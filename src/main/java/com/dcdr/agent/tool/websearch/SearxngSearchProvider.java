package com.dcdr.agent.tool.websearch;

import com.dcdr.agent.common.util.WebClientFactory;
import com.dcdr.agent.config.ToolsProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SearXNG 搜索策略(V3.3 从 {@code SearxngWebSearchTool} 抽出的检索部分, 行为不变)。
 *
 * <p>后端为自托管的 <a href="https://docs.searxng.org/">SearXNG</a> 元搜索引擎
 * (AGPL-3.0, 无 API Key), 通过其 JSON 接口 {@code GET /search?q=...&format=json} 检索。
 *
 * <p><b>前置条件</b>: SearXNG 的 {@code settings.yml} 必须开启
 * {@code search.formats: [html, json]}, 否则 {@code format=json} 会被拒绝(403)。
 *
 * <p><b>为什么还要保留 {@link SearchResultCleaner}</b>: SearXNG 返回的是搜索引擎的
 * <b>原始结果</b>(不像 DeepSeek / Tavily 已为 LLM 清洗过), 一个中文查询里近一半是
 * 聚合页/列表页/视频页 —— 摘要只是标题堆砌、且无法提取正文。清洗管线是这条链路的
 * 必要组成部分, 不是可选的优化。
 */
@Slf4j
@Component
public class SearxngSearchProvider implements WebSearchProvider {

    public static final String ID = "searxng";

    private final ToolsProperties.WebToolsConfig config;
    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public SearxngSearchProvider(ToolsProperties properties) {
        this.config = properties.getWeb();
        this.webClient = WebClientFactory.build(
                stripTrailingSlash(config.getSearxngUrl()),
                Duration.ofSeconds(config.getTimeoutSeconds()),
                Map.of("User-Agent", config.getUserAgent(),
                        "Accept", "application/json"));
        log.info("SearxngSearchProvider 初始化: searxng={}, language={}, timeout={}s",
                config.getSearxngUrl(), config.getLanguage(), config.getTimeoutSeconds());
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean supportsPagination() {
        return true;
    }

    @Override
    public SearchOutcome search(String query, String originalQuestion, int limit) throws Exception {
        return search(query, originalQuestion, limit, 1);
    }

    @Override
    public SearchOutcome search(String query, String originalQuestion, int limit, int page) throws Exception {
        // originalQuestion 刻意不使用: SearXNG 是元搜索, 没有"理解意图"这一层,
        // 传进去只能拼进 query 反而稀释关键词。它是给 DeepSeek 的 ori_question 预留的形参
        String body = webClient.get()
                .uri(uriBuilder -> {
                    var builder = uriBuilder
                            .path("/search")
                            .queryParam("q", query)
                            .queryParam("format", "json")
                            .queryParam("language", config.getLanguage())
                            .queryParam("safesearch", 0);
                    // 第一页省略 pageno, 保持原有请求不变; 第二页才显式带上。
                    if (page > 1) {
                        builder.queryParam("pageno", page);
                    }
                    return builder.build();
                })
                .retrieve()
                .bodyToMono(String.class)
                .block();
        SearchResultCleaner.CleanResult clean = parse(body, limit);
        log.info("SearXNG 检索: query='{}', page={}, 原始 {} 条 → 保留 {} 条(过滤 {} / 去重 {})",
                query, page, clean.raw(), clean.items().size(), clean.filtered(), clean.duplicated());
        return new SearchOutcome(clean.raw(), clean.filtered(), clean.duplicated(), clean.items());
    }

    /**
     * 解析 SearXNG 响应, 随后交由 {@link SearchResultCleaner} 做
     * 「过滤非文章页 → 清洗 URL/标题/摘要 → 去重」。
     * <p>响应形如 {@code {"results":[{"url","title","content","engine","score",...}]}}。
     */
    private SearchResultCleaner.CleanResult parse(String body, int limit) throws Exception {
        if (body == null || body.isBlank()) {
            return new SearchResultCleaner.CleanResult(0, 0, 0, List.of());
        }
        JsonNode root = mapper.readTree(body);
        JsonNode results = root.path("results");
        if (!results.isArray()) {
            log.warn("SearXNG 响应缺少 results 数组(可能未开启 json 输出格式): 响应前 200 字符={}",
                    body.length() > 200 ? body.substring(0, 200) : body);
            return new SearchResultCleaner.CleanResult(0, 0, 0, List.of());
        }
        List<JsonNode> raw = new ArrayList<>(results.size());
        results.forEach(raw::add);
        sortByEngineScore(raw);
        return SearchResultCleaner.cleanWithReport(raw, limit);
    }

    /**
     * 按 SearXNG 的 {@code score}(引擎加权分) 降序就地重排原始结果(在清洗与截断之前)。
     *
     * <p><b>为什么 SearXNG 需要我们自己排、DeepSeek 不需要</b>: DeepSeek 返回的是自研索引 +
     * 统一 rerank 的产物, 数组顺序即 {@code rerank_score} 降序; 而 SearXNG 是多家引擎结果的
     * <b>聚合</b>, 顺序是引擎之间的混合次序 —— 实测同一查询里 Google 的第 1 条可能排在第 7 位,
     * 前面塞着一堆抓不到正文的聚合页。
     *
     * <p>排序必须发生在 {@link SearchResultCleaner#cleanWithReport} <b>之前</b>: 清洗会按 limit
     * 截断, 顺序不对就会"先把后面的好结果截掉"。
     *
     * <p>用稳定排序: 没给 {@code score} 的引擎结果视为 0 分, 保持它们的原有相对顺序, 不会更糟。
     */
    static void sortByEngineScore(List<JsonNode> results) {
        if (results == null || results.size() < 2) {
            return;
        }
        results.sort((a, b) -> Double.compare(
                b.path("score").asDouble(0), a.path("score").asDouble(0)));
    }

    private String stripTrailingSlash(String url) {
        if (url == null) {
            return null;
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
