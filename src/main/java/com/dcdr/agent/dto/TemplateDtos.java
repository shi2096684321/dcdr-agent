package com.dcdr.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * V3.0-beta 模板市场 DTO (F-35 Agent 与工作流模板市场, 文档 §9)。
 */
public class TemplateDtos {

    private TemplateDtos() {}

    /** 市场模板视图 */
    @Data
    @Schema(description = "市场模板视图")
    public static class TemplateShareView {
        private String id;
        /** 类型: agent / workflow */
        private String type;
        private String targetId;
        private String title;
        private String description;
        private String category;
        /** 标签列表 (逗号拆分后) */
        private List<String> tags;
        private String ownerId;
        private int cloneCount;
        private double ratingAvg;
        private int ratingCount;
        private LocalDateTime createdAt;
    }

    /** 分享请求 */
    @Data
    @Schema(description = "分享 Agent/工作流到模板市场请求")
    public static class ShareRequest {
        @Schema(description = "类型: agent / workflow", requiredMode = Schema.RequiredMode.REQUIRED)
        private String type;
        @Schema(description = "目标 ID (Agent ID 或工作流 ID)", requiredMode = Schema.RequiredMode.REQUIRED)
        private String targetId;
        @Schema(description = "市场展示标题 (留空用目标名称)")
        private String title;
        @Schema(description = "市场展示描述 (留空用目标描述)")
        private String description;
        @Schema(description = "分类, 如 翻译/写作/编程/分析/客服")
        private String category;
        @Schema(description = "标签 (逗号分隔)")
        private String tags;
    }

    /** 克隆结果 */
    @Data
    @Schema(description = "克隆模板结果")
    public static class CloneResult {
        @Schema(description = "新副本类型: agent / workflow")
        private String type;
        @Schema(description = "新副本 ID")
        private String newId;
        @Schema(description = "新副本名称")
        private String newName;
    }

    /** 评分请求 */
    @Data
    @Schema(description = "模板评分请求")
    public static class RateRequest {
        @Schema(description = "评分 1~5", requiredMode = Schema.RequiredMode.REQUIRED)
        private Integer rating;
    }

    /** 分页视图 */
    @Data
    @Schema(description = "模板市场分页结果")
    public static class TemplatePageView {
        private List<TemplateShareView> content;
        private int page;
        private int size;
        private long totalElements;
        private int totalPages;
    }
}
