package com.dcdr.agent.notify;

import com.dcdr.agent.common.util.WebClientFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * 钉钉群自定义机器人推送 ({@code oapi.dingtalk.com/robot/send})。
 * <p>
 * 与企业微信的三处关键差异:
 * <ol>
 *   <li><b>加签</b>: 机器人安全设置选「加签」时, 请求 URL 必须带
 *       {@code &timestamp=毫秒&sign=...}。签名算法是
 *       {@code urlEncode(base64(hmacSha256(secret, timestamp + "\n" + secret)))} ——
 *       注意被签的是 {@code timestamp + "\n" + secret} 这个<b>字符串</b>, 而 secret 同时
 *       充当 HMAC 的<b>密钥</b>。漏掉换行符会得到 {@code sign not match}。</li>
 *   <li><b>长度按字符算</b>, 不按字节 (企微是字节)。所以截断用
 *       {@link NotifyRender#truncateChars} 而不是字节版。</li>
 *   <li><b>不支持 {@code <font>} 标签</b>, 传进去会原样显示, 故渲染时关闭着色。</li>
 * </ol>
 * <p>
 * 频率限制同样是 20 条/分钟, 但超出后是<b>限流 10 分钟</b> (返回 {@code errcode=130101}),
 * 比企业微信更狠 —— 节流在这里不是体验优化, 是可用性保障。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DingtalkBotNotifier implements Notifier {

    /** 截断提示语 (字符数要计入预算) */
    static final String TRUNCATE_SUFFIX = "\n\n…(内容过长, 已截断)";

    /** 钉钉 markdown 正文约 20000 字符上限, 配置只能调小 */
    static final int MAX_MARKDOWN_CHARS = 20_000;

    /** title 在会话列表里展示, 过长会被截断得很难看 */
    private static final int MAX_TITLE_CHARS = 40;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String HMAC_SHA256 = "HmacSHA256";

    private final NotifyProperties props;
    private final NotifyTemplate template;

    private volatile WebClient client;

    @Override
    public NotifyChannel channel() {
        return NotifyChannel.DINGTALK_BOT;
    }

    @Override
    public NotifyResult send(NotifyMessage message) {
        // 用户级配置优先 (V3.3), 未配置时回退部署级 agent.notify.dingtalk.*
        String userUrl = message.getWebhookUrl();
        // 纵深防御: 保存时校验过一次, 发请求前再校验一次; 部署级地址不校验(可能走公司代理)
        if (userUrl != null && !userUrl.isBlank()) {
            String deny = NotifyUrlGuard.check(channel(), userUrl);
            if (deny != null) return NotifyResult.failed(channel().code(), deny);
        }
        String rawUrl = firstNonBlank(userUrl, props.getDingtalk().getWebhookUrl());
        if (rawUrl == null || rawUrl.isBlank()) {
            return NotifyResult.failed(channel().code(),
                    "未配置钉钉机器人地址: 请在「推送配置」中填写个人 Webhook, "
                            + "或由管理员设置 agent.notify.dingtalk.webhook-url");
        }
        String title = buildTitle(message);
        String keyword = firstNonBlank(message.getKeyword(), props.getDingtalk().getKeyword());
        // 关键词模式下正文必须含该词(否则 310000)，所以先把它占的字符从预算里扣掉，
        // 再渲染正文 —— 顺序反了的话超长消息会把关键词截没，报的仍是"关键词不匹配"
        int budget = Math.max(64, limitChars() - keywordCost(keyword));
        String text = appendKeyword(
                template.render(channel(), message, budget, NotifyTemplate.Unit.CHARS, TRUNCATE_SUFFIX),
                keyword);
        try {
            String secret = firstNonBlank(message.getSecret(), props.getDingtalk().getSecret());
            String url = signedUrl(rawUrl, secret, System.currentTimeMillis());
            String resp = client()
                    .post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                            "msgtype", "markdown",
                            "markdown", Map.of("title", title, "text", text)))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofMillis(timeoutMs() + 1000L));
            return parseResponse(resp);
        } catch (Exception e) {
            log.warn("钉钉推送失败 (task={}): {}", message.getTaskId(), e.toString());
            // 不能透传 e.getMessage(): 它可能含带 access_token 的完整 Webhook, 会落库并展示到页面
            return NotifyResult.failed(channel().code(), "请求异常: " + NotifyRender.safeReason(e));
        }
    }

    @Override
    public int maxSize() {
        return limitChars();
    }

    @Override
    public NotifyTemplate.Unit unit() {
        return NotifyTemplate.Unit.CHARS;
    }

    @Override
    public String truncateSuffix() {
        return TRUNCATE_SUFFIX;
    }

    /** 用户级凭据优先, 空则回退部署级 */
    private static String firstNonBlank(String preferred, String fallback) {
        return (preferred != null && !preferred.isBlank()) ? preferred : fallback;
    }

    // ===== 加签 =====

    /**
     * 按需给 Webhook 地址拼接 timestamp 与 sign。
     * <p>secret 为空表示机器人用的是「自定义关键词」或「IP 白名单」, 此时不需要加签。
     *
     * @param timestamp 毫秒时间戳; 与钉钉服务器时间误差不能超过 1 小时
     */
    static String signedUrl(String url, String secret, long timestamp) {
        if (secret == null || secret.isBlank()) return url;
        String sep = url.contains("?") ? "&" : "?";
        return url + sep + "timestamp=" + timestamp + "&sign=" + sign(secret, timestamp);
    }

    /**
     * 计算签名: {@code urlEncode(base64(hmacSha256(secret, timestamp + "\n" + secret)))}。
     * <p>
     * 两个容易搞错的点:
     * <ul>
     *   <li>参与签名的字符串是 {@code timestamp + "\n" + secret}, 换行符不能少;</li>
     *   <li>HMAC 的密钥是 secret 本身, 不是 stringToSign。</li>
     * </ul>
     */
    static String sign(String secret, long timestamp) {
        try {
            String stringToSign = timestamp + "\n" + secret;
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            byte[] raw = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
            return URLEncoder.encode(Base64.getEncoder().encodeToString(raw), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("钉钉加签失败: " + e.getMessage(), e);
        }
    }

    // ===== 消息组装 =====

    private static String buildTitle(NotifyMessage m) {
        String state = m.isFailed() ? "任务失败" : "任务完成";
        return NotifyRender.truncateChars(
                NotifyRender.orDefault(m.getTaskName(), "定时任务") + " · " + state,
                MAX_TITLE_CHARS, "…");
    }

    /**
     * 关键词占用的字符数（含与正文之间的空行）。
     * <p>调用方要先把这部分从长度预算里扣掉，再渲染正文。
     */
    static int keywordCost(String keyword) {
        return (keyword == null || keyword.isBlank()) ? 0 : keyword.trim().length() + 2;
    }

    /** 按需把自定义关键词追加到正文末尾 */
    static String appendKeyword(String text, String keyword) {
        if (keyword == null || keyword.isBlank()) return text;
        return text + "\n\n" + keyword.trim();
    }

    // ===== 响应解析 =====

    private NotifyResult parseResponse(String resp) {
        if (resp == null || resp.isBlank()) {
            return NotifyResult.failed(channel().code(), "钉钉返回空响应");
        }
        try {
            JsonNode node = MAPPER.readTree(resp);
            int errcode = node.path("errcode").asInt(-1);
            if (errcode == 0) return NotifyResult.success(channel().code());
            return NotifyResult.failed(channel().code(),
                    describe(errcode, node.path("errmsg").asText("")));
        } catch (Exception e) {
            return NotifyResult.failed(channel().code(),
                    "响应无法解析: " + NotifyRender.truncateUtf8(resp, 200, "..."));
        }
    }

    /**
     * 把错误码翻译成可操作的中文。
     * <p>钉钉原始 errmsg 是英文短句, 直接展示的话用户看不出该改哪一项配置。
     */
    static String describe(int errcode, String errmsg) {
        return switch (errcode) {
            case 130101 -> "触发频率限制 (130101): 同一机器人每分钟最多 20 条, 超限会被限流 10 分钟";
            case 310000 -> "安全校验未通过 (310000): 检查加签 secret 是否正确, "
                    + "或消息正文是否缺少自定义关键词。原始信息: " + errmsg;
            case 300005 -> "webhook token 不存在 (300005): 地址拼错或机器人已被移除";
            case 300001 -> "token 无效 (300001): 检查 webhook 地址是否完整";
            default -> "errcode=" + errcode + " " + errmsg;
        };
    }

    // ===== 基础设施 =====

    private WebClient client() {
        WebClient c = client;
        if (c == null) {
            synchronized (this) {
                c = client;
                if (c == null) {
                    c = WebClientFactory.build(null, Duration.ofMillis(timeoutMs()), null);
                    client = c;
                }
            }
        }
        return c;
    }

    private int limitChars() {
        int cfg = props.getDingtalk().getMaxChars();
        if (cfg <= 0 || cfg > MAX_MARKDOWN_CHARS) return MAX_MARKDOWN_CHARS;
        return cfg;
    }

    private int timeoutMs() {
        int t = props.getTimeoutMs();
        return t <= 0 ? 5000 : t;
    }
}
