package com.dcdr.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * V3.1 回答反馈 DTO (F-48 点赞/点踩收集)。
 */
public class FeedbackDtos {

    private FeedbackDtos() {}

    /** 点踩原因标签 (code + 展示名), 由后端下发以保证前后端口径一致 */
    @Data
    @Schema(description = "点踩原因标签")
    public static class ReasonTag {
        private String code;
        private String label;

        public ReasonTag() {}

        public ReasonTag(String code, String label) {
            this.code = code;
            this.label = label;
        }
    }

    /** 提交/更新反馈请求 */
    @Data
    @Schema(description = "回答反馈请求")
    public static class FeedbackRequest {
        @Schema(description = "会话 ID", requiredMode = Schema.RequiredMode.REQUIRED)
        private String sessionId;
        @Schema(description = "被评价回答的会话内序号 (MessageDTO.seq)", requiredMode = Schema.RequiredMode.REQUIRED)
        private Integer messageSeq;
        @Schema(description = "评价: up=点赞 / down=点踩", requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = {"up", "down"})
        private String rating;
        @Schema(description = "点踩原因标签 code 列表 (可选, 仅 rating=down 时有意义)")
        private List<String> reasonTags;
        @Schema(description = "补充说明文字 (可选)")
        private String comment;
    }

    /** 单条反馈明细 */
    @Data
    @Schema(description = "回答反馈明细")
    public static class FeedbackView {
        private Long id;
        private String sessionId;
        private Integer messageSeq;
        private String userId;
        /** up / down */
        private String rating;
        /** 原因标签 code 列表 */
        private List<String> reasonTags;
        private String comment;
        private String model;
        private String toolsUsed;
        private int sourceCount;
        private boolean webSearch;
        private int messageChars;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    /** 原因标签分布 */
    @Data
    @Schema(description = "点踩原因分布")
    public static class ReasonCount {
        private String code;
        private String label;
        private long count;
    }

    /** 按模型聚合 */
    @Data
    @Schema(description = "按模型的反馈统计")
    public static class ModelStat {
        private String model;
        private long total;
        private long up;
        private long down;
        /** 好评率 = up / total (0~1) */
        private double satisfaction;
    }

    /** 按天聚合 */
    @Data
    @Schema(description = "按天的反馈统计")
    public static class DayStat {
        /** yyyy-MM-dd */
        private String date;
        private long total;
        private long up;
        private long down;
    }

    /** 反馈概览 (收集阶段的核心消费口: 看趋势 + 定位差评集中在哪个模型/原因) */
    @Data
    @Schema(description = "回答反馈概览")
    public static class StatsView {
        private long total;
        private long up;
        private long down;
        /** 好评率 = up / total (0~1), 无数据时为 0 */
        private double satisfaction;
        /** 统计窗口天数 */
        private int days;
        /** 全局 or 仅本人 */
        private boolean global;
        private List<ReasonCount> reasonCounts;
        private List<ModelStat> modelStats;
        private List<DayStat> dayStats;
    }

    /** 反馈明细分页 */
    @Data
    @Schema(description = "反馈明细分页")
    public static class PageView {
        private List<FeedbackView> content;
        private int page;
        private int size;
        private long totalElements;
        private int totalPages;
    }
}
