package com.dcdr.agent.tool.websearch;

import com.dcdr.agent.config.ToolsProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DeepSeek 自研搜索策略(V3.3)。
 *
 * <p>接口: {@code POST /v1/tools/search}(纯 JSON, 与模型接口同一个 Base URL、同一套 Bearer 鉴权)。
 * 文档见 <a href="https://api-docs.deepseek.com/zh-cn/api/search/">网页搜索</a>。
 *
 * <p><b>与 SearXNG 的关键差异, 以及本类为什么不需要 {@link SearchResultCleaner} 那条过滤管线</b>:
 * <ul>
 *   <li>结果是自研索引 + 统一 rerank 的产物, 已过滤 SEO 作弊与低质 AIGC。
 *       但<b>这不等于"没有聚合页"</b>: 实测同一个查询的 top3 里仍会出现作者页、分页栏目页
 *       ({@code /blog/page-36} 这种)。区别在于这些是<b>真实内容页</b>, 不是 SearXNG 那种
 *       "摘要里堆着十条标题"的空壳 —— 前者最多抓不到正文(编排层有失败计数与摘要降级),
 *       后者会直接污染模型上下文。所以编排层的 {@code isSpecificArticleUrl} 过滤仍然必要;</li>
 *   <li>每条带 {@code rerank_score}(0~3) 与 {@code authority}(0~4), 想收紧质量用<b>阈值</b>
 *       即可, 不必靠正则猜"这个 URL 像不像文章页" —— 实测 {@code /blog/page-36} 这类路径
 *       既不含 {@code list/tag} 关键词、也不是单层短路径, 正则管线<b>拦不住它</b>,
 *       硬套只会误伤真实文章;</li>
 *   <li>{@code summary} 有 600~800 字符, 是<b>专为模型阅读</b>设计的长摘要 ——
 *       这正是 SearXNG 最缺的东西(它只有几十到两百字的引擎摘要)。</li>
 * </ul>
 * 因此这里只做三件事: 阈值过滤、URL 去重、字段映射。标题/摘要的清洗仍复用
 * {@link SearchResultCleaner} 的公开方法 —— 那部分是"文本长什么样"的问题, 与来源无关。
 *
 * <p><b>为什么 {@code snippet} 与 {@code summary} 要分别存放</b>: 前端来源卡片展示短摘要,
 * 模型阅读要长摘要。合并成一个字段必然牺牲其中一端; 门面拼提示词时优先取 {@code summary},
 * 缺失才回落 {@code snippet}, 于是 SearXNG 路径(只有 snippet)行为不变。
 */
@Slf4j
@Component
public class DeepseekSearchProvider implements WebSearchProvider {

    public static final String ID = "deepseek";

    /** 前端来源卡片用的短摘要上限(与 SearXNG 路径口径一致) */
    private static final int SNIPPET_MAX = 200;

    /** 喂模型的长摘要上限; 官方口径 600~800, 留一点余量防个别结果超标 */
    private static final int SUMMARY_MAX = 1000;

    /** query / ori_question 的字符上限(接口硬限制) */
    private static final int QUERY_MAX = 128;

    private final ToolsProperties.WebToolsConfig.DeepseekConfig config;
    private final ToolsProperties.WebToolsConfig webConfig;
    private final DeepseekApiClient apiClient;

    public DeepseekSearchProvider(ToolsProperties properties, DeepseekApiClient apiClient) {
        this.webConfig = properties.getWeb();
        this.config = webConfig.getDeepseek();
        this.apiClient = apiClient;
        log.info("DeepseekSearchProvider 初始化: needContent={}, minRerankScore={}, minAuthority={}",
                config.isNeedContent(), config.getMinRerankScore(), config.getMinAuthority());
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
    public SearchOutcome search(String query, String originalQuestion, int limit) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", query);
        // count 不影响计费单价(按请求次数计), 但会撑大上下文, 故仍受门面传入的 limit 约束
        payload.put("count", limit);
        payload.put("need_content", config.isNeedContent());
        if (originalQuestion != null && !originalQuestion.isBlank()) {
            // ori_question 帮助引擎理解意图(如"它多少钱"里的"它"指代上文实体)
            payload.put("ori_question", clip(originalQuestion, QUERY_MAX));
        }
        if (config.getLocalization() != null && !config.getLocalization().isBlank()) {
            payload.put("localization", config.getLocalization());
        }

        JsonNode root = apiClient.post(DeepseekApiClient.PATH_SEARCH, payload, "联网搜索");
        JsonNode results = root.path("results");
        if (!results.isArray()) {
            // 正常响应一定带 results(空结果也是空数组)。不是数组说明协议变了或被中间层改写,
            // 此时必须报错而不是当成"没搜到" —— 后者在线上只表现为"搜索功能突然变差"
            throw new IOException("DeepSeek 搜索响应缺少 results 数组");
        }
        return mapResults(results, limit);
    }

    /** 把 DeepSeek 的 results 映射成统一 sources, 顺带做阈值过滤与去重 */
    private SearchOutcome mapResults(JsonNode results, int limit) {
        int raw = results.size();
        int filtered = 0;
        int duplicated = 0;
        List<Map<String, Object>> sources = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (JsonNode node : results) {
            if (sources.size() >= limit) {
                break;
            }
            double score = node.path("rerank_score").asDouble(0);
            int authority = node.path("authority").asInt(-1);
            // -1(未知权威性) 在 minAuthority >= 0 时会被一并丢弃: 这是刻意的 ——
            // "只要权威来源"就该连未知的一起排除, 否则阈值形同虚设
            if (score < config.getMinRerankScore() || authority < config.getMinAuthority()) {
                filtered++;
                continue;
            }
            String url = SearchResultCleaner.cleanUrl(node.path("url").asText(""));
            if (url.isBlank() || !seen.add(url)) {
                duplicated++;
                continue;
            }
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("index", sources.size() + 1);
            source.put("title", SearchResultCleaner.cleanTitle(node.path("title").asText("")));
            source.put("url", url);
            source.put("snippet", clip(SearchResultCleaner.cleanSnippet(
                    node.path("snippet").asText("")), SNIPPET_MAX));
            String summary = clip(SearchResultCleaner.cleanSnippet(
                    node.path("summary").asText("")), SUMMARY_MAX);
            if (!summary.isBlank()) {
                source.put("summary", summary);
            }
            if (config.isNeedContent()) {
                String content = clip(node.path("content").asText(""), webConfig.getMaxContentLength());
                if (!content.isBlank()) {
                    // 编排层对已有 content 的来源会跳过串联抓取, 见 enrichWithExtract 的说明
                    source.put("content", content);
                }
            }
            // authority 留着供排查"这条为什么被留下/丢弃", 前端不认也无害
            source.put("authority", authority);
            sources.add(source);
        }

        log.info("DeepSeek 检索: 原始 {} 条 → 保留 {} 条(阈值过滤 {} / 去重 {})",
                raw, sources.size(), filtered, duplicated);
        return new SearchOutcome(raw, filtered, duplicated, sources);
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
