package com.dcdr.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 检索健康度 DTO —— RAG 检索质量的可观测性视图。
 *
 * <p><b>为什么需要它</b>: 固定评测集({@code RagRetrievalEvalIT} / {@code RagRetrievalEvalLiveIT})
 * 用的是冻结/人工标注的语料, 回答的是"代码改动是否让效果变差";
 * 而"线上文档持续增长后效果有没有漂移"只能看<b>真实流量</b>, 即本视图的数据来源。
 * 二者互补, 缺一不可。
 *
 * <p>数据来自 {@code llm_call_logs.steps_json} 中 {@code kind=rag} 的步骤
 * (由 {@code LlmCallTrace.recordRag} 写入: query / kbCount / hits / costMs / topScore / docIds)。
 */
public final class RetrievalHealthDtos {

    private RetrievalHealthDtos() {
    }

    /** 检索健康度总览 */
    @Data
    @Schema(description = "检索健康度总览")
    public static class HealthOverview {

        @Schema(description = "统计窗口起点 (含)")
        private LocalDateTime from;
        @Schema(description = "统计窗口终点 (不含)")
        private LocalDateTime to;
        @Schema(description = "统计窗口天数")
        private int days;

        @Schema(description = "扫描的调用链条数")
        private long scannedLogs;
        @Schema(description = "是否因扫描上限被截断 —— true 时指标只覆盖了最近的部分日志")
        private boolean truncated;
        @Schema(description = "扫描上限(超出即截断, 避免大数据量拖垮接口)")
        private int scanLimit;

        @Schema(description = "检索次数 (kind=rag 的步骤总数)")
        private long ragCalls;
        @Schema(description = "平均命中分块数")
        private double avgHits;
        @Schema(description = "零命中占比 (0~1) —— 最值得警惕的指标, 突增意味着知识盲区扩大或检索退化")
        private double zeroHitRate;
        @Schema(description = "平均最高相似度 (无检索时为 null) —— 整体下滑说明语料与问题的匹配度变差")
        private Double avgTopScore;
        @Schema(description = "检索耗时 P95 (毫秒)")
        private long p95CostMs;

        @Schema(description = "按天趋势")
        private List<DailyPoint> trend;
        @Schema(description = "按知识库数量维度拆分(定位是哪个检索范围效果差)")
        private List<KbBucket> byKbCount;
        @Schema(description = "零命中问题样本(用于判断是知识盲区还是检索退化)")
        private List<ZeroHitSample> zeroHits;

        @Schema(description = "上一等长周期的对比值(用于判断趋势方向, 无数据时为 null)")
        private PrevPeriod prev;
    }

    /** 按天趋势点 */
    @Data
    @Schema(description = "按天趋势点")
    public static class DailyPoint {
        @Schema(description = "日期 yyyy-MM-dd")
        private String date;
        @Schema(description = "当日检索次数")
        private long ragCalls;
        @Schema(description = "当日平均命中分块数")
        private double avgHits;
        @Schema(description = "当日零命中次数")
        private long zeroHitCalls;
        @Schema(description = "当日零命中占比 (0~1)")
        private double zeroHitRate;
        @Schema(description = "当日平均最高相似度")
        private Double avgTopScore;
    }

    /** 按知识库数量维度拆分 */
    @Data
    @Schema(description = "按知识库数量维度拆分的检索效果")
    public static class KbBucket {
        @Schema(description = "本次检索覆盖的知识库数量")
        private int kbCount;
        @Schema(description = "检索次数")
        private long ragCalls;
        @Schema(description = "零命中占比 (0~1)")
        private double zeroHitRate;
        @Schema(description = "平均最高相似度")
        private Double avgTopScore;
    }

    /** 零命中问题样本 */
    @Data
    @Schema(description = "零命中问题样本(可据此判断应补文档还是排查检索)")
    public static class ZeroHitSample {
        @Schema(description = "所属调用链日志 ID (可在「调用链」页面查完整上下文)")
        private String logId;
        @Schema(description = "时间")
        private LocalDateTime createdAt;
        @Schema(description = "用户 ID")
        private String userId;
        @Schema(description = "检索用的问题")
        private String question;
        @Schema(description = "检索覆盖的知识库数量")
        private int kbCount;
        @Schema(description = "最高相似度 (零命中时通常低于 minScore 阈值)")
        private Double topScore;
        @Schema(description = "检索耗时(毫秒)")
        private long costMs;
    }

    /** 上一等长周期的对比值 */
    @Data
    @Schema(description = "上一等长周期对比")
    public static class PrevPeriod {
        @Schema(description = "上周期检索次数")
        private long ragCalls;
        @Schema(description = "上周期零命中占比 (0~1)")
        private double zeroHitRate;
        @Schema(description = "上周期平均最高相似度")
        private Double avgTopScore;
    }
}
