package com.dcdr.agent.notify;

import com.dcdr.agent.common.util.WebClientFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

/**
 * 企业微信群机器人推送 (官方接口 {@code qyapi.weixin.qq.com/cgi-bin/webhook/send})。
 * <p>
 * 为什么选它而不是个人微信: 个人微信没有任何官方推送 API, 第三方中转 (Server酱 / PushPlus)
 * 会把消息正文完整落到对方服务器。企业微信是自家接口, 配合「微信插件」还能让消息直接
 * 出现在个人微信里。
 * <p>
 * <b>两个必须遵守的服务端硬限制</b> (超了直接拒绝, 不是软限制):
 * <ul>
 *   <li>markdown 正文最长 <b>4096 字节</b> —— 注意是字节不是字符, 中文占 3 字节,
 *       按字符截断必然超限;</li>
 *   <li>同一机器人 <b>20 条/分钟</b>, 超出返回 {@code errcode=45009}。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WecomBotNotifier implements Notifier {

    /** 企业微信 markdown 内容字节上限 (硬限, 配置只能调小不能调大) */
    static final int MAX_MARKDOWN_BYTES = 4096;

    /** 截断后追加的提示 (本身字节数要计入预算) */
    static final String TRUNCATE_SUFFIX = "\n\n…(内容过长, 已截断)";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final NotifyProperties props;
    private final NotifyTemplate template;

    /** 懒加载: 未配置 Webhook 时不创建连接池; WebClient 本身线程安全, 可复用 */
    private volatile WebClient client;

    @Override
    public NotifyChannel channel() {
        return NotifyChannel.WECOM_BOT;
    }

    @Override
    public NotifyResult send(NotifyMessage message) {
        String url = props.getWecom().getWebhookUrl();
        if (url == null || url.isBlank()) {
            return NotifyResult.failed(channel().code(),
                    "未配置企业微信机器人地址 (agent.notify.wecom.webhook-url)");
        }
        // 按渠道 + 状态渲染模板；默认模板里企业微信用 <font> 给错误信息着色
        String markdown = template.render(channel(), message, limitBytes(),
                NotifyTemplate.Unit.BYTES, TRUNCATE_SUFFIX);
        try {
            String resp = client()
                    .post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("msgtype", "markdown", "markdown", Map.of("content", markdown)))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofMillis(timeoutMs() + 1000L));
            return parseResponse(resp);
        } catch (Exception e) {
            log.warn("企业微信推送失败 (task={}): {}", message.getTaskId(), e.toString());
            return NotifyResult.failed(channel().code(), "请求异常: " + e.getMessage());
        }
    }

    @Override
    public int maxSize() {
        return limitBytes();
    }

    @Override
    public NotifyTemplate.Unit unit() {
        return NotifyTemplate.Unit.BYTES;
    }

    @Override
    public String truncateSuffix() {
        return TRUNCATE_SUFFIX;
    }

    // ===== 响应解析 =====
    /**
     * 解析企业微信响应。
     * <p>
     * 关键点: HTTP 200 不代表发送成功 —— 业务错误同样返回 200, 靠 body 里的
     * {@code errcode} 判断。只看状态码会把"频率超限""key 失效"全记成成功。
     */
    private NotifyResult parseResponse(String resp) {
        if (resp == null || resp.isBlank()) {
            return NotifyResult.failed(channel().code(), "企业微信返回空响应");
        }
        try {
            JsonNode node = MAPPER.readTree(resp);
            int errcode = node.path("errcode").asInt(-1);
            if (errcode == 0) return NotifyResult.success(channel().code());
            String errmsg = node.path("errmsg").asText("");
            if (errcode == 45009) {
                return NotifyResult.failed(channel().code(),
                        "触发频率限制 (45009): 同一机器人每分钟最多 20 条");
            }
            return NotifyResult.failed(channel().code(), "errcode=" + errcode + " " + errmsg);
        } catch (Exception e) {
            return NotifyResult.failed(channel().code(),
                    "响应无法解析: " + NotifyRender.truncateUtf8(resp, 200, "..."));
        }
    }

    // ===== 基础设施 =====

    private WebClient client() {
        WebClient c = client;
        if (c == null) {
            synchronized (this) {
                c = client;
                if (c == null) {
                    // 复用项目的连接池配置, 避免自建 WebClient 复用死连接抛 Connection reset
                    c = WebClientFactory.build(null, Duration.ofMillis(timeoutMs()), null);
                    client = c;
                }
            }
        }
        return c;
    }

    /** 配置只能把上限调小, 不能超过服务端硬限 */
    private int limitBytes() {
        int cfg = props.getWecom().getMaxBytes();
        if (cfg <= 0 || cfg > MAX_MARKDOWN_BYTES) return MAX_MARKDOWN_BYTES;
        return cfg;
    }

    private int timeoutMs() {
        int t = props.getTimeoutMs();
        return t <= 0 ? 5000 : t;
    }
}
