package com.dcdr.agent.tool.websearch;

import com.dcdr.agent.common.util.WebClientFactory;
import com.dcdr.agent.config.ToolsProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

/**
 * DeepSeek Search / Fetch 的共用 HTTP 客户端。
 *
 * <p><b>为什么不放在两个 Provider 里各写一份</b>: 鉴权头、超时、以及"HTTP 状态码 → 人话"
 * 的翻译是<b>同一个接口族</b>的公共知识。各写一份的后果已经见过太多次: 改了一边漏了另一边,
 * 于是同一个 402 在搜索日志里叫"余额不足"、在抓取日志里叫 "Payment Required"。
 *
 * <p>两个接口与模型接口同一个 Base URL、同一套 Bearer 鉴权, 故复用模型侧的
 * {@code DEEPSEEK_API_KEY} 即可(可用独立 key 覆盖, 见 {@code DeepseekConfig#apiKey})。
 *
 * <p><b>速率限制</b>: 按账号限 QPS(Search 与 Fetch 分别计, Fetch 另有单目标域名的限制),
 * 超限返回 429。本项目是"单轮对话里调几次"的用法, 不会触及; 若将来批量跑评测,
 * 需要在这里加退避重试, 而不是在调用方各加一遍。
 */
@Slf4j
@Component
public class DeepseekApiClient {

    /** 搜索接口路径 */
    public static final String PATH_SEARCH = "/v1/tools/search";

    /** 抓取接口路径 */
    public static final String PATH_FETCH = "/v1/tools/fetch";

    private final ToolsProperties.WebToolsConfig.DeepseekConfig config;
    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public DeepseekApiClient(ToolsProperties properties) {
        ToolsProperties.WebToolsConfig web = properties.getWeb();
        this.config = web.getDeepseek();
        this.webClient = WebClientFactory.build(
                stripTrailingSlash(config.getBaseUrl()),
                Duration.ofSeconds(web.getTimeoutSeconds()),
                Map.of("Accept", "application/json"));
        log.info("DeepseekApiClient 初始化: baseUrl={}, keyConfigured={}, needContent={}",
                config.getBaseUrl(), hasApiKey(), config.isNeedContent());
    }

    /** API Key 是否已配置; 未配置时该策略不可用, 由工厂决定是否降级 */
    public boolean hasApiKey() {
        return config.getApiKey() != null && !config.getApiKey().isBlank();
    }

    /**
     * 发起一次 POST 并解析为 JSON。
     *
     * @param path    接口路径
     * @param payload 请求体(直接由 Jackson 序列化, 字段名用 snake_case)
     * @param action  动作名, 仅用于错误信息("搜索" / "抓取")
     * @return 已解析的响应体
     * @throws IOException 网络异常或协议异常; 错误信息已翻译成人话
     */
    public JsonNode post(String path, Map<String, Object> payload, String action) throws IOException {
        String body;
        try {
            body = webClient.post()
                    .uri(path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (WebClientResponseException e) {
            throw new IOException(action + "失败: " + translate(e.getStatusCode().value())
                    + "(HTTP " + e.getStatusCode().value() + ")", e);
        } catch (Exception e) {
            // 连不上 / DNS / 连接池耗尽等: 保留原始 message, 但补上动作名便于定位
            throw new IOException(action + "请求异常: " + e.getMessage(), e);
        }
        if (body == null || body.isBlank()) {
            throw new IOException(action + "失败: 响应体为空");
        }
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw new IOException(action + "失败: 响应不是合法 JSON", e);
        }
    }

    /** 把 HTTP 状态码翻译成人看得懂的原因; 不知道的按 4xx/5xx 大致归类 */
    private static String translate(int status) {
        return switch (status) {
            case 400 -> "请求参数不合法";
            case 401 -> "API Key 无效";
            case 402 -> "账户余额不足";
            case 403 -> "被安全策略拦截";
            case 404 -> "无法提取页面内容";
            case 408 -> "请求超时";
            case 413 -> "请求体过大";
            case 429 -> "触发限流(按账号 QPS, 需退避重试)";
            default -> status >= 500 ? "服务端错误" : "请求失败";
        };
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) {
            return null;
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
