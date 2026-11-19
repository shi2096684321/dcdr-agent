package com.dcdr.agent.agent.prompt;

import com.dcdr.agent.agent.memory.Message;
import com.dcdr.agent.agent.memory.TokenEstimator;
import com.dcdr.agent.dto.ContextUsage;
import com.dcdr.agent.skill.SkillCatalogProvider;
import com.dcdr.agent.tool.Tool;
import com.dcdr.agent.tool.ToolRegistry;
import com.dcdr.agent.tool.mcp.McpTool;
import com.dcdr.agent.tool.plugin.PluginToolAdapter;
import com.dcdr.agent.workspace.WorkspacePromptProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 上下文占用的分段统计 (V3.4) —— 把"发给模型的一整坨上下文"拆成用户看得懂的几笔账。
 *
 * <p><b>统计对象是"实际发出的东西", 不是配置</b>: 分段来源全部取自本轮真实组装的
 * {@code history}(含平台提示词、人设、记忆、RAG、运行时约束、工作区摘要)与真实下发的工具定义,
 * 因此"改了配置但没生效"这类问题在这里会直接暴露为某段为 0。
 *
 * <p><b>分类依据</b>: system 消息按注入时写下的 {@code name} 归类 —— 这是这套注入机制本来就维护的
 * 去重键, 拿它当分类键不需要让任何 Provider 多报一个字段, 也不会与注入逻辑脱节。
 * 工具按注册类型分: MCP 工具({@link McpTool})与插件({@link PluginToolAdapter})归"连接器与 MCP",
 * 其余归"工具及子智能体"(本仓库暂无子智能体概念, 该分类先只统计工具定义)。
 *
 * <p><b>默认是估算, 收尾可被实测校准</b>: {@link #compute} 走 cl100k BPE; 模型返回了真实 usage 时,
 * 由 {@link #calibrate} 用"实测 prompt + 答案估算"覆盖总量与分段 —— 这是"已使用"能与模型口径
 * 对上的唯一办法(分词器/图片/消息包装的差异全被真实值吸收)。未实测时仍是估算, 界面标注不变。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContextUsageService {

    /** 分段 key: 前端按它取色, 不要用 label 匹配 */
    public static final String KEY_SYSTEM = "system";
    public static final String KEY_TOOLS = "tools";
    public static final String KEY_MESSAGES = "messages";
    public static final String KEY_CONNECTORS = "connectors";
    public static final String KEY_SKILLS = "skills";

    /** 属于"技能"分类的注入片段(目录 + 本轮手动指定的正文) */
    private static final Set<String> SKILL_FRAGMENT_NAMES = Set.of(
            SkillCatalogProvider.PROMPT_NAME, SkillCatalogProvider.MANUAL_PROMPT_NAME);

    /**
     * 系统提示词内的片段名 → 人话。
     * <p>未收录的名字直接显示原名: 新增注入片段时宁可露出一个 tech 名字, 也不要被静默归进"其它"
     * —— 那会让"这轮到底多注入了什么"永远查不出来。
     */
    private static final Map<String, String> SYSTEM_FRAGMENT_LABELS = Map.ofEntries(
            Map.entry("agent:persona", "专家人设"),
            Map.entry("memory:long-term", "长期记忆"),
            Map.entry("memory:summary", "历史摘要"),
            Map.entry("runtime:rag-context", "知识库检索"),
            Map.entry("rag:forced", "知识库检索(未命中提示)"),
            Map.entry(WorkspacePromptProvider.FRAGMENT_NAME, "工作区摘要"),
            Map.entry("runtime:current-time", "当前时间"),
            Map.entry("runtime:static", "运行时约束(常量)"),
            Map.entry("runtime:multi-turn", "多轮对话约束"),
            Map.entry("runtime:web-search", "联网搜索引导"),
            Map.entry("runtime:image-policy", "生图策略"),
            Map.entry("runtime:tool-data", "外部数据边界"));

    private static final String PLATFORM_PROMPT_LABEL = "平台系统提示词";

    /**
     * 单条消息在协议里的固定开销(role 分隔符 / 消息包装)。
     * <p>各家在 3~4 token, 取 4 —— 这一项逐条累积, 上百条历史时就是几百 token。
     * 漏算它会让所有"逐条相加"的结果系统性偏低。
     */
    private static final int MESSAGE_OVERHEAD_TOKENS = 4;

    /**
     * 单张图片的估算占用。
     * <p>视觉模型的图片 token 由分辨率折算(常见 1K~2K), 与 base64 字节数弱相关,
     * 无法从 data URL 低成本还原, 故取固定估值。历史图片每轮都会重发, 漏算会让视觉轮次严重偏低。
     * 若模型返回了真实 usage, 收尾那次会以实测值校准, 这里的估值只影响"模型未返回 usage"的场景。
     */
    private static final int IMAGE_TOKENS_ESTIMATE = 1_000;

    private final TokenEstimator tokenEstimator;
    private final ToolRegistry toolRegistry;

    /** 序列化/反序列化共用(快照落 sessions.context_usage); ObjectMapper 线程安全 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 统计本轮上下文占用。
     *
     * @param history  本轮实际发给模型的消息列表(须已含当前用户消息)
     * @param toolDefs 本轮实际下发的 OpenAI 格式工具定义(可为空: 模型不支持函数调用时)
     * @param limit    当前生效的总上下文上限
     */
    public ContextUsage compute(List<Message> history, List<Map<String, Object>> toolDefs, int limit) {
        ContextUsage usage = new ContextUsage();
        usage.setAvailable(true);
        usage.setLimitTokens(limit);

        List<ContextUsage.Segment> segments = new ArrayList<>(5);
        segments.add(systemSegment(history));
        segments.add(toolsSegment(toolDefs));
        segments.add(messagesSegment(history));
        segments.add(connectorsSegment(toolDefs));
        segments.add(skillsSegment(history));

        int total = 0;
        for (ContextUsage.Segment s : segments) {
            total += s.getTokens();
        }
        for (ContextUsage.Segment s : segments) {
            s.setPercent(round1(total == 0 ? 0 : s.getTokens() * 100.0 / total));
        }
        usage.setSegments(segments);
        usage.setTotalTokens(total);
        usage.setPercent(percent(total, limit));
        return usage;
    }

    /** 系统提示词: 除技能以外的全部 system 消息, 按片段名合并 */
    private ContextUsage.Segment systemSegment(List<Message> history) {
        Map<String, int[]> byLabel = new LinkedHashMap<>();
        for (Message m : history) {
            if (!"system".equals(m.getRole())) continue;
            String name = m.getName();
            if (name != null && SKILL_FRAGMENT_NAMES.contains(name)) continue;
            String label = name == null ? PLATFORM_PROMPT_LABEL
                    : SYSTEM_FRAGMENT_LABELS.getOrDefault(name, name);
            addTo(byLabel, label, tokensOf(m));
        }
        return segment(KEY_SYSTEM, "系统提示词", byLabel);
    }

    /** 技能: 技能目录 + 本轮手动指定技能的正文 */
    private ContextUsage.Segment skillsSegment(List<Message> history) {
        Map<String, int[]> byLabel = new LinkedHashMap<>();
        for (Message m : history) {
            if (!"system".equals(m.getRole())) continue;
            String name = m.getName();
            if (name == null || !SKILL_FRAGMENT_NAMES.contains(name)) continue;
            String label = SkillCatalogProvider.PROMPT_NAME.equals(name) ? "技能目录" : "指定技能正文";
            addTo(byLabel, label, tokensOf(m));
        }
        return segment(KEY_SKILLS, "技能", byLabel);
    }

    /** 对话消息: 用户提问 / 助手回答 / 工具结果(含工具调用的结构化参数, 与发给模型的口径一致) */
    private ContextUsage.Segment messagesSegment(List<Message> history) {
        Map<String, int[]> byLabel = new LinkedHashMap<>();
        for (Message m : history) {
            if ("system".equals(m.getRole())) continue;
            addTo(byLabel, roleLabel(m.getRole()), tokensOf(m));
        }
        return segment(KEY_MESSAGES, "对话消息", byLabel);
    }

    private ContextUsage.Segment toolsSegment(List<Map<String, Object>> toolDefs) {
        return toolSegment(KEY_TOOLS, "工具及子智能体", toolDefs, false);
    }

    private ContextUsage.Segment connectorsSegment(List<Map<String, Object>> toolDefs) {
        return toolSegment(KEY_CONNECTORS, "连接器与MCP", toolDefs, true);
    }

    /**
     * 工具定义分段。工具定义是每轮重发的固定成本, 也是"关掉几个连接器能省多少"的唯一答案,
     * 所以按工具名逐条列出, 不做聚合。
     *
     * @param connectorSide true=只统计 MCP/插件工具; false=只统计其余工具
     */
    private ContextUsage.Segment toolSegment(String key, String label,
                                             List<Map<String, Object>> toolDefs, boolean connectorSide) {
        Map<String, int[]> byLabel = new LinkedHashMap<>();
        if (toolDefs != null) {
            for (Map<String, Object> def : toolDefs) {
                String name = toolNameOf(def);
                if (name == null) continue;
                if (isConnectorTool(name) != connectorSide) continue;
                addTo(byLabel, name, estimateJson(def));
            }
        }
        return segment(key, label, byLabel);
    }

    /** 工具归属: MCP 与插件都算"连接器" —— 它们都是外部提供的能力, 可整体摘掉 */
    private boolean isConnectorTool(String name) {
        // 工具已注销时 getTool 返回 null: 归到"工具"侧而不是丢掉, 否则总数会对不上
        Tool tool = toolRegistry.getTool(name);
        return tool instanceof McpTool || tool instanceof PluginToolAdapter;
    }

    @SuppressWarnings("unchecked")
    private static String toolNameOf(Map<String, Object> def) {
        Object fn = def.get("function");
        if (!(fn instanceof Map)) return null;
        Object name = ((Map<String, Object>) fn).get("name");
        return name == null ? null : String.valueOf(name);
    }

    private int estimateJson(Object value) {
        try {
            return tokenEstimator.estimate(MAPPER.writeValueAsString(value));
        } catch (Exception e) {
            // 序列化失败时退化为 toString 估算: 少算总比整段消失好, 且不会影响对话主链路
            log.warn("上下文统计: 工具定义序列化失败, 退化为 toString 估算: {}", e.getMessage());
            return tokenEstimator.estimate(String.valueOf(value));
        }
    }

    /**
     * 工具定义的 token 总量(与"工具及子智能体"分段同一口径)。
     * <p>
     * 供"模型未返回 usage 时的兜底估算"复用: 调用方手里的上下文消息列表不含工具定义,
     * 若各自补一遍就会形成两套口径。这里公开出来, 保证兜底值与面板值同源。
     */
    public int estimateToolDefs(List<Map<String, Object>> toolDefs) {
        if (toolDefs == null || toolDefs.isEmpty()) return 0;
        return estimateJson(toolDefs);
    }

    /**
     * 单条消息在<b>下一轮会被重发</b>的 token 数。
     * <p>
     * 与 {@code TokenEstimator#estimate(List)} 的差别只有一条: <b>不计思维链</b>。
     * 那个方法的口径是"这条消息里有多少字"(用于成本兜底估算), 而占用面板要回答的是
     * "下一轮请求里还有多少字" —— 本项目 Provider 不回传历史消息的 reasoning(见
     * {@code LangChain4jChatProvider#appendHistoryMessage}), 把它算进去会让思考型模型的占用
     * 凭空翻倍, 用户照着这个数字去开新会话就是白开。
     */
    private int tokensOf(Message m) {
        int tokens = MESSAGE_OVERHEAD_TOKENS + tokenEstimator.estimate(m.getContent());
        // 工具往返: assistant 的 tool_calls 结构化字段与 tool 角色的名称/调用 id 同样会被重发
        if (m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {
            tokens += estimateJson(m.getToolCalls());
        }
        if (m.getToolCallId() != null) tokens += tokenEstimator.estimate(m.getToolCallId());
        if (m.getName() != null) tokens += tokenEstimator.estimate(m.getName());
        // 只有 user 的图片会以 image_url 发给模型; assistant 的生图结果在协议出口被改写成占位文本, 不计
        if ("user".equals(m.getRole()) && m.getImages() != null && !m.getImages().isEmpty()) {
            tokens += m.getImages().size() * IMAGE_TOKENS_ESTIMATE;
        }
        return tokens;
    }

    private static void addTo(Map<String, int[]> acc, String label, int tokens) {
        int[] v = acc.computeIfAbsent(label, k -> new int[2]);
        v[0] += tokens;
        v[1] += 1;
    }

    private static ContextUsage.Segment segment(String key, String label, Map<String, int[]> byLabel) {
        ContextUsage.Segment s = new ContextUsage.Segment();
        s.setKey(key);
        s.setLabel(label);
        int total = 0;
        List<ContextUsage.Item> items = new ArrayList<>(byLabel.size());
        for (Map.Entry<String, int[]> e : byLabel.entrySet()) {
            ContextUsage.Item item = new ContextUsage.Item();
            item.setLabel(e.getKey());
            item.setTokens(e.getValue()[0]);
            item.setCount(e.getValue()[1]);
            items.add(item);
            total += e.getValue()[0];
        }
        // 段内按 token 降序: 明细里最该被看见的是"最贵的那几项"
        items.sort((a, b) -> Integer.compare(b.getTokens(), a.getTokens()));
        s.setItems(items);
        s.setTokens(total);
        return s;
    }

    private static String roleLabel(String role) {
        if ("user".equals(role)) return "用户消息";
        if ("assistant".equals(role)) return "助手回答";
        if ("tool".equals(role)) return "工具结果";
        return role;
    }

    private static double percent(int total, int limit) {
        if (limit <= 0) return 0;
        return round1(total * 100.0 / limit);
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    // ===== 实测校准 =====

    /**
     * 用"模型实测的总用量"校准已用总量 (V3.4 修正)。
     *
     * <p><b>为什么必须校准</b>: 分段统计走 cl100k 估算, 而真实请求由模型自己的分词器计量,
     * 再叠加"图片按固定估值""每条消息协议开销"这些近似 —— 同一轮里面板的数字与模型返回的
     * usage 天然对不上。模型既然给了真实用量, 就没有理由继续把估算值当分子。
     *
     * <p><b>口径</b>: 分子直接取厂商返回的 {@code prompt + completion}, 不再叠加任何估算 ——
     * 用户要的是"当前对话实际用了多少", 任何二次加工都只是让数字离真实更远。
     * 唯一偏差是 completion 含思维链、而思维链下一轮不重发, 所以它比"严格窗口占用"略高;
     * 这个差异不做修正, 修正就又回到估算。
     *
     * <p>分段与段内明细仍按估算比例分摊(厂商不会给分段明细), 用最大余数补齐,
     * 保证"五段之和 == totalTokens"不变 —— 界面拿它当账单, 和差一个 token 都会被用户发现。
     *
     * @param usage        已算好的估算结果(用于分段与段内比例)
     * @param targetTokens 模型实测总用量; {@code <= 0} 表示没有可信实测, 原样返回
     */
    public ContextUsage calibrateTo(ContextUsage usage, int targetTokens) {
        if (usage == null || targetTokens <= 0) return usage;
        int target = targetTokens;
        int estimated = usage.getTotalTokens();
        if (estimated <= 0 || target == estimated) return usage;

        List<ContextUsage.Segment> segments = usage.getSegments();
        if (segments == null || segments.isEmpty()) return usage;
        int[] segTokens = new int[segments.size()];
        for (int i = 0; i < segments.size(); i++) {
            segTokens[i] = segments.get(i).getTokens();
        }
        int[] scaledSeg = scale(segTokens, target);

        for (int i = 0; i < segments.size(); i++) {
            ContextUsage.Segment seg = segments.get(i);
            List<ContextUsage.Item> items = seg.getItems();
            if (items != null && !items.isEmpty()) {
                int[] itemTokens = new int[items.size()];
                for (int j = 0; j < items.size(); j++) {
                    itemTokens[j] = items.get(j).getTokens();
                }
                int[] scaledItems = scale(itemTokens, scaledSeg[i]);
                for (int j = 0; j < items.size(); j++) {
                    items.get(j).setTokens(scaledItems[j]);
                }
            }
            seg.setTokens(scaledSeg[i]);
        }

        int total = 0;
        for (ContextUsage.Segment s : segments) total += s.getTokens();
        for (ContextUsage.Segment s : segments) {
            s.setPercent(round1(total == 0 ? 0 : s.getTokens() * 100.0 / total));
        }
        usage.setTotalTokens(total);
        usage.setPercent(percent(total, usage.getLimitTokens()));
        return usage;
    }

    /**
     * 按比例把一组计数缩放到 {@code target}, 余数补给最大项。
     * <p>用最大余数而不是逐项四舍五入: 后者会让分量之和与目标差出好几个 token,
     * 而界面明确要求"五段之和 == 总数"。
     */
    private static int[] scale(int[] values, int target) {
        int[] out = new int[values.length];
        if (values.length == 0) return out;
        long sum = 0;
        for (int v : values) sum += v;
        if (sum <= 0 || target <= 0) return out;

        int maxIdx = 0;
        long acc = 0;
        for (int i = 0; i < values.length; i++) {
            out[i] = (int) (values[i] * (long) target / sum);
            acc += out[i];
            if (values[i] > values[maxIdx]) maxIdx = i;
        }
        // 余数(最多一项)补给最大分段: 它最不容易因 +1 显得突兀
        out[maxIdx] += (int) (target - acc);
        return out;
    }

    // ===== 快照存取 =====

    /** 快照序列化(落 sessions.context_usage)。失败返回 null —— 存不下就不存, 不影响本轮对话 */
    public String toJson(ContextUsage usage) {
        try {
            return MAPPER.writeValueAsString(usage);
        } catch (Exception e) {
            log.warn("上下文快照序列化失败(不影响对话): {}", e.getMessage());
            return null;
        }
    }

    /**
     * 读取快照并按<b>当前</b>上限重算百分比。
     * <p>必须重算而不是原样返回: 用户改了总上下文之后, 百分比的分母跟着变,
     * 但快照里的分子(各类实际占用)没变。直接返回旧值会让界面在"改了设置"后仍显示旧比例。
     *
     * @param json  快照(可为 null)
     * @param limit 当前生效上限
     * @return 无快照或快照损坏时返回 {@code available=false} 的空结构(界面显示空态)
     */
    public ContextUsage fromJson(String json, int limit) {
        if (json == null || json.isBlank()) return empty(limit);
        try {
            ContextUsage usage = MAPPER.readValue(json, ContextUsage.class);
            if (usage == null || usage.getSegments() == null || usage.getSegments().isEmpty()) {
                return empty(limit);
            }
            usage.setAvailable(true);
            usage.setLimitTokens(limit);
            usage.setPercent(percent(usage.getTotalTokens(), limit));
            return usage;
        } catch (Exception e) {
            // 结构升级后老快照可能解析不了: 降级为空态(下次对话会覆盖), 不抛给前端
            log.warn("上下文快照解析失败, 按空态返回: {}", e.getMessage());
            return empty(limit);
        }
    }

    /** 空态: 带上当前上限, 让界面在没有数据时也能显示"上限多少" */
    public ContextUsage empty(int limit) {
        ContextUsage usage = new ContextUsage();
        usage.setAvailable(false);
        usage.setLimitTokens(limit);
        usage.setPercent(0);
        usage.setTotalTokens(0);
        return usage;
    }

    /**
     * 补上"本轮消耗"(收尾时调用)。
     * <p>
     * 单独一步而不是塞进 {@link #compute}: 计算占用时答案还没生成, 输入/输出根本不存在 ——
     * 若那时留 0, 界面会显示"本轮 0 token", 比不显示更糟。
     *
     * @param measured 是否来自模型返回的真实 usage(否则为估算)
     * @return 原对象(便于链式), 已写入 {@code lastRound}
     */
    public ContextUsage withRound(ContextUsage usage, int prompt, int completion, int total, boolean measured) {
        if (usage == null) return null;
        ContextUsage.Round round = new ContextUsage.Round();
        round.setPrompt(prompt);
        round.setCompletion(completion);
        round.setTotal(total);
        round.setMeasured(measured);
        usage.setLastRound(round);
        return usage;
    }
}
