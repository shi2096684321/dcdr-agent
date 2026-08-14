package com.dcdr.agent.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * V3.0 智能路由相关 DTO (F-30)。
 */
public class RoutingDtos {

    private RoutingDtos() {}

    /** 路由判断结果 (IntentRouter 内部使用 + 落库) */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "路由判断结果")
    public static class RouteResult {
        @Schema(description = "路由类型: workflow / agent / chat")
        private String type;
        @Schema(description = "命中的目标 ID (工作流/Agent ID; chat 时为空)")
        private String target;
        @Schema(description = "目标名称 (供前端展示, 由后端回填, LLM 输出不需要)")
        private String targetName;
        @Schema(description = "置信度 0~1, 低于阈值时回退 chat")
        private BigDecimal confidence;
        @Schema(description = "判断理由")
        private String reason;

        public boolean isWorkflow() { return "workflow".equalsIgnoreCase(type); }
        public boolean isAgent() { return "agent".equalsIgnoreCase(type); }
        public boolean isChat() { return type == null || "chat".equalsIgnoreCase(type); }
    }

    /** 路由记录视图 (查询接口返回) */
    @Data
    @Schema(description = "路由记录视图")
    public static class RoutingLogView {
        private String id;
        private String userId;
        private String sessionId;
        private String userMessage;
        private String routeType;
        private String targetId;
        private BigDecimal confidence;
        private String reason;
        private LocalDateTime createdAt;
        @JsonProperty("isHighConfidence")
        private boolean highConfidence;
    }
}
