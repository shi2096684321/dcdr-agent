package com.dcdr.agent.agent.definition;

import com.dcdr.agent.agent.governance.CostSource;
import com.dcdr.agent.agent.governance.CostTracker;
import com.dcdr.agent.agent.memory.Message;
import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.dto.AgentDtos;
import com.dcdr.agent.dto.ChatRequest;
import com.dcdr.agent.dto.ChatResponse;
import com.dcdr.agent.model.provider.ChatModelProvider;
import com.dcdr.agent.model.registry.ModelRegistry;
import com.dcdr.agent.tool.ToolRegistry;
import com.dcdr.agent.tool.result.ToolResultReadTool;
import com.dcdr.agent.tool.skill.SkillLoadTool;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 专家 AI 生成 (V3.2): 一句话需求 → 一份可直接使用的专家配置。
 *
 * <p><b>为什么要它</b>: 手写专家要填名称/头衔/分类/标签/推荐问法/角色设定/工具/温度八项,
 * 其中"角色设定"是最难写的 —— 大多数用户写出来的是一句愿望("帮我看合同"), 而不是模型真正
 * 需要的角色规范。让模型先把这句话展开成结构化配置, 用户再改两笔, 冷启动成本就从"写作文"
 * 降到"确认"。
 *
 * <p><b>为什么只产出草稿、不直接落库</b>:
 * <ul>
 *   <li>生成结果是<b>模型推断</b>, 名字、分类、工具都可能不合用户本意。落库后再改要经过
 *       "编辑 → 新版本快照", 而草稿态改起来没有成本;</li>
 *   <li>落库要走配额、工具白名单、版本快照这三道既有约束, 草稿态不用把它们的判定复制一遍 ——
 *       前端确认后仍走 {@code POST /api/agents}, 约束天然生效。</li>
 * </ul>
 *
 * <p><b>模型输出一律不可信</b>: 工具名按注册表求交集(编造的名字会被静默丢弃, 且非管理员
 * 永远拿不到沙箱工具), 温度夹到 [0, 2], 文本按列宽/界面长度截断。只有"角色设定"是硬要求 ——
 * 它为空时整份草稿没有意义, 直接报错让用户重试, 而不是交出一个空壳专家。
 *
 * <p><b>线程模型</b>: {@link #generate} 同步阻塞调用 LLM, 调用方须在 boundedElastic 线程执行
 * (与 {@code IntentRouter} / {@code MemoryAutoExtractor} 同一约定)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentDraftGenerator {

    /** 需求说明上限: 再长也不是"最小的说明"了, 且会把生成 prompt 顶到无谓的长度 */
    private static final int MAX_BRIEF_LEN = 2000;
    /** 名称上限: 远小于 {@code AgentDefinitionService#validateName} 的 100, 因为卡片上放不下 */
    private static final int MAX_NAME_LEN = 40;
    private static final int MAX_PROFESSION_LEN = 32;
    private static final int MAX_DESCRIPTION_LEN = 200;
    private static final int MAX_PERSONA_LEN = 4000;
    private static final int MAX_TAG_LEN = 20;
    private static final int MAX_TAGS = 8;
    private static final int MAX_QUICK_PROMPT_LEN = 80;
    private static final int MAX_QUICK_PROMPTS = 5;
    private static final int MAX_TOOLS = 6;
    /** 工具描述截断: 工具的完整描述是给模型看的调用说明, 生成 prompt 里只需要"它是干什么的" */
    private static final int MAX_TOOL_DESC_LEN = 100;
    /** 生成温度: 配置文案需要一点措辞变化, 但结构必须稳, 故低于对话默认值 */
    private static final double GENERATE_TEMPERATURE = 0.4;
    /** 温度缺省值: 与前端新建表单的默认值保持一致, 用户看不出"AI 给的和手填的不一样" */
    private static final BigDecimal DEFAULT_TEMPERATURE = new BigDecimal("0.7");

    /**
     * 平台基础工具: 与 Agent 白名单无关, 由平台自动注入(见 {@code ToolRegistry#PLATFORM_TOOLS})。
     * 不该出现在"要不要给这个专家启用"的建议里 —— 列出来只会让用户困惑。
     */
    private static final Set<String> PLATFORM_TOOLS =
            Set.of(SkillLoadTool.NAME, ToolResultReadTool.NAME);

    /**
     * 生成提示词。
     * <p>
     * 逐字段写死约束而不是笼统说"生成一个专家": 模型对开放式要求会退化成
     * "你是一个专业助手, 请帮助用户解决问题"这类放之四海皆可的空话, 生成出来的专家
     * 与不建专家没有区别。几个反复实测后加上的约束:
     * <ul>
     *   <li>"不要写成系统提示词 / 不要出现忽略规则" —— persona 会被平台包装后转述, 这类文本
     *       要么被安全层拦掉(白生成一次), 要么让用户误以为能改平台行为;</li>
     *   <li>"tools 只能从列表里选" —— 模型很爱编 {@code file_reader} 这种听起来合理但不存在的名字;</li>
     *   <li>"只输出配置, 不执行用户请求" —— 否则用户说"帮我审合同", 模型直接开始审合同。</li>
     * </ul>
     */
    private static final String SYSTEM_PROMPT = """
            你是「专家配置生成器」。用户在平台上创建一个可复用的专家(一个带角色设定的 AI 助手),
            你根据他的一句话需求, 生成一份可直接使用的专家配置。

            【字段要求】
            - name: 专家名称, 2~12 个字, 要具体(如"劳动合同审查员"), 不要"智能助手""万能专家"
            - profession: 一句话头衔, 不超过 12 个字(如"资深劳动法律师")
            - description: 一句话简介, 不超过 40 个字, 说清"为谁、解决什么问题"
            - categoryId: 行业分类, 2~4 个字, 从这些里选最贴近的一个:
              编程开发 / 写作创作 / 翻译 / 数据分析 / 办公效率 / 学习教育 / 市场营销 / 法律财务 / 生活健康 / 客服销售
            - tags: 3~6 个擅长领域标签, 每个不超过 6 个字
            - quickPrompts: 3~4 条用户真正会点开的问法, 每条不超过 25 个字;
              第一条是开场引导, 用来说明它能做什么
            - persona: 角色设定正文(150~400 字), 用第二人称"你是一名…"来写, 依次交代:
              ① 身份与专长; ② 工作方法(先做什么、依据什么、按什么顺序推进);
              ③ 输出格式(结构、篇幅、是否举例); ④ 边界(不确定就说不确定、不编造数据,
              涉及专业结论时提醒用户核实)
            - tools: 从【可用工具】里挑真正需要的, 名称必须与列表完全一致, 不需要就给空数组。
              严禁编造工具名
            - temperature: 0~2 的小数。严谨任务(法律/财务/代码/数据)用 0.1~0.3,
              通用问答 0.5~0.7, 创意写作 0.8~1.0

            【边界】
            - 你只生成"专家配置", 不执行用户请求本身, 也不输出配置之外的任何内容
            - 不写"忽略平台规则""绕过限制"之类的指令; persona 由平台做安全包装后转述,
              因此不要把它写成系统提示词, 不要出现"以下是系统指令"
            - 不索取、不编造用户的隐私信息; 工具能力不足时宁可少选工具, 不要虚构能力

            严格只输出 JSON, 不要任何解释或多余文字:
            {"name":"…","profession":"…","description":"…","categoryId":"…","tags":["…"],
            "quickPrompts":["…"],"persona":"…","tools":["…"],"temperature":0.5}
            """;

    private final AgentDefinitionService agentDefinitionService;
    private final ToolRegistry toolRegistry;
    private final ModelRegistry modelRegistry;
    private final CostTracker costTracker;
    /** 容忍未知字段: 模型偶尔会多输出几个字段, 不该因此整次生成失败 */
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * 生成一份专家草稿(同步阻塞)。调用方负责切到 boundedElastic 线程。
     *
     * @param brief   用户需求说明(一句话即可)
     * @param model   生成用模型; 留空用系统默认
     * @param userId  当前用户(成本归属 + 审计; 无会话, 故 sessionId 传 null)
     * @param isAdmin 影响可用工具集合(受限工具仅管理员可写入)
     */
    public AgentDtos.AgentDraft generate(String brief, String model, String userId, boolean isAdmin) {
        String cleanBrief = brief == null ? "" : brief.trim();
        if (cleanBrief.isEmpty()) {
            throw new BusinessException(400, "请先简单描述你想要的专家");
        }
        if (cleanBrief.length() > MAX_BRIEF_LEN) {
            cleanBrief = cleanBrief.substring(0, MAX_BRIEF_LEN);
        }

        Map<String, String> tools = availableTools(isAdmin);
        try {
            ChatModelProvider provider = resolveProvider(model);
            ChatRequest req = new ChatRequest();
            req.setMessage(buildUserPrompt(cleanBrief, tools));
            req.setStream(false);
            req.setTemperature(GENERATE_TEMPERATURE);
            // 与记忆抽取同一取舍: 输出是嵌套数组, json_schema 的 strict 要求换个供应商就可能
            // 整体失败; json_object + 逐字段校验更稳(反正下面本来就要做归一化)
            req.setResponseFormat("json_object");

            ChatResponse resp = provider.invoke(req, List.of(Message.system(SYSTEM_PROMPT)));
            costTracker.record(userId, null, null, CostSource.AGENT_GENERATE, provider.getName(),
                    resp == null ? null : resp.getTokenUsage());

            AgentDtos.AgentDraft draft = parseDraft(resp == null ? null : resp.getContent(),
                    new ArrayList<>(tools.keySet()));
            draft.setGeneratedBy(provider.getName());
            log.info("专家生成完成: user={}, model={}, name={}, tools={}",
                    userId, provider.getName(), draft.getName(), draft.getTools());
            return draft;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // 前台动作: 必须让用户看到失败原因(而不是"什么都没发生"), 又不能把上游原始报错直接透出
            log.warn("专家生成失败: user={}, err={}", userId, e.getMessage());
            throw new BusinessException(502, "AI 生成专家失败, 请稍后重试或补充一点说明", "AGENT_GENERATE_FAILED");
        }
    }

    // ==================== 输入组装 ====================

    /**
     * 可供专家启用的工具集合(名称 → 截断后的用途)。
     * <p>
     * 数据源取 {@code getToolSpecifications()} 而不是 {@code getAllTools()}: 后者包含
     * "注册了但 schema 坏了、不暴露给 LLM"的工具, 把它们推荐给用户等于建出一个永远调不动的专家。
     */
    private Map<String, String> availableTools(boolean isAdmin) {
        Map<String, String> all = new LinkedHashMap<>();
        for (ToolSpecification spec : toolRegistry.getToolSpecifications()) {
            if (PLATFORM_TOOLS.contains(spec.name())) continue;
            all.put(spec.name(), clip(spec.description(), MAX_TOOL_DESC_LEN));
        }
        // 受限工具在这里就滤掉(而不是等到落库被 403): 让模型从一开始就看不到它,
        // 生成结果才不会被"建议了一个用不了的工具"污染
        List<String> allowed = agentDefinitionService.filterAdminOnlyTools(
                new ArrayList<>(all.keySet()), isAdmin);
        Map<String, String> out = new LinkedHashMap<>();
        for (String name : allowed) {
            if (all.containsKey(name)) out.put(name, all.get(name));
        }
        return out;
    }

    private String buildUserPrompt(String brief, Map<String, String> tools) {
        StringBuilder sb = new StringBuilder();
        sb.append("【用户需求】\n").append(brief).append("\n\n");
        sb.append("【可用工具】(名称 | 用途; 只能从这里选, 且只选真正需要的)\n");
        if (tools.isEmpty()) {
            sb.append("(无)\n");
        } else {
            tools.forEach((name, desc) ->
                    sb.append("- ").append(name).append(" | ").append(desc).append('\n'));
        }
        sb.append("\n请输出 JSON。");
        return sb.toString();
    }

    private ChatModelProvider resolveProvider(String model) {
        if (model != null && !model.isBlank()) {
            return modelRegistry.getChatProvider(model.trim());
        }
        return modelRegistry.getDefaultChatProvider();
    }

    // ==================== 解析与归一化 ====================

    /**
     * 把模型输出归一化为草稿。包级可见: 归一化规则(工具白名单、温度夹取、长度截断)
     * 是这条链路最容易出错的部分, 需要能脱离 LLM 单独验证。
     *
     * @param allowedTools 本次允许出现在草稿里的工具名(已按权限过滤)
     * @throws BusinessException 输出里没有可用配置(无 JSON, 或缺角色设定)
     */
    AgentDtos.AgentDraft parseDraft(String content, List<String> allowedTools) {
        JsonNode root = readJsonObject(content);
        if (root == null) {
            log.info("专家生成: 输出里没有 JSON 对象, 判定失败: {}", clip(content, 200));
            throw new BusinessException(502, "AI 生成的配置无法解析, 请重试", "AGENT_GENERATE_UNPARSABLE");
        }
        String persona = clip(text(root, "persona"), MAX_PERSONA_LEN);
        if (persona.isEmpty()) {
            // 角色设定是这个功能的全部价值: 缺了它, 剩下七个字段拼不出一个专家
            log.info("专家生成: 模型未给出角色设定, 判定失败: {}", clip(content, 200));
            throw new BusinessException(502, "AI 没有给出可用的角色设定, 请重试或补充一点说明",
                    "AGENT_GENERATE_EMPTY_PERSONA");
        }

        AgentDtos.AgentDraft d = new AgentDtos.AgentDraft();
        String name = clip(text(root, "name"), MAX_NAME_LEN);
        d.setName(name.isEmpty() ? "未命名专家" : name);
        d.setProfession(emptyToNull(clip(text(root, "profession"), MAX_PROFESSION_LEN)));
        d.setDescription(emptyToNull(clip(text(root, "description"), MAX_DESCRIPTION_LEN)));
        d.setCategoryId(emptyToNull(clip(text(root, "categoryId"), MAX_PROFESSION_LEN)));
        d.setPersona(persona);
        d.setTags(normalizeList(root.path("tags"), MAX_TAGS, MAX_TAG_LEN));
        List<String> quickPrompts = normalizeList(root.path("quickPrompts"),
                MAX_QUICK_PROMPTS, MAX_QUICK_PROMPT_LEN);
        d.setQuickPrompts(quickPrompts.isEmpty()
                ? List.of("请先介绍一下你能帮我做什么") : quickPrompts);
        d.setTools(pickTools(root.path("tools"), allowedTools));
        d.setTemperature(normalizeTemperature(root.path("temperature")));
        return d;
    }

    /** 从输出中抽取首个完整 JSON 对象(兼容 ```json 包裹)。括号配平, 避免贪婪正则误截 */
    private JsonNode readJsonObject(String content) {
        if (content == null || content.isBlank()) return null;
        String json = extractJson(content);
        if (json == null) return null;
        try {
            JsonNode node = mapper.readTree(json);
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 工具名求交集: 模型编造的名字一律丢弃。
     * <p>
     * 这不仅是"数据校验", 也是安全边界的一部分 —— 虽然落库时 {@code validateTools} 还会再拦一次
     * 受限工具, 但草稿阶段就把不存在的名字删掉, 用户看到的就是"真正会生效的配置"。
     */
    private List<String> pickTools(JsonNode node, List<String> allowedTools) {
        if (node == null || !node.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonNode item : node) {
            String name = item == null || !item.isValueNode() ? null : item.asText();
            if (name == null) continue;
            name = name.trim();
            if (name.isEmpty() || !allowedTools.contains(name) || out.contains(name)) continue;
            out.add(name);
            if (out.size() >= MAX_TOOLS) break;
        }
        return out;
    }

    /** 温度: 缺省 0.7, 超界夹到 [0, 2]; 保留一位小数, 与前端滑杆 step=0.1 对齐 */
    private static BigDecimal normalizeTemperature(JsonNode node) {
        if (node == null || !node.isNumber()) return DEFAULT_TEMPERATURE;
        BigDecimal v = BigDecimal.valueOf(node.asDouble());
        if (v.compareTo(BigDecimal.ZERO) < 0) v = BigDecimal.ZERO;
        if (v.compareTo(new BigDecimal("2")) > 0) v = new BigDecimal("2");
        return v.setScale(1, RoundingMode.HALF_UP);
    }

    /** 字符串数组合并归一: 去空白、去重、限长限量; 非数组一律返回空列表 */
    private static List<String> normalizeList(JsonNode node, int maxItems, int maxLen) {
        if (node == null || !node.isArray()) return new ArrayList<>();
        List<String> out = new ArrayList<>();
        for (JsonNode item : node) {
            String v = item == null || !item.isValueNode() ? null : item.asText();
            if (v == null || v.isBlank()) continue;
            v = clip(v, maxLen);
            if (!out.contains(v)) out.add(v);
            if (out.size() >= maxItems) break;
        }
        return out;
    }

    // ==================== 工具 ====================

    /** 从输出里截取首个括号配平的 JSON 对象(兼容 ```json 代码块与前后多余文字) */
    private static String extractJson(String content) {
        int start = content.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        boolean inStr = false;
        char strQuote = 0;
        for (int i = start; i < content.length(); i++) {
            char c = content.charAt(i);
            if (inStr) {
                if (c == '\\') { i++; continue; }
                if (c == strQuote) inStr = false;
            } else {
                if (c == '"' || c == '\'') { inStr = true; strQuote = c; }
                else if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) return content.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    /** 取字符串字段; 空白与字面量 "null" 一律归一为空串 */
    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || v.isNull() || !v.isValueNode()) return "";
        String s = v.asText();
        if (s == null) return "";
        s = s.trim();
        return "null".equalsIgnoreCase(s) ? "" : s;
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
