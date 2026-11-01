package com.dcdr.agent.notify;

/**
 * 通知渠道枚举。
 * <p>
 * {@code code} 是落库/配置用的稳定标识 (与枚举名解耦, 便于将来改名不影响存量数据),
 * 数据库 {@code scheduled_tasks.notify_channel} 存的就是它; 留空表示"用全局默认渠道"。
 */
public enum NotifyChannel {

    /** 企业微信群机器人 Webhook (官方接口, 走 qyapi.weixin.qq.com) */
    WECOM_BOT("wecom_bot", "企业微信群机器人"),

    /** 钉钉群自定义机器人 Webhook (oapi.dingtalk.com/robot/send) */
    DINGTALK_BOT("dingtalk_bot", "钉钉群机器人");

    private final String code;
    private final String label;

    NotifyChannel(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    /** 是否支持加签 / 自定义关键词 (目前只有钉钉; 企微机器人只有 key 一种鉴权) */
    public boolean supportsSign() {
        return this == DINGTALK_BOT;
    }

    /**
     * 官方域名 (SSRF 白名单的唯一真值源)。
     * <p>用户填的地址会被服务端直接请求，只允许请求各渠道官方域名。
     */
    public String allowedHost() {
        return switch (this) {
            case WECOM_BOT -> "qyapi.weixin.qq.com";
            case DINGTALK_BOT -> "oapi.dingtalk.com";
        };
    }

    /** 填写示例, 前端展示用 (不含真实凭据) */
    public String exampleWebhook() {
        return switch (this) {
            case WECOM_BOT -> "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxxxxxxx";
            case DINGTALK_BOT -> "https://oapi.dingtalk.com/robot/send?access_token=xxxxxxxx";
        };
    }

    /**
     * 按 code 或枚举名解析 (大小写不敏感)。
     *
     * @return 无法识别时返回 {@code null}, 由调用方决定降级策略 (不要静默当成默认渠道,
     *         否则用户配错渠道名会以为推送失败是网络问题)
     */
    public static NotifyChannel fromCode(String code) {
        if (code == null || code.isBlank()) return null;
        String v = code.trim();
        for (NotifyChannel c : values()) {
            if (c.code.equalsIgnoreCase(v) || c.name().equalsIgnoreCase(v)) return c;
        }
        return null;
    }
}
