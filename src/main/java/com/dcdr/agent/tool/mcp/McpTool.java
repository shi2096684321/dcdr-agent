package com.dcdr.agent.tool.mcp;

import com.dcdr.agent.tool.Tool;
import com.dcdr.agent.tool.ToolInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 将 MCP Server 暴露的某个工具包装成本项目现有的 {@link Tool}。
 * <p>
 * 这样就无需改动 Agent 编排层 / ToolRegistry: MCP 工具与内置工具(Weather/Calculator)走完全相同的抽象,
 * 只是 {@code execute} 内部改为「通过 McpClient 向外部 Server 进程发 JSON-RPC」。
 * </p>
 */
@Slf4j
public class McpTool implements Tool {

    /**
     * 本项目约定的工具名(与 application.yml 的 aliases 对应)
     */
    public static final String NAME_WEB_SEARCH = "web_search";
    public static final String NAME_WEB_EXTRACT = "web_extract";

    private final McpClient client;
    private final McpClient.McpToolSpec spec;
    private final ObjectMapper mapper;
    private final boolean streaming;
    /**
     * 对外注册到 ToolRegistry 的名字(可经 alias 映射, 与实际调用名 spec.name 不同)
     */
    private final String registeredName;

    public McpTool(McpClient client, McpClient.McpToolSpec spec, ObjectMapper mapper, boolean streaming) {
        this(client, spec, mapper, streaming, spec.name);
    }

    public McpTool(McpClient client, McpClient.McpToolSpec spec, ObjectMapper mapper,
                   boolean streaming, String registeredName) {
        this.client = client;
        this.spec = spec;
        this.mapper = mapper;
        this.streaming = streaming;
        this.registeredName = registeredName;
    }

    @Override
    public String getName() {
        return registeredName;
    }

    @Override
    public String getDescription() {
        return spec.description != null ? spec.description : spec.name;
    }

    @Override
    public String getParameterSchema() {
        // spec.inputSchema 已是 JSON Schema 结构; 转成 ToolRegistry 期望的 JSON 字符串
        if (spec.inputSchema == null) {
            return "{\"type\":\"object\",\"properties\":{}}";
        }
        try {
            return mapper.writeValueAsString(spec.inputSchema);
        } catch (Exception e) {
            log.warn("MCP 工具 {} 的 inputSchema 序列化失败", spec.name, e);
            return "{\"type\":\"object\",\"properties\":{}}";
        }
    }

    @Override
    public boolean supportsStreaming() {
        return streaming;
    }

    @Override
    public String execute(Map<String, Object> params) {
        return executeWithInfo(params).getContent();
    }

    @Override
    public ToolInfo executeWithInfo(Map<String, Object> params) {
        try {
            // web_search 自动补全检索参数: 提高深度(advanced 返回更具体的文章链接)与结果数
            if (NAME_WEB_SEARCH.equals(registeredName) && params != null) {
                params.computeIfAbsent("searchDepth", k -> "advanced");
                // 新闻类查询(含「新闻/今日/今天/最新/要闻/动态」等词)设 topic=news,
                // 让 tavily 过滤新闻源并返回 published_date, 更贴近「今日重点新闻」(官方最佳实践)
                Object q = params.get("query");
                String queryText = q != null ? q.toString() : "";
                if (looksLikeNewsQuery(queryText) && params.get("topic") == null) {
                    params.put("topic", "news");
                }
                // 新闻查询时排除聚合门户首页(Google News / Bing News 等): 这些只返回导航页、无法提取正文,
                // 排除后 tavily 更可能返回具体文章链接(官方建议提取前先过滤候选来源)
                if (looksLikeNewsQuery(queryText) && params.get("excludeDomains") == null) {
                    List<String> exclude = new ArrayList<>();
                    exclude.add("news.google.com");
                    exclude.add("news.google.com.hk");
                    exclude.add("news.yahoo.com");
                    exclude.add("bing.com");
                    params.put("excludeDomains", exclude);
                }
                // maxResults 默认 10(新闻查询需更多候选以抵消过滤损耗), 上限 10 避免过长
                Object mr = params.get("maxResults");
                int n = 8;
                if (mr instanceof Number) n = ((Number) mr).intValue();
                else if (mr instanceof String) {
                    try {
                        n = Integer.parseInt(((String) mr).trim());
                    } catch (Exception ignored) {
                    }
                }
                n = Math.max(3, Math.min(8, n));
                params.put("maxResults", n);
            }
            log.debug("param:{}", params);
            McpClient.McpCallResult result = client.callTool(spec.name, params);
            if (result.isError) {
                return ToolInfo.text("[" + spec.name + "] 执行出错: " + result.text);
            }
            // === 便于排查解析错位: 始终打印一次检索的原始 result.text 全文 ===
            log.info("===== MCP RAW RESULT BEGIN [{}] =====\n{}\n===== MCP RAW RESULT END [{}] =====",
                    spec.name, result.text, spec.name);
            if (log.isDebugEnabled()) {
                log.debug("MCP structured blocks: {}", result.structured);
            }
            // 归一化: 把 Server 原始文本/resource 拆成结构化来源, 并生成喂给模型的精简内容
            // extract 模式把传入的第一个 url 作为来源兜底(extract 文本本身不含链接)
            List<String> fallbackUrls = new ArrayList<>();
            if (NAME_WEB_EXTRACT.equals(registeredName) && params != null) {
                Object urls = params.get("urls");
                if (urls instanceof List) {
                    for (Object u : (List<?>) urls) {
                        if (u != null) fallbackUrls.add(u.toString());
                    }
                } else if (urls instanceof String) {
                    fallbackUrls.add(urls.toString());
                }
            }
            SearchResultBundle bundle = normalize(result, fallbackUrls);
            return new ToolInfo(bundle.compactContent, bundle.sources.isEmpty() ? null : bundle.sources);
        } catch (Exception e) {
            log.error("MCP 工具 {} 调用失败", spec.name, e);
            return ToolInfo.text("[" + spec.name + "] 调用失败: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractSources(McpClient.McpCallResult result) {
        List<Map<String, Object>> sources = new java.util.ArrayList<>();
        for (Map<String, Object> res : result.structured) {
            Object uri = res.get("uri");
            Object text = res.get("text");
            if (uri != null || text != null) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("title", res.get("name") != null ? res.get("name").toString() : (uri != null ? uri.toString() : "来源"));
                s.put("url", uri != null ? uri.toString() : "");
                s.put("content", text != null ? text.toString() : "");
                sources.add(s);
            }
        }
        return sources;
    }

    /**
     * 归一化结果: 从 Server 原始输出中抽取结构化来源, 并生成喂给模型的精简内容。
     * <p>
     * 这是 Host 侧对 MCP 原始数据的标准适配层 —— Server 只保证返回内容, 不保证格式好看,
     * 因此 Host 需要把整段原文拆成 title/url/snippet 的来源卡片, 并只给模型紧凑摘要。
     * </p>
     */
    private SearchResultBundle normalize(McpClient.McpCallResult result, List<String> fallbackUrls) {
        List<Map<String, Object>> sources = new ArrayList<>();
        // 1) 优先从结构化 resource 块抽取(uri/text 形式)
        for (Map<String, Object> res : result.structured) {
            Object uri = res.get("uri");
            Object text = res.get("text");
            String title = res.get("name") != null ? res.get("name").toString()
                    : (uri != null ? uri.toString() : "来源");
            String url = uri != null ? uri.toString() : "";
            String snippet = text != null ? clip(text.toString(), SNIPPET_MAX) : "";
            if (uri != null || text != null) {
                sources.add(buildSource(sources.size() + 1, title, url, snippet));
            }
        }
        // 2) 若结构化块为空, 解析纯文本。tavily-mcp 实际返回的是
        //    「一个站点聚合多条 ### 标题新闻」的格式, 没有 Content: 字段,
        //    因此优先按 markdown 标题切分每条独立新闻, 失败再退回 Title: 块切分。
        if (sources.isEmpty() && result.text != null && !result.text.isBlank()) {
            List<Map<String, Object>> byHeadings = parseByHeadings(result.text);
            if (!byHeadings.isEmpty()) {
                sources.addAll(byHeadings);
            } else {
                sources.addAll(parseTextResults(result.text));
            }
        }
        // 2.5) extract 模式: 把整页完整正文作为 content 挂到来源, 供编排层串联合并
        //      (extract 返回的是单页原始正文, normalize 默认只生成 snippet, 不填 content,
        //       导致上游 enrichWithExtract 永远拿不到 content、正文打印/串联失效)
        if (NAME_WEB_EXTRACT.equals(registeredName)) {
            String full = cleanNoise(result.text != null ? result.text : "");
            if (sources.isEmpty() && !fallbackUrls.isEmpty()) {
                // 没拆出任何来源时, 用传入 url + 完整正文构造一个来源
                String url = fallbackUrls.get(0);
                String title = hostOf(url);
                Map<String, Object> s = buildSource(1, title, url, clip(full, SNIPPET_MAX));
                s.put("content", full);
                sources.add(s);
            } else {
                // 已拆出多个碎片来源时, 把整页正文作为 content 挂到第一个来源(最具引用价值),
                // 其余来源保留 snippet 供前端卡片展示
                sources.get(0).put("content", full);
            }
        }
        // 限条数
        if (sources.size() > MAX_SOURCES) {
            sources = new ArrayList<>(sources.subList(0, MAX_SOURCES));
        }
        // 3) 生成喂给模型的精简内容: 只给标题+URL+摘要, 不塞全文
        String compact = buildCompactContent(sources);
        return new SearchResultBundle(compact, sources);
    }

    /**
     * 主解析路径: 按 markdown 标题(### / ##)切分每条独立新闻。
     * tavily 把「一个站点的多个新闻」压在一个块里, 真正的可引用单元是每条 ### 标题新闻。
     * 每个站点块顶部的 Title:/URL: 作为该组新闻的公用来源地址。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseByHeadings(String text) {
        List<Map<String, Object>> out = new ArrayList<>();
        // 先按 "Title: xxx\nURL: yyy" 提取每个站点块的 URL(后续同块新闻共用)
        String[] siteBlocks = text.split("(?m)^\\s*(Title|标题)\\s*[:：]");
        String currentUrl = "";
        for (int i = 1; i < siteBlocks.length; i++) {
            String siteBlock = siteBlocks[i];
            // 站点名(块首行)作分组标题; 站点 URL 通常在首行之后的 "URL:" 行
            String siteName = siteBlock.split("\\R", 2)[0].trim();
            for (String line : siteBlock.split("\\R")) {
                String t = line.trim();
                if (t.toLowerCase().startsWith("url") && t.contains(":")) {
                    String u = t.substring(t.indexOf(':') + 1).trim();
                    if (looksLikeUrl(u)) currentUrl = u;
                }
            }
            // 在该站点块内按 markdown 标题切分每条新闻
            String[] items = siteBlock.split("(?m)^\\s*(#{2,3})\\s+");
            for (int j = 1; j < items.length; j++) {
                String item = items[j];
                // 去掉首个 # 标记后的残留标题文本在 split 时已分离, item 即标题+正文
                String[] parts = item.split("\\R", 2);
                String title = cleanHeading(parts[0].trim());
                if (title.isBlank()) continue;
                String body = parts.length > 1 ? parts[1] : "";
                String snippet = clip(cleanNoise(body), SNIPPET_MAX);
                String url = currentUrl;
                // 若新闻正文里出现更具体的链接(极少数), 优先用它
                String inlineUrl = extractFirstUrl(body);
                if (inlineUrl != null) url = inlineUrl;
                out.add(buildSource(out.size() + 1, title, url, snippet));
            }
        }
        return out;
    }

    /**
     * 兜底解析: 按 Title: 块切分(无 markdown 标题时)
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseTextResults(String text) {
        List<Map<String, Object>> out = new ArrayList<>();
        String[] blocks = text.split("(?m)^\\s*(Title|标题)\\s*[:：]", Pattern.MULTILINE);
        for (int i = 1; i < blocks.length; i++) {
            String block = blocks[i];
            String title = null, url = "", snippet = "";
            for (String line : block.split("\\R")) {
                String t = line.trim();
                if (t.isEmpty()) continue;
                if (t.toLowerCase().startsWith("url") && t.contains(":")) {
                    String u = t.substring(t.indexOf(':') + 1).trim();
                    if (looksLikeUrl(u)) url = u;
                } else if (title == null) {
                    title = t;
                }
            }
            if (title != null && !title.isBlank()) {
                out.add(buildSource(out.size() + 1, title, url, clip(cleanNoise(block), SNIPPET_MAX)));
            }
        }
        return out;
    }

    // ——— 文本清洗/工具 ——

    /**
     * 清理 tavily 正文噪声: 省略标记、图片占位、纯署名行、无意义「更多」
     */
    private static String cleanNoise(String s) {
        if (s == null) return "";
        String[] lines = s.split("\\R");
        StringBuilder sb = new StringBuilder();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("[...") || line.startsWith("Image") || line.startsWith("图")) continue;
            if (line.equals("更多") || line.startsWith("展开") || line.equals("登录")) continue;
            // 去掉行内 [...]
            line = line.replaceAll("\\[\\.\\.\\.\\]", "").trim();
            if (line.isEmpty()) continue;
            sb.append(line).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * 去掉标题里残留的 markdown 符号/署名
     */
    private static String cleanHeading(String s) {
        if (s == null) return "";
        return s.replaceAll("#+", "").replaceAll("\\[\\.\\.\\.\\]", "").trim();
    }

    private static boolean looksLikeUrl(String s) {
        return s != null && (s.startsWith("http://") || s.startsWith("https://"));
    }

    /**
     * 启发式判断是否为新闻类查询: 含新闻/今日/最新/要闻等时效词时返回 true, 触发 topic=news
     */
    private static boolean looksLikeNewsQuery(String q) {
        if (q == null) return false;
        String s = q.toLowerCase();
        return s.contains("新闻") || s.contains("今日") || s.contains("今天") || s.contains("昨天")
                || s.contains("最新") || s.contains("要闻") || s.contains("动态") || s.contains("周报")
                || s.contains("日报") || s.contains("早报") || s.contains("晚报") || s.contains("本周")
                || s.contains("头条") || s.contains("快讯") || s.matches(".*(news|today|latest|headline).*");
    }

    /**
     * 从文本里抓第一个 http(s) 链接, 无则返回 null
     */
    private static String extractFirstUrl(String s) {
        if (s == null) return null;
        java.util.regex.Matcher m = Pattern.compile("https?://[^\\s)]]+").matcher(s);
        return m.find() ? m.group().replaceAll("[\\]）]$", "") : null;
    }

    /**
     * 取 URL 的 host 作为来源标题兜底
     */
    private static String hostOf(String url) {
        if (url == null) return "来源";
        try {
            String u = url.startsWith("http") ? url : "https://" + url;
            String host = java.net.URI.create(u).getHost();
            return host != null ? host : url;
        } catch (Exception e) {
            return url;
        }
    }

    private Map<String, Object> buildSource(int index, String title, String url, String snippet) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("index", index);
        s.put("title", title);
        s.put("url", url);
        s.put("snippet", snippet);
        return s;
    }

    /**
     * 生成喂给模型的精简内容。
     * 带上 {@code [[n]]} 引用标记(模型可在正文中穿插引用), 末尾附结构化来源摘要,
     * 形成「正文引用 + 来源列表」的层级结构 —— 与 deepseek 等联网回答的呈现一致。
     */
    private String buildCompactContent(List<Map<String, Object>> sources) {
        if (sources.isEmpty()) return "（联网搜索未返回可用结果）";
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
            Object sn = s.get("snippet");
            if (sn != null && !sn.toString().isBlank()) {
                sb.append("\n    ").append(sn);
            }
            sb.append("\n");
            i++;
        }
        return sb.toString();
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /**
     * 归一化产物: 喂给模型的精简内容 + 给前端的来源卡片
     */
    private static class SearchResultBundle {
        final String compactContent;
        final List<Map<String, Object>> sources;

        SearchResultBundle(String compactContent, List<Map<String, Object>> sources) {
            this.compactContent = compactContent;
            this.sources = sources;
        }
    }

    private static final int SNIPPET_MAX = 200;
    private static final int MAX_SOURCES = 12;
}
