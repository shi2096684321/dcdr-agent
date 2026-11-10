package com.dcdr.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 上下文压缩事件的查询视图(V3.5)。
 *
 * <p>与 {@code LlmCallLogDtos} 同一套分页结构(前端两个页面共用分页组件的取数方式),
 * 但字段是压缩特有的: 触发阈值、前后 token、被压/保留条数、摘要字数、边界 seq、失败原因。
 */
public class ContextCompactionDtos {

    @Data
    @Schema(description = "一条压缩事件")
    public static class View {

        @Schema(description = "主键 ID")
        private String id;

        @Schema(description = "会话 ID; 会话删除后为 null")
        private String sessionId;

        @Schema(description = "用户 ID")
        private String userId;

        @Schema(description = "事件类型 code: compacted / degraded / invalidated / cleared / edited")
        private String eventType;

        /** 中文名由后端给出: 前端不再维护一份映射, 否则加一种事件必然漏一边 */
        @Schema(description = "事件类型中文名")
        private String eventLabel;

        @Schema(description = "事件说明(降级/作废原因等)")
        private String reason;

        @Schema(description = "本轮触发阈值(token)")
        private Integer triggerTokens;

        @Schema(description = "压缩前估算 token")
        private Integer beforeTokens;

        @Schema(description = "压缩后估算 token")
        private Integer afterTokens;

        @Schema(description = "被压进摘要的消息条数")
        private Integer summarizedCount;

        @Schema(description = "保留原文的消息条数")
        private Integer keptCount;

        @Schema(description = "摘要字数")
        private Integer summaryChars;

        @Schema(description = "摘要边界 seq")
        private Integer boundarySeq;

        @Schema(description = "摘要使用的 provider 名")
        private String model;

        @Schema(description = "摘要调用耗时(毫秒)")
        private Long durationMs;

        @Schema(description = "发生时间")
        private LocalDateTime createdAt;
    }

    @Data
    @Schema(description = "压缩事件分页结果")
    public static class PageView {
        private List<View> content;
        private int page;
        private int size;
        private long totalElements;
        private int totalPages;
    }
}
