package com.dcdr.agent.tool.websearch;

import com.dcdr.agent.config.ToolsProperties;
import com.dcdr.agent.tool.AbstractTool;
import com.dcdr.agent.tool.ToolInfo;
import com.dcdr.agent.tool.WebStat;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网页正文抓取工具(门面, V3.3 起底层可切换 本地 crux / DeepSeek Fetch)。
 *
 * <p><b>本类现在只剩"与抓取源无关"的职责</b>: 解析并约束 URL 列表、逐条调用抓取策略、
 * 组装 sources 与 {@link WebStat}。真正的正文提取已移到 {@link HtmlArticleExtractor}
 * (本地链路) 与 {@link DeepseekFetchProvider}(DeepSeek 链路)。
 *
 * <p><b>与编排层的契约</b>(不可变更):
 * <ul>
 *   <li>工具名必须为 {@code web_extract} —— 编排层对 {@code "web_extract"} 有硬编码特判;</li>
 *   <li>参数名必须为 {@code urls}(数组或单值) 与可选 {@code query} ——
 *       {@code AgentOrchestrator#enrichWithExtract} 按此名传参;</li>
 *   <li><b>完整正文必须放在 sources[0].content</b> —— 编排层正是从这里取全文,
 *       再经 {@code cleanMarkdownContent} 清洗后写回搜索来源。</li>
 * </ul>
 *
 * <p><b>能力边界</b>: 无论哪个源都不执行 JavaScript, 纯 SPA 站点(内容由 JS 渲染)
 * 取不到正文, 此时返回空 content, 上游会优雅降级为使用搜索摘要。
 */
@Slf4j
@Component
public class WebExtractTool extends AbstractTool {

    /** 工具名: 与原先 MCP(tavily-extract) 的 alias 保持一致, 编排层零改动 */
    public static final String NAME = "web_extract";

    /** snippet 最大长度 */
    private static final int SNIPPET_MAX = 300;

    private final ToolsProperties.WebToolsConfig config;
    private final WebProviderFactory providerFactory;

    public WebExtractTool(ToolsProperties properties, WebProviderFactory providerFactory) {
        this.config = properties.getWeb();
        this.providerFactory = providerFactory;
        log.info("WebExtractTool 初始化: enabled={}, fetchProvider={}",
                config.isEnabled(), providerFactory.fetchProvider().id());
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
    public boolean untrustedOutput() {
        // 抓到的是网页正文, 完全由第三方控制; 这是"读了就照着做"风险最高的一个工具
        return true;
    }

    @Override
    public String execute(Map<String, Object> params) {
        return executeWithInfo(params).getContent();
    }

    @Override
    public ToolInfo executeWithInfo(Map<String, Object> params) {
        if (!config.isEnabled()) {
            return ToolInfo.text("网页抓取未启用")
                    .withStat(WebStat.unavailable(NAME, "none", null, WebStat.Empty.DISABLED));
        }
        List<String> urls = resolveUrls(params);
        if (urls.isEmpty()) {
            return ToolInfo.text("请提供要抓取的 URL");
        }
        String query = getString(params, "query");

        List<Map<String, Object>> sources = new ArrayList<>();
        StringBuilder compact = new StringBuilder();
        int ok = 0, failed = 0, contentChars = 0, thin = 0;
        for (String url : urls) {
            Map<String, Object> source = extractOne(url, query, sources.size() + 1);
            if (source != null) {
                sources.add(source);
                ok++;
                // 正文长度与"薄页"在这里一并统计: 只报"抓取成功 N 篇"会掩盖
                // "抓到的其实是登录页/验证码页"(正文几十个字, 模型拿到的等于废话)
                String content = String.valueOf(source.get("content"));
                contentChars += content.length();
                if (content.length() < WebStat.THIN_CONTENT_CHARS) {
                    thin++;
                }
            } else {
                failed++;
            }
        }
        String firstUrl = urls.get(0);

        if (sources.isEmpty()) {
            return ToolInfo.text("网页抓取失败: 未能从 " + urls.size() + " 个 URL 中提取到正文"
                            + "（可能是纯 JS 渲染页面、需要登录、或已阻止抓取）")
                    .withStat(WebStat.extract(providerFactory.fetchProvider().id(), firstUrl, 0, failed, 0, 0));
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
        return new ToolInfo(compact.toString(), sources,
                WebStat.extract(providerFactory.fetchProvider().id(), firstUrl, ok, failed, contentChars, thin));
    }

    /**
     * 通过抓取策略取回单个 URL 的正文。
     * <p>SSRF 校验在 Provider 内部完成(本地与 DeepSeek 两条路都要过同一道闸), 这里不重复判断。
     *
     * @return 来源卡片({@code {index,title,url,snippet,content}}); 失败返回 null
     */
    private Map<String, Object> extractOne(String url, String query, int index) {
        FetchedPage page = providerFactory.fetchProvider().fetch(url, query);
        if (page == null || page.isEmpty()) {
            return null;
        }
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("index", index);
        source.put("title", page.title() == null || page.title().isBlank()
                ? hostOf(url) : page.title());
        source.put("url", url);
        source.put("snippet", HtmlArticleExtractor.clip(page.content(), SNIPPET_MAX));
        source.put("content", page.content());
        return source;
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

    private static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host != null ? host : url;
        } catch (Exception e) {
            return url;
        }
    }

    // ===================== 测试入口(兼容既有 fixture 用例) =====================

    /**
     * 一次正文抽取的结果。
     *
     * @param title   已清洗的标题(去站点后缀 / 话题标签)
     * @param content 已裁剪到上限的 Markdown 正文
     */
    record Extracted(String title, String content) {
    }

    /**
     * 从<b>已拿到的 HTML 字节</b>中抽取正文 —— 不涉及任何网络。
     *
     * <p>实现已迁到 {@link HtmlArticleExtractor}, 这里保留同名入口是因为
     * {@code WebExtractFixtureTest} 那批 fixture 用例是这条链路<b>唯一的回归防线</b>
     * (crux 的 body 为空、中文站 GBK、SPA 空壳页, 任何一处退化都只表现为"抓不到内容",
     * 与"站点拒绝抓取"在外部无法区分)。改测试入口等于让这批用例失效, 不划算。
     */
    static Extracted extractFromHtml(byte[] raw, String url, int maxChars) {
        FetchedPage page = HtmlArticleExtractor.extract(raw, url, maxChars);
        return page == null ? null : new Extracted(page.title(), page.content());
    }
}
