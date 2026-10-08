package com.dcdr.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * V3.0-beta 治理与可观测性 DTO (F-34 执行监控 / 成本统计, 文档 §8)。
 */
public class MonitorDtos {

    private MonitorDtos() {}

    /** 工作流执行监控总览 */
    @Data
    @Schema(description = "工作流执行监控总览 (F-34)")
    public static class WorkflowMonitorOverview {
        @Schema(description = "时间窗口内的总执行次数")
        private long totalExecutions;
        @Schema(description = "成功次数")
        private long successCount;
        @Schema(description = "失败次数")
        private long failedCount;
        @Schema(description = "暂停(待审批)次数")
        private long pausedCount;
        @Schema(description = "运行中次数")
        private long runningCount;
        @Schema(description = "成功率 (0~1, 无执行时为 null)")
        private Double successRate;
        @Schema(description = "平均耗时 ms")
        private long avgDurationMs;
        @Schema(description = "总 token 消耗")
        private long totalTokens;
        @Schema(description = "按工作流聚合的执行统计")
        private List<WorkflowStat> byWorkflow;
    }

    /** 单个工作流统计 */
    @Data
    @Schema(description = "按工作流聚合的执行统计")
    public static class WorkflowStat {
        private String workflowId;
        private String workflowName;
        private long executions;
        private long successCount;
        private long failedCount;
        @Schema(description = "成功率 (0~1)")
        private Double successRate;
        private long avgDurationMs;
        private long totalTokens;
        private LocalDateTime lastExecutedAt;
    }

    /** 成本统计总览 */
    @Data
    @Schema(description = "成本统计总览 (F-34)")
    public static class CostOverview {
        @Schema(description = "时间窗口内的总 token")
        private long totalTokens;
        @Schema(description = "输入 token 合计")
        private long inputTokens;
        @Schema(description = "输出 token 合计")
        private long outputTokens;
        @Schema(description = "调用次数")
        private long callCount;
        @Schema(description = "按天聚合的用量")
        private List<DailyUsage> daily;
        @Schema(description = "按模型聚合的用量")
        private List<ModelUsage> byModel;
    }

    /** 按天用量 */
    @Data
    @Schema(description = "按天 token 用量")
    public static class DailyUsage {
        @Schema(description = "日期 (yyyy-MM-dd)")
        private String date;
        private long inputTokens;
        private long outputTokens;
        private long totalTokens;
    }

    /** 按模型用量 */
    @Data
    @Schema(description = "按模型 token 用量")
    public static class ModelUsage {
        private String model;
        private long calls;
        private long inputTokens;
        private long outputTokens;
        private long totalTokens;
    }

    /** 用户配额使用情况 (F-39) */
    @Data
    @Schema(description = "用户配额使用情况 (F-39)")
    public static class QuotaUsage {
        private boolean quotaEnabled;
        @Schema(description = "今日已用 token")
        private long dailyUsed;
        @Schema(description = "今日限额 (0=不限)")
        private long dailyLimit;
        @Schema(description = "本月已用 token")
        private long monthlyUsed;
        @Schema(description = "本月限额 (0=不限)")
        private long monthlyLimit;
    }

    /** LLM 服务调用与熔断概览 (F-40.2 / F-40.4, 前端内置监控面板用) */
    @Data
    @Schema(description = "LLM 服务调用与熔断概览 (F-40.2 / F-40.4)")
    public static class LlmMonitorOverview {
        @Schema(description = "成功调用总数(进程内累计)")
        private long totalCalls;
        @Schema(description = "失败调用总数")
        private long totalErrors;
        @Schema(description = "总体失败率 (0~1)")
        private double overallErrorRate;
        @Schema(description = "各 provider 调用/失败/耗时统计")
        private List<Map<String, Object>> providers;
        @Schema(description = "熔断器快照 (provider/state/windowCalls/windowFailures)")
        private List<Map<String, Object>> circuitBreaker;
    }

    // ===== V3.1 F-44.2 成本分析报表 =====

    /** 成本报表总览 (按 用户 / 模型 / 时间 维度聚合) */
    @Data
    @Schema(description = "成本报表总览 (F-44.2)")
    public static class CostReportView {
        @Schema(description = "统计区间起 (yyyy-MM-dd, 含)")
        private String from;
        @Schema(description = "统计区间止 (yyyy-MM-dd, 含)")
        private String to;
        @Schema(description = "趋势粒度: day / week / month")
        private String granularity;
        private long totalTokens;
        private long inputTokens;
        private long outputTokens;
        private long callCount;
        @Schema(description = "金额合计 (元); 是否可用见 costDataAvailable")
        private BigDecimal totalCost;
        /**
         * 区间内是否存在带金额的记录。
         * <p>未配置模型定价时 {@code cost_amount} 全为 null, 聚合结果是 0 —— 直接展示会误导成
         * "零成本", 故用该标记让前端把金额列降级为 "—"。
         */
        @Schema(description = "是否存在金额数据 (false 时前端金额列显示 —)")
        private boolean costDataAvailable;
        @Schema(description = "按粒度聚合的用量趋势")
        private List<TrendPoint> trend;
        @Schema(description = "模型维度: 调用/用量/金额/占比")
        private List<ModelUsageCost> byModel;
        @Schema(description = "Top 用户 (仅管理员全局视图返回)")
        private List<UserUsageCost> byUser;
        @Schema(description = "调用来源维度: chat / workflow / routing")
        private List<SourceUsage> bySource;
    }

    /** 趋势数据点 */
    @Data
    @Schema(description = "成本趋势数据点")
    public static class TrendPoint {
        @Schema(description = "粒度标识: 2026-09-28 / 2026-W39 / 2026-09")
        private String period;
        private long calls;
        private long inputTokens;
        private long outputTokens;
        private long totalTokens;
        private BigDecimal cost;
    }

    /** 模型维度行 */
    @Data
    @Schema(description = "模型维度成本")
    public static class ModelUsageCost {
        private String model;
        private long calls;
        private long inputTokens;
        private long outputTokens;
        private long totalTokens;
        private BigDecimal cost;
        @Schema(description = "Token 占比 (0~1)")
        private double tokenRatio;
        @Schema(description = "金额占比 (0~1); 无金额数据时为 null")
        private Double costRatio;
    }

    /** 用户维度行 (Top N) */
    @Data
    @Schema(description = "用户维度成本 (Top N)")
    public static class UserUsageCost {
        private String userId;
        @Schema(description = "用户名 (回填; 已删除用户为占位文案)")
        private String username;
        private long calls;
        private long totalTokens;
        private BigDecimal cost;
    }

    /** 来源维度行 */
    @Data
    @Schema(description = "调用来源维度成本")
    public static class SourceUsage {
        @Schema(description = "chat / workflow / routing")
        private String source;
        private long calls;
        private long totalTokens;
        private BigDecimal cost;
    }
}
