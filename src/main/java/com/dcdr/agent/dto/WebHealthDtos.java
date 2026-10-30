package com.dcdr.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 联网健康度 DTO —— 联网搜索与网页抓取的执行质量视图。
 *
 * <p><b>为什么需要它</b>: 固定评测（{@code WebExtractFixtureTest} 的存档页面、
 * {@code WebSearchEvalIT} 的金标查询）回答的是"<b>代码改动</b>有没有让抽取/召回变差";
 * 而"线上真实流量下效果是否在漂移"只能看调用链日志, 即本视图。
 * 二者互补: 评测集用的是冻结语料, 本视图看的是活的数据。
 *
 * <p><b>与检索健康度（{@link RetrievalHealthDtos}）的关键差别</b>:
 * 检索退化的第一表现是"命中了但不太相关"（看相似度与零命中率）;
 * 联网退化的第一表现是"<b>一条都没拿到</b>", 而它在对话界面里完全看不出来 ——
 * 模型会拿空上下文继续作答。故本视图的第一指标是 {@code emptyRate}。
 *
 * <p>数据来自 {@code llm_call_logs.steps_json} 中 {@code kind=web} 的步骤
 * （由 {@code LlmCallTrace.recordWeb} 写入, 字段定义见 {@code WebStat}）。
 */
public final class WebHealthDtos {

    private WebHealthDtos() {
    }

    /** 联网健康度总览 */
    @Data
    @Schema(description = "联网健康度总览")
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

        @Schema(description = "联网调用次数 (kind=web 的步骤总数)")
        private long webCalls;
        @Schema(description = "其中搜索调用次数")
        private long searchCalls;
        @Schema(description = "其中抓取调用次数")
        private long extractCalls;

        @Schema(description = "空结果占比 (0~1) —— 最该盯的指标, 突增意味着模型开始在没有资料的情况下作答")
        private double emptyRate;
        @Schema(description = "清洗存活率 (保留条数 ÷ 上游原始条数, 0~1) —— 下滑说明清洗规则相对当前结果形态过严; 无搜索调用时为 null")
        private Double avgKeepRatio;
        @Schema(description = "抓取成功率 (0~1) —— 下滑通常是目标站点开始拦截或改版; 未触发抓取时为 null")
        private Double extractSuccessRate;
        @Schema(description = "薄页占比 (0~1) —— 抓到了但正文极短(登录页/验证码页), 抓取成功率会因此虚高")
        private Double thinRate;
        @Schema(description = "平均正文长度(字符), 仅统计真正抓到正文的调用")
        private Long avgContentChars;
        @Schema(description = "联网耗时 P95 (毫秒), 含串联抓取")
        private long p95CostMs;

        @Schema(description = "按天趋势")
        private List<DailyPoint> trend;
        @Schema(description = "空结果成因分布 —— 区分'上游没给'与'自己过滤太狠'")
        private List<ReasonBucket> byReason;
        @Schema(description = "清洗存活率异常样本(上游给了不少、几乎全被丢弃)")
        private List<WeakSample> weakSamples;
        @Schema(description = "薄页样本(抓取成功但正文极短)")
        private List<ThinSample> thinSamples;

        @Schema(description = "上一等长周期的对比值(用于判断趋势方向, 无数据时为 null)")
        private PrevPeriod prev;
    }

    /** 按天趋势点 */
    @Data
    @Schema(description = "按天趋势点")
    public static class DailyPoint {
        @Schema(description = "日期 yyyy-MM-dd")
        private String date;
        @Schema(description = "当日联网调用次数")
        private long webCalls;
        @Schema(description = "当日空结果次数")
        private long emptyCalls;
        @Schema(description = "当日空结果占比 (0~1)")
        private double emptyRate;
        @Schema(description = "当日清洗存活率")
        private Double avgKeepRatio;
        @Schema(description = "当日抓取成功率")
        private Double extractSuccessRate;
    }

    /** 空结果成因分组 */
    @Data
    @Schema(description = "空结果成因分布: 上游无返回 / 结果全被过滤 / 未启用 / 请求失败")
    public static class ReasonBucket {
        @Schema(description = "成因取值 (no_results / all_filtered / disabled / error)")
        private String reason;
        @Schema(description = "成因中文名(由后端下发, 前端不维护映射表)")
        private String label;
        @Schema(description = "该成因的调用次数")
        private long calls;
        @Schema(description = "占全部联网调用的比例 (0~1)")
        private double share;
        @Schema(description = "该成因下的平均上游条数(all_filtered 时通常很大)")
        private Long avgRawCount;
    }

    /** 清洗存活率异常样本 */
    @Data
    @Schema(description = "清洗存活率异常样本: 上游给了 >=10 条, 保留 <=2 条")
    public static class WeakSample {
        @Schema(description = "所属调用链日志 ID")
        private String logId;
        @Schema(description = "时间")
        private LocalDateTime createdAt;
        @Schema(description = "用户 ID")
        private String userId;
        @Schema(description = "搜索词")
        private String query;
        @Schema(description = "上游原始条数")
        private int rawCount;
        @Schema(description = "清洗后保留条数")
        private int results;
        @Schema(description = "存活率 (保留 ÷ 原始)")
        private double keepRatio;
    }

    /** 薄页样本 */
    @Data
    @Schema(description = "薄页样本: 抓取显示成功, 但正文极短(登录页/验证码页/空壳页)")
    public static class ThinSample {
        @Schema(description = "所属调用链日志 ID")
        private String logId;
        @Schema(description = "时间")
        private LocalDateTime createdAt;
        @Schema(description = "搜索词或抓取地址")
        private String query;
        @Schema(description = "抓取成功篇数")
        private int extractOk;
        @Schema(description = "抓取失败篇数")
        private int extractFailed;
        @Schema(description = "正文总字符数")
        private int contentChars;
        @Schema(description = "其中正文过短的篇数")
        private int thinPages;
    }

    /** 上一等长周期的对比值 */
    @Data
    @Schema(description = "上一等长周期对比")
    public static class PrevPeriod {
        @Schema(description = "上周期联网调用次数")
        private long webCalls;
        @Schema(description = "上周期空结果占比")
        private double emptyRate;
        @Schema(description = "上周期清洗存活率")
        private Double avgKeepRatio;
        @Schema(description = "上周期抓取成功率")
        private Double extractSuccessRate;
    }
}
