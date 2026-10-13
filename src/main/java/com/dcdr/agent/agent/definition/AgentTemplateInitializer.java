package com.dcdr.agent.agent.definition;

import com.dcdr.agent.entity.AgentDefinitionEntity;
import com.dcdr.agent.repository.AgentDefinitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * V2.3 预置 Agent 模板初始化器。
 * <p>
 * 应用启动时检查模板表, 若为空则插入一批常用预设模板 (isTemplate=true)。
 * 管理员后续可在 Agent 管理界面增删改这些模板, 重启不会丢失 (只首次插入)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentTemplateInitializer implements ApplicationRunner {

    private final AgentDefinitionRepository repository;

    @Override
    public void run(ApplicationArguments args) {
        long existing = repository.findByIsTemplateTrueOrderByCreatedAtDesc().size();
        if (existing > 0) {
            log.info("Agent 模板库已有 {} 个模板, 跳过预置初始化", existing);
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<AgentDefinitionEntity> presets = buildPresetTemplates(now);
        int inserted = 0;
        for (AgentDefinitionEntity t : presets) {
            // V3.1 F-46 多实例: 两个实例同时首启会都判定"库为空" → 同时插入相同主键(tpl_*)
            // → 后到的那个撞唯一约束, 异常从 ApplicationRunner 抛出会**导致启动失败**。
            // 这里用 saveAndFlush 让约束冲突在本行抛出并被吞掉(而不是拖到事务提交时),
            // 于是"并发初始化"退化为"一个成功、另一个静默跳过"。
            // 用 flush 而非普通 save 的原因就在这里: save 的 INSERT 可能在方法返回后才 flush,
            // 那时异常已经脱离 try 块, 兜不住。
            // 未用集群锁的原因: 锁只解决"同时", 且持锁实例崩溃会让另一个实例跳过初始化 →
            // 模板缺失; 而对唯一约束容错是确定性的, 与实例数、时序无关。
            try {
                repository.saveAndFlush(t);
                inserted++;
            } catch (DataIntegrityViolationException e) {
                log.debug("预置模板 {} 已由其他实例写入, 跳过", t.getId());
            }
        }
        log.info("Agent 预置模板初始化完成: 本次插入 {}/{} 个模板", inserted, presets.size());
    }

    /** 构建预置模板列表 (系统级, userId=null, isTemplate=true) */
    private List<AgentDefinitionEntity> buildPresetTemplates(LocalDateTime now) {
        return List.of(
                build("tpl_translator", "翻译助手",
                        "专业中英互译助手, 保持专业术语准确, 文学性内容保留原文风格",
                        "你是一位资深翻译专家, 精通中英互译。翻译时:\n" +
                        "1. 保持专业术语准确统一\n" +
                        "2. 文学性内容保留原文风格与修辞\n" +
                        "3. 技术文档确保逻辑清晰\n" +
                        "4. 如遇歧义, 给出两种译法并标注区别\n" +
                        "请直接给出译文, 无需额外解释。",
                        null, null, new BigDecimal("0.30"), null, null, now),

                build("tpl_coder", "代码助手",
                        "编程问题解答, 提供可运行代码示例与原理说明",
                        "你是一位经验丰富的软件工程师, 擅长多种编程语言。\n" +
                        "回答要求:\n" +
                        "1. 先给出简洁的解决思路\n" +
                        "2. 提供可直接运行的完整代码示例\n" +
                        "3. 关键代码行加注释说明\n" +
                        "4. 指出常见坑与最佳实践\n" +
                        "5. 如有多种方案, 简述各自优劣",
                        null, List.of("calculator", "time"), new BigDecimal("0.20"), null, null, now),

                build("tpl_copywriter", "文案创作",
                        "营销文案创作, 有感染力, 适合社交媒体传播",
                        "你是一位创意文案大师, 擅长各类营销文案创作。\n" +
                        "创作原则:\n" +
                        "1. 标题抓人眼球, 3 秒内引起兴趣\n" +
                        "2. 正文有节奏感, 善用短句与排比\n" +
                        "3. 融入场景感与情绪共鸣\n" +
                        "4. 结尾有明确行动召唤 (CTA)\n" +
                        "5. 适配用户指定的平台风格 (小红书/微信/微博等)",
                        null, List.of("web_search"), new BigDecimal("1.00"), null, null, now),

                build("tpl_analyst", "数据分析师",
                        "数据分析与解读, 给出结论性判断与行动建议",
                        "你是一位严谨的数据分析师, 擅长从数据中提炼洞察。\n" +
                        "分析要求:\n" +
                        "1. 先概述数据概况与关键指标\n" +
                        "2. 识别趋势、异常与关联性\n" +
                        "3. 给出明确的结论性判断\n" +
                        "4. 提供可落地的行动建议\n" +
                        "5. 标注数据局限性, 不过度推断",
                        null, List.of("calculator"), new BigDecimal("0.20"), null, null, now),

                build("tpl_kb_qa", "知识库问答",
                        "基于知识库内容严谨回答, 标注来源, 拒绝臆测",
                        "你是一位严谨的知识库问答助手。\n" +
                        "回答规则:\n" +
                        "1. 仅基于检索到的知识库片段回答, 不编造信息\n" +
                        "2. 回答末尾标注引用的知识库来源\n" +
                        "3. 知识库未覆盖的问题, 明确告知'知识库中未找到相关内容'\n" +
                        "4. 涉及多条信息时, 分点结构化呈现\n" +
                        "5. 保持客观中立, 不添加个人观点",
                        null, List.of("knowledge_base_search"), new BigDecimal("0.10"), null, null, now),

                build("tpl_summarizer", "内容总结",
                        "长文压缩为要点摘要, 保留核心信息, 去除冗余",
                        "你是一位高效的内容总结专家。\n" +
                        "总结规则:\n" +
                        "1. 提取 3-5 个核心要点, 每点不超过两句话\n" +
                        "2. 保留关键数据、结论与行动项\n" +
                        "3. 去除举例、铺垫与重复内容\n" +
                        "4. 按重要性排序, 最关键的信息在前\n" +
                        "5. 结尾用一句话概括全文主旨",
                        null, null, new BigDecimal("0.30"), null, null, now)
        );
    }

    private AgentDefinitionEntity build(String id, String name, String description, String systemPrompt,
                                        String model, List<String> tools, BigDecimal temperature,
                                        List<String> kbIds, Boolean isPublic, LocalDateTime now) {
        AgentDefinitionEntity e = new AgentDefinitionEntity();
        e.setId(id);
        e.setUserId(null); // 系统级模板
        e.setName(name);
        e.setDescription(description);
        e.setSystemPrompt(systemPrompt);
        e.setModel(model);
        e.setTools(tools != null ? String.join(",", tools) : null);
        e.setKbIds(kbIds != null ? String.join(",", kbIds) : null);
        e.setTemperature(temperature);
        e.setPublic(isPublic != null && isPublic);
        e.setTemplate(true);
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        return e;
    }
}
