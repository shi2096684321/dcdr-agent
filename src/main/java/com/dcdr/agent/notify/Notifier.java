package com.dcdr.agent.notify;

/**
 * 通知渠道实现接口。
 * <p>
 * 新增渠道 (钉钉/飞书/邮件) 只需实现本接口并标注 {@code @Component},
 * {@link NotifierRegistry} 会在启动时自动收集, 调度层零改动 —— 与项目里
 * {@code Tool} + {@code ToolRegistry} 的扩展方式保持一致。
 */
public interface Notifier {

    /** 本实现负责的渠道 */
    NotifyChannel channel();

    /**
     * 发送一条通知。实现必须自行兜住所有异常并返回 {@link NotifyResult},
     * 不要向外抛 —— 推送是旁路能力, 不允许影响任务本身的成败。
     */
    NotifyResult send(NotifyMessage message);

    /**
     * 本渠道单条消息的长度上限。
     * <p>用于模板预览: 让用户在配置模板时就能看到"会不会被截断", 而不是等真推送才发现。
     */
    int maxSize();

    /** 本渠道的计长单位 (企微按字节、钉钉按字符) */
    NotifyTemplate.Unit unit();

    /** 截断时追加的提示语 */
    String truncateSuffix();
}
