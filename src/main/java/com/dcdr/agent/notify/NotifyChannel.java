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
