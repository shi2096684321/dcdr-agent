package com.dcdr.agent.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * V3.0 工作流执行记录实体 (F-33 执行容错, 文档 §7.3)。
 * <p>
 * 持久化每次工作流执行的状态, 记录每步执行结果 (steps_state_json),
 * 支持失败重试与断点续跑 (resume / approve / cancel)。
 * <p>
 * 状态机: running → (paused | success | failed | cancelled)。
 * <ul>
 *   <li>{@code running}: 执行中</li>
 *   <li>{@code paused}:  暂停 (人工审批节点), 可 approve 继续</li>
 *   <li>{@code failed}:  失败 (重试耗尽), 可 resume 从断点续跑</li>
 *   <li>{@code success}: 成功完成</li>
 *   <li>{@code cancelled}: 已取消</li>
 * </ul>
 */
@Data
@Entity
@EntityListeners(OwnershipFillListener.class)
@Schema(description = "V3.0 工作流执行记录 (F-33)")
@Table(name = "workflow_executions", indexes = {
        @Index(name = "idx_wfexec_workflow", columnList = "workflowId"),
        @Index(name = "idx_wfexec_user", columnList = "userId"),
        @Index(name = "idx_wfexec_status", columnList = "status")
})
public class WorkflowExecutionEntity implements OwnedEntity {

    @Schema(description = "主键 ID")
    @Id
    @Column(length = 64)
    private String id;

    @Schema(description = "工作流 ID")
    @Column(length = 64, nullable = false)
    private String workflowId;

    @Schema(description = "所属用户 ID")
    @Column(length = 64)
    private String userId;

    @Schema(description = "触发方式: manual / api / webhook / event / schedule / chat")
    @Column(nullable = false)
    private String triggerType;

    @Schema(description = "执行输入 (替换 ${user_message})")
    @Column(columnDefinition = "TEXT")
    private String input;

    @Schema(description = "执行状态: running / paused / success / failed / cancelled")
    @Column(nullable = false)
    private String status;

    @Schema(description = "当前步骤名 (断点续跑用)")
    @Column(length = 64)
    private String currentStep;

    @Schema(description = "各步状态 + 输出 (JSON)")
    @Column(name = "steps_state_json", columnDefinition = "TEXT")
    private String stepsStateJson;

    @Schema(description = "开始时间")
    @Column(nullable = false)
    private LocalDateTime startedAt;

    @Schema(description = "完成时间")
    private LocalDateTime completedAt;

    @Schema(description = "错误信息")
    @Column(columnDefinition = "TEXT")
    private String error;

    @Schema(description = "Token 消耗")
    private Integer costTokens;

    @Schema(description = "成本金额")
    @Column(precision = 10, scale = 4)
    private BigDecimal costAmount;
}
