package com.dcdr.agent.tool.websearch;

import com.dcdr.agent.config.ToolsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DeepSeek 网页抓取策略(V3.3)。
 *
 * <p>接口: {@code POST /v1/tools/fetch}(单个 url 进, {@code {title, content}} 出,
 * content 已是 Markdown)。文档见
 * <a href="https://api-docs.deepseek.com/zh-cn/api/fetch">网页抓取</a>。
 *
 * <p><b>它和本地链路是两套完全不同的能力, 不是"同一个东西的不同实现"</b>:
 * <ul>
 *   <li>本地: 自己下载 HTML, 用 crux 剔噪声、结构化重建, <b>每一步都可调</b> ——
 *       中文站 GBK 编码、代码块语言标注这类问题都是实测踩出来后打的补丁;</li>
 *   <li>DeepSeek: 黑盒。{@code 403}=被它的安全策略拦, {@code 404}=它提不出可用 Markdown,
 *       这两种失败<b>无从改进</b>, 只能换源。</li>
 * </ul>
 * 所以本类把失败一律收敛成 {@code null}(而不是异常), 由 {@link FallbackFetchProvider}
 * 决定是否切到本地兜底 —— 抓取是逐 URL 进行的, 一个失败不该中断其余 URL。
 */
@Slf4j
@Component
public class DeepseekFetchProvider implements WebFetchProvider {

    public static final String ID = "deepseek";

    private final ToolsProperties.WebToolsConfig config;
    private final DeepseekApiClient apiClient;

    public DeepseekFetchProvider(ToolsProperties properties, DeepseekApiClient apiClient) {
        this.config = properties.getWeb();
        this.apiClient = apiClient;
        log.info("DeepseekFetchProvider 初始化: maxContent={}字符, ssrfGuard={}",
                config.getMaxContentLength(), config.isSsrfGuard());
    }

    @Override
    public String id() {
        return ID;
    }

    /** 未配置 Key 时不可用, 工厂会据此跳过本策略 */
    @Override
    public boolean available() {
        return apiClient.hasApiKey();
    }

    @Override
    public FetchedPage fetch(String url, String query) {
        // 目标地址是我们替模型发出去的, 内网地址同样不该泄露给第三方 —— 这道闸不能因为
        // "反正对方抓不到内网" 就省掉, 它防的是拓扑信息外泄, 不只是内容读取
        if (!SsrfGuard.isSafe(url, config.isSsrfGuard())) {
            log.warn("DeepSeek 抓取拒绝不安全的 URL(SSRF 防护): {}", url);
            return null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("url", url);
        try {
            var root = apiClient.post(DeepseekApiClient.PATH_FETCH, payload, "网页抓取");
            String content = root.path("content").asText("");
            if (content.isBlank()) {
                log.warn("DeepSeek 抓取未返回正文: url={}", url);
                return null;
            }
            String title = root.path("title").asText("");
            log.info("DeepSeek 抓取成功: url={}, 标题={}, 正文字符数={}",
                    url, title, content.length());
            return new FetchedPage(title, HtmlArticleExtractor.clip(content, config.getMaxContentLength()));
        } catch (Exception e) {
            // 403/404 是"这个 URL 它抓不了", 与网络故障一样都该让兜底接手, 故统一返回 null
            log.warn("DeepSeek 抓取失败, 将尝试兜底: url={}, cause={}", url, e.getMessage());
            return null;
        }
    }
}
