package com.dcdr.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户级推送配置相关 DTO (V3.4)。
 */
public class NotifyConfigDtos {

    private NotifyConfigDtos() {}

    /** 某渠道的配置视图 (Webhook 只回显掩码) */
    @Data
    @Schema(description = "推送配置视图 (凭据只回显掩码)")
    public static class NotifyConfigView {
        private String channel;
        private String channelLabel;
        /** 是否已配置可用的 Webhook */
        private boolean configured;
        private boolean enabled;
        /** 掩码后的地址, 如 https://qyapi.weixin.qq.com/...?key=****a1b2 */
        private String webhookMasked;
        /** Webhook 填写示例/格式提示 */
        private String webhookExample;
        /** 是否已配置加签密钥 (钉钉) */
        private boolean hasSecret;
        private String keyword;
        /** 该渠道是否支持加签/关键词 (钉钉专用项) */
        private boolean supportsSecret;
        private LocalDateTime updatedAt;
        /** 未配置时回退的部署级配置是否可用 (用于提示"当前走全局默认") */
        private boolean globalFallbackAvailable;
    }

    /** 保存推送配置请求 (凭据留空 = 保留原值) */
    @Data
    @Schema(description = "保存推送配置请求")
    public static class SaveNotifyConfigRequest {
        @Schema(description = "渠道 code", example = "wecom_bot")
        private String channel;
        @Schema(description = "Webhook 地址; 留空表示保留原值")
        private String webhookUrl;
        @Schema(description = "钉钉加签密钥; 留空表示保留原值")
        private String secret;
        @Schema(description = "钉钉自定义关键词")
        private String keyword;
        @Schema(description = "是否启用")
        private Boolean enabled;
    }

    /** 测试推送结果 */
    @Data
    @Schema(description = "测试推送结果")
    public static class NotifyTestResult {
        private boolean success;
        private boolean skipped;
        private String channel;
        private String error;
        private String reason;
    }
}
