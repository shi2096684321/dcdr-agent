package com.dcdr.agent.agent.workflow;

import com.dcdr.agent.entity.WorkflowDefinitionEntity;
import com.dcdr.agent.repository.WorkflowDefinitionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * V2.3 预置工作流模板初始化器。
 * <p>
 * 应用启动时检查工作流模板表, 若为空则插入一批常用预设模板 (isTemplate=true),
 * 覆盖串行/并行/条件三种编排模式。管理员后续可在工作流管理界面增删改这些模板,
 * 重启不会丢失 (只首次插入)。
 * <p>
 * <b>依赖</b>: 预置 Agent 模板 (tpl_*) 必须先于本初始化器执行, 因此通过 @Order(20)
 * 确保晚于 {@code AgentTemplateInitializer} (默认 order=0)。
 */
@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
public class WorkflowTemplateInitializer implements ApplicationRunner {

    private final WorkflowDefinitionRepository repository;
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void run(ApplicationArguments args) {
        long existing = repository.findByIsTemplateTrueOrderByCreatedAtDesc().size();
        if (existing > 0) {
            log.info("工作流模板库已有 {} 个模板, 跳过预置初始化", existing);
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<WorkflowDefinitionEntity> templates = buildPresetTemplates(now);
        int inserted = 0;
        for (WorkflowDefinitionEntity t : templates) {
            // V3.1 F-46 多实例: 与 AgentTemplateInitializer 同因 —— 两个实例同时首启会插入相同
            // 主键(wf_tpl_*) 撞唯一约束; 约束冲突从 ApplicationRunner 抛出会**导致启动失败**。
            // 故逐条 saveAndFlush 并容错: 一个成功, 另一个静默跳过(详见该类注释)。
            try {
                repository.saveAndFlush(t);
                inserted++;
            } catch (DataIntegrityViolationException e) {
                log.debug("预置工作流模板 {} 已由其他实例写入, 跳过", t.getId());
            }
        }
        log.info("工作流预置模板初始化完成: 本次插入 {}/{} 个模板", inserted, templates.size());
    }

    /** 构建预置工作流模板 (系统级, userId=null, isTemplate=true) */
    private List<WorkflowDefinitionEntity> buildPresetTemplates(LocalDateTime now) {
        List<WorkflowDefinitionEntity> list = new ArrayList<>();
        // 1. 串行: 翻译并总结 (英文 → 中文翻译 → 提炼要点)
        list.add(build("wf_tpl_translate_summarize", "翻译并总结",
                "先将英文内容翻译为中文, 再提取核心要点。适合处理外文资料。",
                "sequential",
                steps(
                        step("translate", "tpl_translator",
                                "请将以下英文内容翻译为中文:\n\n${user_message}", null),
                        step("summarize", "tpl_summarizer",
                                "请总结以下内容的 3-5 个核心要点:\n\n${translate}", null)
                ), now));

        // 2. 串行: 技术博客创作 (主题 → 大纲 → 正文 → 摘要)
        list.add(build("wf_tpl_blog_creation", "技术博客创作",
                "三步创作: 生成技术要点 → 扩写为博客正文 → 提炼摘要。适合内容创作场景。",
                "sequential",
                steps(
                        step("outline", "tpl_coder",
                                "针对以下主题, 列出 5 个核心技术要点, 每个要点附一句话说明:\n\n主题: ${user_message}", null),
                        step("draft", "tpl_copywriter",
                                "基于以下技术要点, 写一篇 800 字左右的博客文章, 要求标题吸引人、结构清晰、有代码示例:\n\n技术要点:\n${outline}", null),
                        step("abstract", "tpl_summarizer",
                                "为以下博客文章写一段 100 字以内的摘要:\n\n${draft}", null)
                ), now));

        // 3. 并行: 多视角分析 (数据分析师 + 知识库问答 同时分析, 汇总)
        list.add(build("wf_tpl_multi_perspective", "多视角分析",
                "并行调用数据分析师和知识库问答, 从不同角度分析同一问题, 最后汇总输出。",
                "parallel",
                steps(
                        step("data_analysis", "tpl_analyst",
                                "从数据分析角度分析以下问题, 给出结论和建议:\n\n${user_message}", null),
                        step("kb_lookup", "tpl_kb_qa",
                                "基于知识库回答以下问题:\n\n${user_message}", null)
                ), now));

        // 4. 条件: 智能路由 (判断是否编程问题 → 走代码助手或文案创作)
        list.add(build("wf_tpl_smart_route", "智能客服路由",
                "根据输入内容判断: 含编程问题走代码助手, 否则走文案创作。演示条件分支编排。",
                "conditional",
                steps(
                        step("classify", "tpl_summarizer",
                                "判断以下内容是否与编程/代码相关, 只回答 YES 或 NO:\n\n${user_message}", null),
                        step("code_path", "tpl_coder",
                                "解答以下编程问题:\n\n${user_message}",
                                "contains(${classify}, \"YES\")"),
                        step("text_path", "tpl_copywriter",
                                "针对以下主题写一段文案:\n\n${user_message}",
                                "not:contains(${classify}, \"YES\")")
                ), now));

        // 5. 串行: 内容审校 (草稿 → 审校 → 终稿)
        list.add(build("wf_tpl_review_pipeline", "内容审校流水线",
                "三步审校: 生成初稿 → 审校修改 → 润色定稿。适合高质量内容产出场景。",
                "sequential",
                steps(
                        step("draft", "tpl_copywriter",
                                "针对以下主题写一段 300 字左右的初稿:\n\n${user_message}", null),
                        step("review", "tpl_analyst",
                                "审校以下内容, 指出逻辑漏洞、事实错误和改进点, 给出修改建议:\n\n${draft}", null),
                        step("finalize", "tpl_copywriter",
                                "根据以下审校建议, 修改并润色原文, 输出终稿:\n\n原文:\n${draft}\n\n审校建议:\n${review}", null)
                ), now));

        return list;
    }

    // ===== 内部辅助: 构造步骤 JSON =====

    private String steps(Map<String, Object>... stepMaps) {
        try {
            return mapper.writeValueAsString(List.of(stepMaps));
        } catch (Exception e) {
            throw new RuntimeException("序列化预置工作流步骤失败", e);
        }
    }

    private Map<String, Object> step(String name, String agentId, String input, String condition) {
        return Map.of(
                "name", name,
                "agentId", agentId,
                "input", input,
                "condition", condition != null ? condition : ""
        );
    }

    private WorkflowDefinitionEntity build(String id, String name, String description,
                                            String mode, String stepsJson, LocalDateTime now) {
        WorkflowDefinitionEntity e = new WorkflowDefinitionEntity();
        e.setId(id);
        e.setUserId(null); // 系统级模板
        e.setName(name);
        e.setDescription(description);
        e.setMode(mode);
        e.setStepsJson(stepsJson);
        e.setEnabled(true);
        e.setTemplate(true);
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        return e;
    }
}
