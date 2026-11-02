package com.dcdr.agent.tool.websearch;

import com.dcdr.agent.common.util.WebClientFactory;
import com.dcdr.agent.config.ToolsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

/**
 * 本地 HTML 抓取策略(V3.3 从 {@code WebExtractTool} 抽出的取字节部分, 行为不变)。
 *
 * <p>只负责"取字节 + 过 SSRF 闸", 真正的正文抽取在 {@link HtmlArticleExtractor}
 * (纯静态、可离线测)。这样拆的理由: 网络部分不可单测, 抽取部分必须能单测。
 *
 * <p><b>能力边界</b>: jsoup 不执行 JavaScript, 纯 SPA 站点(内容由 JS 渲染)取不到正文,
 * 返回 {@code null} 由上层降级为使用搜索摘要。
 */
@Slf4j
@Component
public class LocalHtmlFetchProvider implements WebFetchProvider {

    public static final String ID = "local";

    /** snippet 最大长度(与 {@code WebExtractTool} 原有口径一致, 保留给来源卡片展示) */
    private static final int SNIPPET_MAX = 300;

    private final ToolsProperties.WebToolsConfig config;
    private final WebClient webClient;

    public LocalHtmlFetchProvider(ToolsProperties properties) {
        this.config = properties.getWeb();
        this.webClient = WebClientFactory.build(
                null,
                Duration.ofSeconds(config.getTimeoutSeconds()),
                Map.of("User-Agent", config.getUserAgent(),
                        "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        "Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8"));
        log.info("LocalHtmlFetchProvider 初始化: timeout={}s, maxContent={}字符, ssrfGuard={}",
                config.getTimeoutSeconds(), config.getMaxContentLength(), config.isSsrfGuard());
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public FetchedPage fetch(String url, String query) {
        if (!SsrfGuard.isSafe(url, config.isSsrfGuard())) {
            log.warn("本地抓取拒绝不安全的 URL(SSRF 防护): {}", url);
            return null;
        }
        try {
            // 取字节而非字符串: 交给 jsoup 依据 <meta charset>/BOM 自动判定编码,
            // 避免中文站点(GBK/GB2312) 被按 UTF-8 解码成乱码
            byte[] raw = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block();
            if (raw == null || raw.length == 0) {
                log.warn("本地抓取响应为空: {}", url);
                return null;
            }
            FetchedPage page = HtmlArticleExtractor.extract(raw, url, config.getMaxContentLength());
            if (page == null) {
                log.warn("本地抓取未能提取到正文(可能是纯 JS 渲染页面): {}", url);
                return null;
            }
            log.info("本地抓取成功: url={}, 标题={}, 正文字符数={}",
                    url, page.title(), page.content().length());
            return page;
        } catch (Exception e) {
            log.warn("本地抓取失败: url={}, cause={}", url, e.getMessage());
            return null;
        }
    }

    /** 来源卡片里的 snippet 长度上限(供门面复用, 避免两处各写一个魔法数) */
    public static int snippetMax() {
        return SNIPPET_MAX;
    }
}
