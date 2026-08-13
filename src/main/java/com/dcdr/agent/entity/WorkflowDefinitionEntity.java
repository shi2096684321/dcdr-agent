package com.dcdr.agent.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * V2.3 工作流定义实体 (F-25 多 Agent 协作编排)。
 * <p>
 * 一个工作流由若干步骤组成, 每步引用一个 Agent 并定义输入/输出变量。
 * 支持三种编排模式:
 * <ul>
 *   <li>sequential: 串行, 按顺序依次执行</li>
 *   <li>parallel:   并行, 全部步骤并发执行后汇总</li>
 *   <li>conditional:条件, 由前一步输出经 LLM/规则判断后选择分支</li>
 * </ul>
 * 步骤定义存为 JSON 字符串(steps_json), 由 WorkflowEngine 解析执行。
 */
@Data
@Entity
@Schema(description = "V2.3 工作流定义实体 (F-25 多 Agent 协作编排)")
@Table(name = "workflow_definitions", indexes = {
        @Index(name = "idx_workflow_user", columnList = "userId")
})
public class WorkflowDefinitionEntity {

    @Schema(description = "主键 ID")
    @Id
    @Column(length = 64)
    private String id;

    @Schema(description = "所属用户 ID")
    @Column(length = 64)
    private String userId;

    @Schema(description = "工作流名称")
    @Column(nullable = false)
    private String name;

    @Schema(description = "工作流描述")
    @Column(columnDefinition = "TEXT")
    private String description;

    @Schema(description = "编排模式: sequential / parallel / conditional")
    @Column(nullable = false)
    private String mode = "sequential";

    @Schema(description = "步骤定义数组(JSON), 每步含 name/agentId/input/output/dependsOn/condition")
    @Column(name = "steps_json", columnDefinition = "TEXT", nullable = false)
    private String stepsJson;

    @Schema(description = "是否启用")
    @Column(nullable = false)
    private boolean enabled = true;

    @Schema(description = "是否为预设模板 (V2.3, 模板对所有用户可见且不可删除, 仅可克隆)")
    @Column(nullable = false)
    private boolean isTemplate = false;

    @Schema(description = "创建时间")
    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Schema(description = "最后更新时间")
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
