package com.dcdr.agent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * V3.0 Webhook 相关 DTO (F-31 触发层扩展)。
 */
public class WebhookDtos {

    private WebhookDtos() {}

    /** 创建 Webhook 请求 */
    @Data
    @Schema(description = "创建 Webhook 请求")
    public static class SaveWebhookRequest {
        @Schema(description = "绑定的工作流 ID", requiredMode = Schema.RequiredMode.REQUIRED)
        private String workflowId;
        @Schema(description = "端点名称")
        private String name;
        @Schema(description = "可选 secret (用于签名验证)")
        private String secret;
        @Schema(description = "是否启用")
        private Boolean enabled;
    }

    /** Webhook 视图 */
    @Data
    @Schema(description = "Webhook 视图")
    public static class WebhookView {
        private String id;
        private String userId;
        private String workflowId;
        private String workflowName;
        private String name;
        private String token;
        private String url;
        private String secret;
        private boolean enabled;
        private LocalDateTime lastTriggered;
        private int triggerCount;
        private LocalDateTime createdAt;
        @JsonProperty("editable")
        private boolean editable;
    }
}
