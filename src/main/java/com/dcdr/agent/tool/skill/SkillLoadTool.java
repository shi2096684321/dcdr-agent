package com.dcdr.agent.tool.skill;

import com.dcdr.agent.skill.SkillContextHolder;
import com.dcdr.agent.skill.SkillService;
import com.dcdr.agent.tool.AbstractTool;
import com.dcdr.agent.tool.ToolInfo;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 技能按需加载工具 (V3.1 专家体系) —— 渐进式披露的第二层。
 * <p>
 * 与 {@code KnowledgeSearchTool} 的分工: 知识库检索回答"资料里怎么写的"(事实),
 * 技能加载回答"这类任务该怎么做"(流程与方法)。两者都按需触发, 都只暴露一个 query/name 参数。
 * <p>
 * 工具只暴露 {@code name} 与 {@code path} 给模型; "当前是谁在问"由
 * {@link SkillContextHolder} 从对话上下文注入 —— 否则无法按归属过滤技能,
 * 可能加载到他人的私有技能。
 * <p>
 * <b>只读工具, 无副作用</b>: 加载技能不产生任何写入、不触发外部调用, 因此不需要审计留痕
 * (与"每次工具调用都写审计"的口径冲突)。
 */
@Slf4j
@Component
public class SkillLoadTool extends AbstractTool {

    private final SkillService skillService;

    public SkillLoadTool(SkillService skillService) {
        this.skillService = skillService;
    }

    @Override
    public String getName() {
        return "skill_load";
    }

    @Override
    public String getDescription() {
        return "加载【技能】的完整指令, 或读取技能内的某份参考资源。\n" +
                "\n" +
                "【何时调用】\n" +
                "- 任务属于某个已知技能覆盖的领域(见系统提示中的「可用技能」清单)时\n" +
                "- 你需要该技能里写明的具体流程、规范、模板或检查清单时\n" +
                "\n" +
                "【何时不要调用】\n" +
                "- 不传 path 时返回技能指令全文, 所以同一个技能在一轮里只调用一次即可\n" +
                "- 技能清单里没有的技能: 不要凭猜测编名字, 直接告诉用户该技能不可用\n" +
                "- 与当前任务无关的技能: 不要为了\"可能有用\"而加载\n" +
                "\n" +
                "【参数说明】\n" +
                "- name(必填): 技能标识, 必须逐字取自系统提示中的「可用技能」清单\n" +
                "- path(选填): 技能内的参考资源路径。省略则返回技能指令正文;\n" +
                "  指令里若提到某份参考文档(如 references/faq.md), 再用 path 单独取回,\n" +
                "  避免一次性读入不需要的内容";
    }

    @Override
    public JsonObjectSchema getParameters() {
        return JsonObjectSchema.builder()
                .addProperty("name", JsonStringSchema.builder()
                        .description("技能标识 (逐字取自「可用技能」清单)").build())
                .addProperty("path", JsonStringSchema.builder()
                        .description("技能内参考资源路径(可选); 省略则返回技能指令正文").build())
                .required(List.of("name"))
                .build();
    }

    @Override
    public String execute(Map<String, Object> params) {
        String name = getString(params, "name");
        String path = getString(params, "path");
        String userId = SkillContextHolder.get();
        try {
            String content = skillService.loadContent(userId, name == null ? "" : name.trim(), path);
            log.info("技能加载: name={}, path={}, user={}, 返回 {} 字符",
                    name, path, userId, content == null ? 0 : content.length());
            return content;
        } catch (Exception e) {
            // 加载失败不该中断对话: 返回可读提示, 让模型自行降级(通常改用自身知识作答)
            log.warn("技能加载失败: name={}, err={}", name, e.getMessage());
            return "技能加载失败: " + e.getMessage() + "。请改用其它方式完成当前任务, 不要重复重试。";
        }
    }

    @Override
    public ToolInfo executeWithInfo(Map<String, Object> params) {
        return ToolInfo.text(execute(params));
    }

    @Override
    public boolean supportsStreaming() {
        // 必须暴露给流式对话(chat 路径) —— 技能加载只发生在 Agentic 的流式链路里
        return true;
    }
}
