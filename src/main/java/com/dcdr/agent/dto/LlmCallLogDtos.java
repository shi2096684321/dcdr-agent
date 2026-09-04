package com.dcdr.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** LLM 调用链日志 DTO(一次请求一条, 供「调用链日志」独立页面查询) */
public final class LlmCallLogDtos {

    @Data
    @Schema(description = "调用链日志视图")
    public static class LlmCallLogView {
        @Schema(description = "主键 ID")
        private String id;
        @Schema(description = "本轮追踪短 ID")
        private String turnId;
        @Schema(description = "用户 ID")
        private String userId;
        @Schema(description = "会话 ID")
        private String sessionId;
        @Schema(description = "用户消息(截取)")
        private String userMessage;
        @Schema(description = "结束信号: ON_COMPLETE / ON_ERROR / ON_CANCEL")
        private String endSignal;
        @Schema(description = "LLM 调用次数")
        private Integer llmCalls;
        @Schema(description = "工具执行步数")
        private Integer toolSteps;
        @Schema(description = "输入 token 累计")
        private Long totalInTokens;
        @Schema(description = "输出 token 累计")
        private Long totalOutTokens;
        @Schema(description = "总耗时(毫秒)")
        private Long totalDurationMs;
        @Schema(description = "链路步骤名")
        private String chain;
        @Schema(description = "步骤明细(kind=llm/tool 及元数据)")
        private List<Map<String, Object>> steps;
        @Schema(description = "创建时间")
        private LocalDateTime createdAt;
    }

    @Data
    @Schema(description = "调用链日志分页视图")
    public static class LlmCallLogPageView {
        private List<LlmCallLogView> content;
        private int page;
        private int size;
        private long totalElements;
        private int totalPages;
    }

    private LlmCallLogDtos() {}
}