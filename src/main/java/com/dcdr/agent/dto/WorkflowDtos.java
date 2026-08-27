package com.dcdr.agent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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
        @Schema(description = "编排模式: sequential / parallel / conditional / dag", defaultValue = "sequential")
        private String mode;
        @Schema(description = "步骤定义数组")
        private List<WorkflowStep> steps;
        @Schema(description = "V3.0-beta F-32 DAG 定义 (nodes/edges); 非空时优先按 DAG 执行")
        private DagSpec dag;
        @Schema(description = "变更说明 (V3.0-beta F-34, 更新时存入版本历史)")
        private String changeNote;
        @Schema(description = "是否启用")
        private Boolean enabled;
        @Schema(description = "是否为预设模板 (仅 admin 可设)", defaultValue = "false")
        @JsonProperty("isTemplate")
        private Boolean isTemplate;
    }

    // ===== V3.0-beta F-32 可视化 DAG 定义 =====

    /** DAG 图定义 (存入 dag_json) */
    @Data
    @Schema(description = "DAG 图定义 (F-32)")
    public static class DagSpec {
        @Schema(description = "节点列表")
        private List<DagNode> nodes;
        @Schema(description = "边列表")
        private List<DagEdge> edges;
    }

    /** DAG 节点 */
    @Data
    @Schema(description = "DAG 节点 (F-32)")
    public static class DagNode {
        @Schema(description = "节点 ID (如 n1)")
        private String id;
        @Schema(description = "节点类型: agent / condition / approval / merge")
        private String type;
        @Schema(description = "画布坐标 (仅前端布局用)")
        private Map<String, Integer> position;
        @Schema(description = "节点配置")
        private DagNodeData data;
    }

    /** DAG 节点配置 */
    @Data
    @Schema(description = "DAG 节点配置 (F-32)")
    public static class DagNodeData {
        @Schema(description = "节点显示名 (也用作步骤名与变量名)")
        private String label;
        @Schema(description = "agent 节点: 调用的 Agent ID")
        private String agentId;
        @Schema(description = "agent 节点: 输入模板, 支持 ${user_message} / ${n1.output} 变量")
        private String input;
        @Schema(description = "agent 节点: 重试策略 (可选)")
        private RetryConfig retry;
        @Schema(description = "condition 节点: 条件描述 (自然语言) 或表达式")
        private String condition;
        @Schema(description = "condition 节点判断模式: llm (自然语言, 默认) / expr (表达式)")
        private String conditionMode;
    }

    /** DAG 边 */
    @Data
    @Schema(description = "DAG 边 (F-32)")
    public static class DagEdge {
        @Schema(description = "源节点 ID")
        private String source;
        @Schema(description = "目标节点 ID")
        private String target;
        @Schema(description = "边标签; condition 节点出边用 是/否 (true/false) 标识分支")
        private String label;
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
        @Schema(description = "V3.0-beta F-32 DAG 定义 (无则为 null)")
        private DagSpec dag;
        private boolean enabled;
        @JsonProperty("isTemplate")
        private boolean template;
        /** 是否可被当前用户编辑 (创建者本人 且 非模板) */
        private boolean editable;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    // ===== V3.0-beta F-34 版本管理视图 =====

    /** 工作流版本视图 */
    @Data
    @Schema(description = "工作流版本历史视图 (F-34)")
    public static class VersionView {
        private String id;
        private String workflowId;
        private int version;
        private String name;
        private String description;
        private String mode;
        private String changeNote;
        private String createdBy;
        private LocalDateTime createdAt;
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
        @Schema(description = "本步 token 消耗 (V3.0-beta F-34 成本追踪)")
        private Integer tokens;
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

    /** V3.0 执行记录分页视图 (执行历史搜索/筛选/分页) */
    @Data
    @Schema(description = "工作流执行记录分页结果")
    public static class ExecutionPageView {
        @Schema(description = "当前页数据")
        private List<ExecutionView> content;
        @Schema(description = "页码 (从 0 开始)", example = "0")
        private int page;
        @Schema(description = "每页条数", example = "10")
        private int size;
        @Schema(description = "总条数", example = "42")
        private long totalElements;
        @Schema(description = "总页数", example = "5")
        private int totalPages;
    }
}
