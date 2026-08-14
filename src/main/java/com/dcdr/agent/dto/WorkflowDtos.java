package com.dcdr.agent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * V2.3 工作流相关 DTO (F-25 多 Agent 协作编排)。
 * <p>
 * V3.0-alpha 扩展 (F-33 执行容错):
 * <ul>
 *   <li>{@link WorkflowStep} 新增 {@code retry} (重试策略) / {@code approval} (人工审批节点)</li>
 *   <li>{@link ExecutionResult} 新增 {@code executionId} / {@code triggerType}</li>
 *   <li>新增 {@link ExecutionView} 等执行记录相关 DTO (断点续跑 / 审批)</li>
 * </ul>
 */
public class WorkflowDtos {

    private WorkflowDtos() {}

    /** 步骤重试策略 (F-33, 文档 §7.2) */
    @Data
    @Schema(description = "步骤重试策略")
    public static class RetryConfig {
        @Schema(description = "最大尝试次数 (含首次, 1=不重试)", defaultValue = "3")
        private Integer maxAttempts;
        @Schema(description = "退避策略: fixed / linear / exponential", defaultValue = "exponential")
        private String backoff;
        @Schema(description = "初始退避延迟 ms", defaultValue = "1000")
        private Long initialDelayMs;
        @Schema(description = "退避乘数 (exponential/linear)", defaultValue = "2.0")
        private Double multiplier;
    }

    /** 工作流单步定义 (存入 steps_json 数组中的一项) */
    @Data
    @Schema(description = "工作流单步定义")
    public static class WorkflowStep {
        @Schema(description = "步骤名称 (在工作流内唯一, 用作变量名引用前序步骤输出)")
        private String name;
        @Schema(description = "本步调用的 Agent ID (approval=true 时可留空, 仅作审批门禁)")
        private String agentId;
        @Schema(description = "输入模板, 支持 ${user_message} / ${prev.name} 等变量占位")
        private String input;
        @Schema(description = "输出变量名 (供后续步骤引用)")
        private String output;
        @Schema(description = "依赖的前序步骤名列表 (仅 conditional 模式分支用)")
        private List<String> dependsOn;
        @Schema(description = "条件分支表达式 (仅 conditional 模式; 形如 'contains(${prev.out},\"YES\")')")
        private String condition;
        @Schema(description = "重试策略 (V3.0 F-33; 留空则用 agent.v3.execution.retry 默认策略)")
        private RetryConfig retry;
        @Schema(description = "是否为人工审批节点 (V3.0 F-33; 执行到该步后暂停, 等待 approve 后继续)")
        @JsonProperty("approval")
        private Boolean approval;
    }

    /** 创建/更新工作流请求 */
    @Data
    @Schema(description = "创建/更新工作流请求")
    public static class SaveWorkflowRequest {
        @Schema(description = "工作流名称", requiredMode = Schema.RequiredMode.REQUIRED)
        private String name;
        @Schema(description = "描述")
        private String description;
        @Schema(description = "编排模式: sequential / parallel / conditional", defaultValue = "sequential")
        private String mode;
        @Schema(description = "步骤定义数组")
        private List<WorkflowStep> steps;
        @Schema(description = "是否启用")
        private Boolean enabled;
        @Schema(description = "是否为预设模板 (仅 admin 可设)", defaultValue = "false")
        @JsonProperty("isTemplate")
        private Boolean isTemplate;
    }

    /** 工作流视图 */
    @Data
    @Schema(description = "工作流视图")
    public static class WorkflowView {
        private String id;
        private String userId;
        private String name;
        private String description;
        private String mode;
        private List<WorkflowStep> steps;
        private boolean enabled;
        @JsonProperty("isTemplate")
        private boolean template;
        /** 是否可被当前用户编辑 (创建者本人 且 非模板) */
        private boolean editable;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    /** 执行工作流请求 */
    @Data
    @Schema(description = "执行工作流请求")
    public static class ExecuteWorkflowRequest {
        @Schema(description = "工作流 ID (与 definition 二选一)")
        private String workflowId;
        @Schema(description = "用户输入 (替换 ${user_message})")
        private String userInput;
        @Schema(description = "触发方式 (manual / api / webhook / chat); 留空默认 manual", hidden = true)
        private String triggerType;
    }

    /** 工作流单步执行结果 (用于前端可视化展示执行过程) */
    @Data
    @Schema(description = "工作流单步执行结果")
    public static class StepResult {
        @Schema(description = "步骤名")
        private String name;
        @Schema(description = "调用的 Agent ID")
        private String agentId;
        @Schema(description = "Agent 名称")
        private String agentName;
        @Schema(description = "本步输入文本 (变量已替换)")
        private String input;
        @Schema(description = "本步输出文本")
        private String output;
        @Schema(description = "状态: success / failed / skipped / paused / running")
        private String status;
        @Schema(description = "失败原因")
        private String error;
        @Schema(description = "耗时 ms")
        private long durationMs;
        @Schema(description = "实际尝试次数 (V3.0 F-33)")
        private int attempts;
    }

    /** 工作流执行结果 */
    @Data
    @Schema(description = "工作流执行结果")
    public static class ExecutionResult {
        @Schema(description = "执行记录 ID (V3.0 F-33, 用于断点续跑/审批)")
        private String executionId;
        @Schema(description = "工作流 ID")
        private String workflowId;
        @Schema(description = "工作流名称")
        private String workflowName;
        @Schema(description = "编排模式")
        private String mode;
        @Schema(description = "触发方式: manual / api / webhook / chat")
        private String triggerType;
        @Schema(description = "最终输出 (sequential/conditional: 最后一步输出; parallel: 各步输出 JSON)")
        private String finalOutput;
        @Schema(description = "各步执行详情 (供前端可视化)")
        private List<StepResult> steps;
        @Schema(description = "总耗时 ms")
        private long totalDurationMs;
        @Schema(description = "整体状态: success / failed / partial / paused / cancelled")
        private String status;
        @Schema(description = "错误信息 (失败时)")
        private String error;
    }

    // ===== V3.0 F-33 执行记录视图 (断点续跑 / 审批) =====

    /** 执行记录视图 */
    @Data
    @Schema(description = "工作流执行记录视图")
    public static class ExecutionView {
        private String id;
        private String workflowId;
        private String workflowName;
        private String userId;
        private String triggerType;
        private String input;
        private String status;
        private String currentStep;
            private List<StepResult> steps;
        private LocalDateTime startedAt;
        private LocalDateTime completedAt;
        private String error;
        private Integer costTokens;
        private String costAmount;
        /** 当前执行是否可被该用户续跑/审批/取消 */
        private boolean resumable;
    }

    /** 续跑/审批请求 */
    @Data
    @Schema(description = "断点续跑请求")
    public static class ResumeRequest {
        @Schema(description = "审批意见 (approve 时可选; reject 时填写拒绝理由)")
        private String comment;
        @Schema(description = "覆盖原始输入 (可选, 续跑时重新替换 ${user_message})")
        private String overrideInput;
    }
}
