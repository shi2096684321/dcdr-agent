package com.dcdr.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * V2.3 工作流相关 DTO (F-25 多 Agent 协作编排)。
 */
public class WorkflowDtos {

    private WorkflowDtos() {}

    /** 工作流单步定义 (存入 steps_json 数组中的一项) */
    @Data
    @Schema(description = "工作流单步定义")
    public static class WorkflowStep {
        @Schema(description = "步骤名称 (在工作流内唯一, 用作变量名引用前序步骤输出)")
        private String name;
        @Schema(description = "本步调用的 Agent ID")
        private String agentId;
        @Schema(description = "输入模板, 支持 ${user_message} / ${prev.name} 等变量占位")
        private String input;
        @Schema(description = "输出变量名 (供后续步骤引用)")
        private String output;
        @Schema(description = "依赖的前序步骤名列表 (仅 conditional 模式分支用)")
        private List<String> dependsOn;
        @Schema(description = "条件分支表达式 (仅 conditional 模式; 形如 'contains(${prev.out},\"YES\")')")
        private String condition;
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
        @Schema(description = "状态: success / failed / skipped")
        private String status;
        @Schema(description = "失败原因")
        private String error;
        @Schema(description = "耗时 ms")
        private long durationMs;
    }

    /** 工作流执行结果 */
    @Data
    @Schema(description = "工作流执行结果")
    public static class ExecutionResult {
        @Schema(description = "工作流 ID")
        private String workflowId;
        @Schema(description = "工作流名称")
        private String workflowName;
        @Schema(description = "编排模式")
        private String mode;
        @Schema(description = "最终输出 (sequential/conditional: 最后一步输出; parallel: 各步输出 JSON)")
        private String finalOutput;
        @Schema(description = "各步执行详情 (供前端可视化)")
        private List<StepResult> steps;
        @Schema(description = "总耗时 ms")
        private long totalDurationMs;
        @Schema(description = "整体状态: success / failed / partial")
        private String status;
    }
}
