package com.dcdr.agent.tool.websearch;

import com.dcdr.agent.common.util.WebClientFactory;
import com.dcdr.agent.config.ToolsProperties;
import com.dcdr.agent.tool.AbstractTool;
import com.dcdr.agent.tool.ToolInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 自建联网搜索工具(V3.2, 替代第三方 Tavily)。
 * <p>
 * 后端为自托管的 <a href="https://docs.searxng.org/">SearXNG</a> 元搜索引擎(AGPL-3.0, 无 API Key),
 * 通过其 JSON 接口 {@code GET /search?q=...&format=json} 检索。
 * <p>
 * <b>前置条件</b>: SearXNG 的 {@code settings.yml} 必须开启 {@code search.formats: [html, json]},
 * 否则 {@code format=json} 会被拒绝(403)。
 * <p>
 * <b>与编排层的契约</b>(不可变更, 否则 {@code AgentOrchestrator} 的引用链路失效):
 * <ul>
 *   <li>工具名必须为 {@code web_search} —— 与原先 MCP(tavily-search) 的 alias 一致,
 *       编排层对 {@code "web_search".equals(name)} 有硬编码特判(触发正文串联抓取)</li>
 *   <li>{@link #executeWithInfo} 必须返回 sources, 每条形如
 *       {@code {index, title, url, snippet}}; 完整正文由 {@code web_extract} 二次补全 {@code content}</li>
 * </ul>
 */
@Slf4j
@Component
public class SearxngWebSearchTool extends AbstractTool {

    /** 工具名: 与原先 MCP(tavily-search) 的 alias 保持一致, 编排层零改动 */
    public static final String NAME = "web_search";

    /** 送入模型的来源条数上限 */
    private static final int MAX_SOURCES = 12;

    private final ToolsProperties.WebToolsConfig config;
    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public SearxngWebSearchTool(ToolsProperties properties) {
        this.config = properties.getWeb();
        this.webClient = WebClientFactory.build(
                stripTrailingSlash(config.getSearxngUrl()),
                Duration.ofSeconds(config.getTimeoutSeconds()),
                Map.of("User-Agent", config.getUserAgent(),
                        "Accept", "application/json"));
        log.info("SearxngWebSearchTool 初始化: searxng={}, language={}, timeout={}s, enabled={}",
                config.getSearxngUrl(), config.getLanguage(), config.getTimeoutSeconds(), config.isEnabled());
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "联网搜索工具：通过自建 SearXNG 元搜索引擎检索互联网实时信息。\n"
                + "\n"
                + "【何时调用】\n"
                + "- 用户询问时效性信息：最新新闻、实时数据、股价、天气、赛事、版本发布\n"
                + "- 需要引用外部来源、或你的知识可能已过时\n"
                + "- 用户明确要求「联网查一下」「搜一下」「帮我查最新」\n"
                + "\n"
                + "【何时不要调用】\n"
                + "- 通用常识、数学计算、写代码、翻译、润色\n"
                + "- 用户已提供足够上下文的问题\n"
                + "\n"
                + "【query 构造建议】\n"
                + "- 传完整自然语言查询，而非零散关键词堆叠\n"
                + "- 时效类查询带上时间词（如「2026年10月」「最新」）能显著提升命中率\n"
                + "- 复杂问题可拆成多次聚焦查询，而非一次塞入多个主题\n"
                + "\n"
                + "【返回内容】\n"
                + "- 返回若干条检索结果（标题 / 链接 / 摘要）\n"
                + "- 系统会自动对其中最有价值的文章抓取正文，无需重复搜索";
    }

    @Override
    public JsonObjectSchema getParameters() {
        return JsonObjectSchema.builder()
                .addProperty("query", JsonStringSchema.builder()
                        .description("搜索查询词，推荐使用完整自然语言表述").build())
                .addProperty("maxResults", JsonIntegerSchema.builder()
                        .description("期望返回的结果条数，默认 8，范围 3~" + config.getMaxResultsLimit()).build())
                .required(List.of("query"))
                .build();
    }

    @Override
    public boolean supportsStreaming() {
        // 暴露给流式对话(chat 路径), 使模型在对话中可自主调用
        return config.isEnabled();
    }

    @Override
    public String execute(Map<String, Object> params) {
        return executeWithInfo(params).getContent();
    }

    @Override
    public ToolInfo executeWithInfo(Map<String, Object> params) {
        if (!config.isEnabled()) {
            return ToolInfo.text("联网搜索未启用");
        }
        String query = getString(params, "query");
        if (query == null || query.isBlank()) {
            return ToolInfo.text("请提供搜索关键词");
        }
        int limit = resolveLimit(params);
        try {
            String body = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/search")
                            .queryParam("q", query)
                            .queryParam("format", "json")
                            .queryParam("language", config.getLanguage())
                            .queryParam("safesearch", 0)
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            List<Map<String, Object>> sources = parseSources(body, limit);
            if (sources.isEmpty()) {
                log.warn("SearXNG 未返回可用结果: query='{}'", query);
                return ToolInfo.text("（联网搜索未返回可用结果）");
            }
            log.info("SearXNG 检索: query='{}', 返回 {} 条", query, sources.size());
            return new ToolInfo(buildCompactContent(sources), sources);
        } catch (Exception e) {
            // 不抛异常: 工具失败不应中断整个对话, 由模型基于已有信息继续回答
            log.error("SearXNG 检索失败: query='{}', cause={}", query, e.getMessage(), e);
            return ToolInfo.text("联网搜索失败: " + e.getMessage()
                    + "（请检查 SearXNG 服务是否可用, 以及 settings.yml 是否开启 json 格式）");
        }
    }

    /** 解析 maxResults 参数并钳制到 [3, maxResultsLimit] */
    private int resolveLimit(Map<String, Object> params) {
        int n = config.getMaxResults();
        Object raw = params.get("maxResults");
        if (raw instanceof Number num) {
            n = num.intValue();
        } else if (raw instanceof String s) {
            try {
                n = Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                // 保留默认值
            }
        }
        return Math.max(3, Math.min(config.getMaxResultsLimit(), n));
    }

    /**
     * 解析 SearXNG 响应为来源卡片, 随后交由 {@link SearchResultCleaner} 做
     * 「过滤非文章页 → 清洗 URL/标题/摘要 → 去重」。
     * <p>响应形如 {@code {"results":[{"url","title","content","engine","score",...}]}}。
     */
    private List<Map<String, Object>> parseSources(String body, int limit) throws Exception {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        JsonNode root = mapper.readTree(body);
        JsonNode results = root.path("results");
        if (!results.isArray()) {
            log.warn("SearXNG 响应缺少 results 数组(可能未开启 json 输出格式): 响应前 200 字符={}",
                    body.length() > 200 ? body.substring(0, 200) : body);
            return List.of();
        }
        List<JsonNode> raw = new ArrayList<>(results.size());
        results.forEach(raw::add);
        return SearchResultCleaner.clean(raw, Math.min(limit, MAX_SOURCES));
    }

    /**
     * 生成喂给模型的精简内容: 与原先 McpTool 的 compact 格式保持一致
     * (带 {@code [[n]]} 引用标记, 供模型在正文中穿插引用)。
     */
    private String buildCompactContent(List<Map<String, Object>> sources) {
        StringBuilder sb = new StringBuilder();
        sb.append("以下是联网检索到的 ").append(sources.size())
                .append(" 条资料，请在回答中通过 [[n]] 标注引用来源（n 为下方序号），并在结尾列出引用：\n\n");
        int i = 1;
        for (Map<String, Object> s : sources) {
            sb.append("[[").append(i).append("]] ").append(s.get("title"));
            Object url = s.get("url");
            if (url != null && !url.toString().isBlank()) {
                sb.append("  (").append(url).append(")");
            }
            Object snippet = s.get("snippet");
            if (snippet != null && !snippet.toString().isBlank()) {
                sb.append("\n    ").append(snippet);
            }
            sb.append("\n");
            i++;
        }
        return sb.toString();
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) {
            return null;
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

}
