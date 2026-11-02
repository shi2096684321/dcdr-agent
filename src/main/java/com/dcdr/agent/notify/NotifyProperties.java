package com.dcdr.agent.notify;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 执行结果推送配置 (绑定 {@code agent.notify.*})。
 * <p>
 * 默认 {@code enabled=false}: 与项目其它 V3 特性一致, 不配置就不产生任何外部请求。
 * Webhook 地址里带机器人 key, 属于凭据, <b>必须</b>通过环境变量注入, 不要写进仓库。
 */
@Data
@ConfigurationProperties(prefix = "agent.notify")
public class NotifyProperties {

    /** 总开关; 关闭时不发送任何请求 (任务级开关叠加在其上) */
    private boolean enabled = false;

    /** 默认渠道 (任务未指定 notifyChannel 时使用) */
    private NotifyChannel channel = NotifyChannel.WECOM_BOT;

    /**
     * 成功消息节流窗口(ms): 同一任务在窗口内最多推送 1 条。
     * <p>
     * 失败消息<b>不受</b>节流限制 —— 漏掉一次失败告警的代价远大于多收几条。
     * 设为 0 表示不节流。
     */
    private long throttleWindowMs = 600_000L;

    /** 请求超时(ms); 推送跑在 boundedElastic 上, 不会阻塞调度线程 */
    private int timeoutMs = 5_000;

    /**
     * 消息模板目录 (相对工作目录)。
     * <p>期望结构: {@code <dir>/<渠道code>/<success|failed>.md}, 例如
     * {@code config/notify/templates/dingtalk_bot/failed.md}。
     * 文件不存在时用代码内置的默认模板, 所以这个目录可以完全不存在。
     * <p>每次推送都会读文件, 改完模板立即生效, 不需要重启。
     */
    private String templatesDir = "./config/notify/templates";

    /**
     * 概览字数上限（<b>全局兜底</b>）。
     * <p>模板里的 {@code {{summary}}} 取正文前 N 字（在句末或换行处断开，不留半句话）。
     * 抓取类任务的正文可能上万字，IM 里只适合放概览 + 详情链接。
     * <p><b>渠道级 {@code summary-chars} 大于 0 时以渠道级为准</b>（见 {@link Wecom#getSummaryChars()}、
     * {@link Dingtalk#getSummaryChars()}）。统一用一个值不合适：企微 markdown 硬限 4096 <b>字节</b>
     * （约 1365 汉字），钉钉是 18000 <b>字符</b>，两者能装的内容差 10 倍以上 ——
     * 以前共用 300 字，钉钉那条上限几乎没用上，消息里只显示一小段，用户以为内容就这么点。
     */
    private int summaryChars = 300;

    /**
     * 详情页外部基址，如 {@code https://agent.example.com}。
     * <p>留空则<b>不生成</b>"查看完整结果"链接，只推概览 —— 相对路径在 IM 里点不开，
     * 不如干脆不给。
     */
    private String shareBaseUrl = "";

    /** 详情链接有效期（天）；过期后页面返回 404。默认 7 天 */
    private int shareTtlDays = 7;

    /** 企业微信群机器人 */
    private Wecom wecom = new Wecom();

    /** 钉钉群自定义机器人 */
    private Dingtalk dingtalk = new Dingtalk();

    @Data
    public static class Wecom {
        /**
         * 群机器人 Webhook 完整地址, 形如
         * {@code https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxxx}。
         * <p>在群聊右上角「消息推送 / 群机器人」里创建后复制; 留空则推送直接失败并给出提示。
         */
        private String webhookUrl = "";

        /**
         * 单条 markdown 最大<b>字节</b>数 (企业微信硬限 4096, 配大了也会被服务端拒绝)。
         * <p>注意是字节: 一个汉字 3 字节, 4096 字节实际只能放约 1300 个汉字。
         */
        private int maxBytes = 4096;

        /**
         * 概览字数上限（覆盖全局 {@link NotifyProperties#getSummaryChars()}）；0 = 用全局值。
         * <p>4096 字节 ÷ 3 ≈ 1365 汉字，扣掉模板骨架（标题/时间/耗时/链接约 80 字）
         * 和截断提示语，<b>900 字</b>是留了安全边界后的实用值。
         * 再往上调不会报错，但会频繁走到"超限 → 截断"，末尾那条链接容易被切掉。
         */
        private int summaryChars = 900;
    }

    @Data
    public static class Dingtalk {
        /**
         * 群机器人 Webhook 完整地址, 形如
         * {@code https://oapi.dingtalk.com/robot/send?access_token=xxxx}。
         * <p>群设置 → 智能群助手 → 添加机器人 → 自定义, 创建后复制。
         */
        private String webhookUrl = "";

        /**
         * 加签密钥 ({@code SEC} 开头)。
         * <p>机器人安全设置选「加签」时<b>必须</b>填写, 否则钉钉返回
         * {@code errcode=310000 sign not match}; 选「自定义关键词」或「IP 白名单」时留空。
         * 三种安全方式互斥, 推荐加签 —— 关键词模式会强迫每条消息都带上那个词,
         * IP 模式对出口 IP 不固定的部署很不友好。
         */
        private String secret = "";

        /**
         * 自定义关键词。
         * <p>机器人安全设置选「自定义关键词」时<b>必须</b>填写: 钉钉要求消息正文必须包含该词,
         * 否则返回 {@code errcode=310000「关键词不匹配」}。选「加签」或「IP 白名单」时留空。
         * <p>发送时会追加到正文末尾, 截断时也会为它预留空间 —— 关键词被截掉同样会被拒收。
         */
        private String keyword = "";

        /**
         * 单条 markdown 最大<b>字符</b>数 (钉钉约 20000 字符上限, 留余量)。
         * <p>与企微不同, 钉钉按字符计长, 故单独配置, 单位不要混。
         */
        private int maxChars = 18_000;

        /**
         * 概览字数上限（覆盖全局 {@link NotifyProperties#getSummaryChars()}）；0 = 用全局值。
         * <p>钉钉上限 18000 <b>字符</b>，<b>3000 字</b>只占六分之一，余量足够。
         * 之所以不直接给满：群消息在手机上滑三屏以上没人读完，且无链接时用户也只能看概览，
         * 3000 字足够覆盖"今日重点 + 几条要点"这种典型结果，又不会把消息刷成一篇长文。
         */
        private int summaryChars = 3_000;
    }
}
