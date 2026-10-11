package com.dcdr.agent.tool.websearch;

import com.chimbori.crux.articles.Article;
import com.chimbori.crux.articles.ArticleExtractor;
import com.dcdr.agent.common.util.WebClientFactory;
import com.dcdr.agent.config.ToolsProperties;
import com.dcdr.agent.tool.AbstractTool;
import com.dcdr.agent.tool.ToolInfo;
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网页正文抓取工具(V3.2, 替代 Tavily 的 extract 能力)。
 * <p>
 * 链路: {@code WebClient 取字节 → jsoup 解析(自动识别 charset, 解决中文站 GBK 乱码)
 * → crux ArticleExtractor 提取正文(去导航/广告/页脚) → flexmark 转 Markdown}。
 * <p>
 * <b>与编排层的契约</b>(不可变更):
 * <ul>
 *   <li>工具名必须为 {@code web_extract} —— 编排层对 {@code "web_extract"} 有硬编码特判</li>
 *   <li>参数名必须为 {@code urls}(数组或单值) 与可选 {@code query} ——
 *       {@code AgentOrchestrator#enrichWithExtract} 按此名传参</li>
 *   <li><b>完整正文必须放在 sources[0].content</b> —— 编排层正是从这里取全文,
 *       再经 {@code cleanMarkdownContent} 清洗后写回搜索来源</li>
 * </ul>
 * <p>
 * <b>能力边界</b>: jsoup 不执行 JavaScript, 纯 SPA 站点(内容由 JS 渲染)无法提取正文,
 * 此时返回空 content, 上游会优雅降级为使用搜索摘要。
 */
@Slf4j
@Component
public class WebExtractTool extends AbstractTool {

    /** 工具名: 与原先 MCP(tavily-extract) 的 alias 保持一致, 编排层零改动 */
    public static final String NAME = "web_extract";

    /** snippet 最大长度 */
    private static final int SNIPPET_MAX = 300;

    private final ToolsProperties.WebToolsConfig config;
    private final WebClient webClient;
    private final FlexmarkHtmlConverter mdConverter = FlexmarkHtmlConverter.builder().build();

    public WebExtractTool(ToolsProperties properties) {
        this.config = properties.getWeb();
        this.webClient = WebClientFactory.build(
                null,
                Duration.ofSeconds(config.getTimeoutSeconds()),
                Map.of("User-Agent", config.getUserAgent(),
                        "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        "Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8"));
        log.info("WebExtractTool 初始化: timeout={}s, maxContent={}字符, ssrfGuard={}, enabled={}",
                config.getTimeoutSeconds(), config.getMaxContentLength(), config.isSsrfGuard(), config.isEnabled());
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "网页正文抓取工具：抓取指定 URL 的网页并提取其中的正文内容（自动去除导航栏、广告、页脚等噪声）。\n"
                + "\n"
                + "【何时调用】\n"
                + "- 需要某个已知链接的完整内容，而不仅是搜索摘要时\n"
                + "- 用户直接提供了 URL 并希望你阅读/总结该页面\n"
                + "- 搜索结果摘要过于简略，需要深入阅读原文\n"
                + "\n"
                + "【何时不要调用】\n"
                + "- 还没有链接时，应先用 web_search 找到来源\n"
                + "- 搜索摘要已经足够回答问题时（抓取正文会消耗更多时间）\n"
                + "- 纯前端渲染（SPA）的站点可能提取不到正文，属正常情况\n"
                + "\n"
                + "【返回内容】\n"
                + "- 成功：返回该页面的标题与正文（Markdown 格式）\n"
                + "- 失败：返回失败原因，此时应保留已有的搜索摘要，不要编造正文";
    }

    @Override
    public JsonObjectSchema getParameters() {
        return JsonObjectSchema.builder()
                .addProperty("urls", JsonArraySchema.builder()
                        .items(JsonStringSchema.builder()
                                .description("要抓取的完整 URL，必须以 http:// 或 https:// 开头").build())
                        .description("待抓取的 URL 列表，单次最多 " + config.getMaxExtractUrls() + " 个").build())
                .addProperty("query", JsonStringSchema.builder()
                        .description("可选：用户原始问题，用于按相关性聚焦正文内容").build())
                .required(List.of("urls"))
                .build();
    }

    @Override
    public boolean supportsStreaming() {
        return config.isEnabled();
    }

    @Override
    public String execute(Map<String, Object> params) {
        return executeWithInfo(params).getContent();
    }

    @Override
    public ToolInfo executeWithInfo(Map<String, Object> params) {
        if (!config.isEnabled()) {
            return ToolInfo.text("网页抓取未启用");
        }
        List<String> urls = resolveUrls(params);
        if (urls.isEmpty()) {
            return ToolInfo.text("请提供要抓取的 URL");
        }

        List<Map<String, Object>> sources = new ArrayList<>();
        StringBuilder compact = new StringBuilder();
        int ok = 0, failed = 0;
        for (String url : urls) {
            Map<String, Object> source = extractOne(url, sources.size() + 1);
            if (source != null) {
                sources.add(source);
                ok++;
            } else {
                failed++;
            }
        }

        if (sources.isEmpty()) {
            return ToolInfo.text("网页抓取失败: 未能从 " + urls.size() + " 个 URL 中提取到正文"
                    + "（可能是纯 JS 渲染页面、需要登录、或已阻止抓取）");
        }
        compact.append("已抓取 ").append(ok).append(" 个页面");
        if (failed > 0) {
            compact.append("（").append(failed).append(" 个失败）");
        }
        compact.append("的正文内容：\n\n");
        for (Map<String, Object> s : sources) {
            compact.append("[[").append(s.get("index")).append("]] ").append(s.get("title"));
            Object u = s.get("url");
            if (u != null) {
                compact.append("  (").append(u).append(")");
            }
            compact.append("\n").append(s.get("content")).append("\n");
        }
        return new ToolInfo(compact.toString(), sources);
    }

    /**
     * 抓取并提取单个 URL 的正文。
     *
     * @return 来源卡片({@code {index,title,url,snippet,content}}); 失败返回 null
     */
    private Map<String, Object> extractOne(String url, int index) {
        if (!isSafeUrl(url)) {
            log.warn("web_extract 拒绝不安全的 URL(SSRF 防护): {}", url);
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
                log.warn("web_extract 响应为空: {}", url);
                return null;
            }
            Document doc = Jsoup.parse(new ByteArrayInputStream(raw), null, url);
            HttpUrl httpUrl = HttpUrl.get(url);
            Article article = new ArticleExtractor(httpUrl, doc)
                    .extractMetadata()
                    .extractContent()
                    .estimateReadingTime()
                    .getArticle();
            String rawTitle = article.getTitle() != null && !article.getTitle().isBlank()
                    ? article.getTitle() : hostOf(url);
            // 复用搜索侧的标题清洗(去话题标签/尾部省略号/站点后缀, 如 "_光明网")
            String title = SearchResultCleaner.cleanTitle(rawTitle);
            String markdown = toMarkdown(article);
            if (markdown.isBlank()) {
                log.warn("web_extract 未能提取到正文(可能是纯 JS 渲染页面): {}", url);
                return null;
            }
            String content = clip(markdown, config.getMaxContentLength());
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("index", index);
            source.put("title", title);
            source.put("url", url);
            source.put("snippet", clip(content, SNIPPET_MAX));
            source.put("content", content);
            log.info("web_extract 成功: url={}, 原标题={}, 正文字符数={}", url, title, content.length());
            return source;
        } catch (Exception e) {
            log.warn("web_extract 抓取失败: url={}, cause={}", url, e.getMessage());
            return null;
        }
    }

    /**
     * 把 crux 提取出的正文 DOM 转成 Markdown。
     * <p>
     * <b>⚠️ 关键坑</b>: crux 4.0 的 {@code Article.document} 是把提取出的正文元素
     * <b>直接挂在 Document 根下</b>(与自动创建的空 {@code <body>} 平级), 因此
     * {@code doc.body().html()} 恒为空 —— 直接取 body 会得到空正文。
     * 必须先重新解析一次(jsoup 会把游离的元素归入 body), 再取其 body 内容。
     * 实测: 不重新解析 → Markdown 长度 0; 重新解析后 → 正常提取。
     */
    private String toMarkdown(Article article) {
        try {
            Document clean = article.getDocument();
            if (clean == null) {
                return "";
            }
            // 1) 重新解析: crux 把正文元素挂在根下(见上方说明), 借 jsoup 归位到 body
            Document normalized = Jsoup.parse(clean.html());
            // 2) DOM 规范化: 去无用标签、展开嵌套 <p>、删空元素
            normalizeDom(normalized);
            String html = normalized.body() != null ? normalized.body().html() : clean.html();
            // 3) 转 Markdown 后再做文本级清洗
            return normalizeMarkdown(mdConverter.convert(html));
        } catch (Exception e) {
            log.warn("正文转 Markdown 失败: {}", e.getMessage());
            return "";
        }
    }

    /** 解析 urls 参数: 兼容数组与单值两种传法, 并限制数量 */
    private List<String> resolveUrls(Map<String, Object> params) {
        List<String> urls = new ArrayList<>();
        Object raw = params.get("urls");
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !o.toString().isBlank()) {
                    urls.add(o.toString().trim());
                }
            }
        } else if (raw instanceof String s && !s.isBlank()) {
            urls.add(s.trim());
        }
        if (urls.size() > config.getMaxExtractUrls()) {
            log.warn("web_extract 传入 {} 个 URL, 超出上限 {}, 仅处理前 {} 个",
                    urls.size(), config.getMaxExtractUrls(), config.getMaxExtractUrls());
            urls = urls.subList(0, config.getMaxExtractUrls());
        }
        return Collections.unmodifiableList(urls);
    }

    /**
     * SSRF 防护: 只允许 http(s), 并拒绝环回 / 私有 / 链路本地(含云元数据 169.254.169.254) 地址。
     * <p>
     * 这里只校验 <b>IP 字面量</b>与明显的内网域名, 不做 DNS 解析 —— 解析会引入网络开销,
     * 且无法根治 DNS Rebinding。如需更强防护, 应在出口代理层做统一管控。
     */
    private boolean isSafeUrl(String url) {
        if (!config.isSsrfGuard()) {
            return true;
        }
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                return false;
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return false;
            }
            String h = host.toLowerCase();
            if ("localhost".equals(h) || h.endsWith(".localhost")
                    || h.endsWith(".local") || h.endsWith(".internal")) {
                return false;
            }
            return !isBlockedIpLiteral(h);
        } catch (Exception e) {
            return false;
        }
    }

    /** 判断是否为应被拦截的内网/环回 IP 字面量(IPv4 与常见 IPv6) */
    private static boolean isBlockedIpLiteral(String host) {
        if (host.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            String[] parts = host.split("\\.");
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            return a == 0 || a == 127 || a == 10
                    || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && b == 168)
                    || (a == 169 && b == 254);
        }
        if (host.contains(":")) {
            return "::1".equals(host) || host.startsWith("fe80:")
                    || host.startsWith("fc") || host.startsWith("fd");
        }
        return false;
    }

    private static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host != null ? host : url;
        } catch (Exception e) {
            return url;
        }
    }

    /**
     * DOM 规范化: crux 的输出常见 {@code <p>} 嵌套与空元素, 会让 Markdown 转换产生大量碎片
     * (实测出现 {@code <p><p>public</p><p>class</p> HelloWorld</p>} 这类把代码块拆散的结构)。
     * <p>处理: 去掉无内容价值的标签 → 展开外层嵌套 {@code <p>} → 删除空元素。
     */
    private static void normalizeDom(Document doc) {
        Element body = doc.body();
        if (body == null) {
            return;
        }
        body.select("script, style, noscript, iframe, svg, form, button, input").remove();
        // <p><p>x</p></p> → <p>x</p>: 展开外层 p(移除其标签但保留子节点)
        body.select("p:has(> p)").forEach(Element::unwrap);
        // 删除纯空白且不含图片的空元素(先收集再删, 避免遍历时修改 DOM)
        body.select("p, div, span, li, td").stream()
                .filter(e -> e.text().isBlank() && e.select("img").isEmpty())
                .toList()
                .forEach(Element::remove);
    }

    /**
     * 网页正文中常见的「列表符号」—— 多来自 {@code <ul><li>} 被提取后的纯文本残留。
     * <p>用 Unicode 码点转义写法而非直接输入字符, 避免源码编码差异导致漏匹配。
     * <p>注意: 注释中不能出现反斜杠加 u 的转义写法 —— Java 的词法预解析对注释同样生效,
     * 非法转义会直接编译失败(本项目已踩过)。
     */
    /**
     * 网页正文里的「列表符号」—— 它们来自 {@code <ul><li>} 被提取后的纯文本残留。
     * <p>刻意<b>不含</b> U+00B7(中文间隔号, 如「迈克尔·乔丹」), 避免误删人名。
     */
    private static final String BULLET_CHARS =
            "\u2022\u2023\u2043\u2219\u25aa\u25ab\u25cf\u25e6\u30fb\uff65";

    /**
     * Markdown 文本清洗:
     * <ol>
     *   <li>去 NBSP</li>
     *   <li>去列表符号残留({@code •} 等非 Markdown 标准符号)</li>
     *   <li>去 Markdown 转义符({@code \[}、{@code \>} 等) —— 对 LLM 而言转义符是纯噪声</li>
     *   <li>去行尾空白、压缩连续空行</li>
     * </ol>
     */
    private static String normalizeMarkdown(String md) {
        if (md == null) {
            return "";
        }
        return stripBullets(md.replace('\u00a0', ' '))
                .replaceAll("\\\\([\\\\`*_{}\\[\\]()#+\\-.!>~|])", "$1")
                .replaceAll("[ \\t]+(\\R)", "$1")
                .replaceAll("\\R{3,}", "\n\n")
                .trim();
    }

    /**
     * 逐个字符删除列表符号及其后随的一个空格。
     * <p>相较「逐行判断行首是否以列表符号开头」的写法, 这里不依赖行首定位、也不依赖
     * 空白判定, 实现更直白可靠(前者曾在实测中静默失效)。
     */
    private static String stripBullets(String md) {
        StringBuilder sb = new StringBuilder(md.length());
        for (int i = 0; i < md.length(); i++) {
            char ch = md.charAt(i);
            if (BULLET_CHARS.indexOf(ch) < 0) {
                sb.append(ch);
                continue;
            }
            // 命中列表符号: 跳过符号本身; 若其后紧跟一个半角空格也一并跳过
            if (i + 1 < md.length() && md.charAt(i + 1) == ' ') {
                i++;
            }
        }
        return sb.toString();
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
