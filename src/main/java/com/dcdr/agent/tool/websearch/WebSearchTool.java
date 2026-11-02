package com.dcdr.agent.tool.websearch;

import com.dcdr.agent.config.ToolsProperties;
import com.dcdr.agent.tool.AbstractTool;
import com.dcdr.agent.tool.ToolInfo;
import com.dcdr.agent.tool.WebStat;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 联网搜索工具(门面, V3.3 起底层可切换 SearXNG / DeepSeek)。
 *
 * <p><b>改名说明</b>: 原名 {@code SearxngWebSearchTool}, 名字里写死了一个具体后端。
 * 现在后端是配置项, 再把类名绑在 SearXNG 上会持续误导后来的人(以为换源要改这个类)。
 * 实际职责只剩三件<b>与搜索源无关</b>的事: 参数钳制、拼 {@code [[n]]} 引用文本、上报 {@link WebStat}。
 *
 * <p><b>与编排层的契约</b>(不可变更, 否则 {@code AgentOrchestrator} 的引用链路失效):
 * <ul>
 *   <li>工具名必须为 {@code web_search} —— 编排层对 {@code "web_search".equals(name)}
 *       有硬编码特判(触发正文串联抓取);</li>
 *   <li>返回的 sources 每条形如 {@code {index, title, url, snippet}}(可能带
 *       {@code summary} / {@code content} / {@code authority}); 完整正文由
 *       {@code web_extract} 二次补全 {@code content}。</li>
 * </ul>
 */
@Slf4j
@Component
public class WebSearchTool extends AbstractTool {

    /** 工具名: 与原先 MCP(tavily-search) 的 alias 保持一致, 编排层零改动 */
    public static final String NAME = "web_search";

    private final ToolsProperties.WebToolsConfig config;
    private final WebProviderFactory providerFactory;

    public WebSearchTool(ToolsProperties properties, WebProviderFactory providerFactory) {
        this.config = properties.getWeb();
        this.providerFactory = providerFactory;
        log.info("WebSearchTool 初始化: enabled={}, provider={}",
                config.isEnabled(), providerFactory.searchProvider().id());
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        // 描述刻意不写"通过 SearXNG 检索": 后端是可配置的, 写死具体引擎会让模型
        // 在回答里复述一个它根本无从验证的实现细节
        return "联网搜索工具：检索互联网实时信息。\n"
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
    public boolean untrustedOutput() {
        // 标题与摘要由各站点作者写, 是提示注入最常见的载体("忽略以上指令"就藏在摘要里)。
        // 换成 DeepSeek 也<b>一样</b>: 它的 summary 同样是第三方内容的转述
        return true;
    }

    @Override
    public String execute(Map<String, Object> params) {
        return executeWithInfo(params).getContent();
    }

    @Override
    public ToolInfo executeWithInfo(Map<String, Object> params) {
        if (!config.isEnabled()) {
            return ToolInfo.text("联网搜索未启用")
                    .withStat(WebStat.searchFailed("none", null, WebStat.Empty.DISABLED));
        }
        String query = getString(params, "query");
        if (query == null || query.isBlank()) {
            return ToolInfo.text("请提供搜索关键词");
        }
        int limit = resolveLimit(params);
        WebSearchProvider provider = providerFactory.searchProvider();
        try {
            // originalQuestion 传 null: 工具是无状态的(Tool#execute 只有 Map),
            // 拿不到会话里的用户原话。DeepSeek 的 ori_question 因此暂时不启用 ——
            // 它只影响意图理解, 不影响检索正确性; 若将来确有需要, 应在编排层透传而非在工具里查库
            SearchOutcome outcome = provider.search(query, null, limit);
            List<Map<String, Object>> sources = outcome.sources();
            if (outcome.isEmpty()) {
                // 区分两种"空": 上游一条没给 vs 给了但全被规则/阈值过滤掉 ——
                // 前者是外部依赖故障, 后者是我们自己的规则过严, 排查方向完全相反。
                String hint = outcome.rawCount() > 0
                        ? "（联网搜索返回 " + outcome.rawCount() + " 条，但均不符合过滤条件，已全部剔除）"
                        : "（联网搜索未返回可用结果）";
                log.warn("联网搜索未返回可用结果: provider={}, query='{}', 原始 {} 条, 过滤 {} / 去重 {}",
                        provider.id(), query, outcome.rawCount(), outcome.filtered(), outcome.duplicated());
                return ToolInfo.text(hint).withStat(WebStat.search(provider.id(), query, outcome.rawCount(), 0));
            }
            return new ToolInfo(buildCompactContent(sources), sources,
                    WebStat.search(provider.id(), query, outcome.rawCount(), sources.size()));
        } catch (Exception e) {
            // 不抛异常: 工具失败不应中断整个对话, 由模型基于已有信息继续回答
            log.error("联网搜索失败: provider={}, query='{}', cause={}", provider.id(), query, e.getMessage(), e);
            return ToolInfo.text("联网搜索失败: " + e.getMessage())
                    .withStat(WebStat.searchFailed(provider.id(), query, WebStat.Empty.ERROR));
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
        // 上限只由配置决定: 再叠一个硬编码常量会让 agent-tools.web.max-results-limit 静默失效
        return Math.max(3, Math.min(config.getMaxResultsLimit(), n));
    }

    /**
     * 生成喂给模型的精简内容: 与原先 McpTool 的 compact 格式保持一致
     * (带 {@code [[n]]} 引用标记, 供模型在正文中穿插引用)。
     * <p>摘要<b>优先取 {@code summary}</b>(DeepSeek 给的 600~800 字符长摘要),
     * 缺失时回落 {@code snippet} —— 这使 SearXNG 路径的输出与改造前逐字节相同。
     */
    private String buildCompactContent(List<Map<String, Object>> sources) {
        StringBuilder sb = new StringBuilder();
        sb.append("以下是联网检索到的 ").append(sources.size())
                .append(" 条资料，请在回答中通过 [[n]] 标注引用来源（n 为下方序号）即可，"
                        + "不要在结尾另列来源清单（界面已在回答下方统一展示来源卡片）：\n\n");
        int i = 1;
        for (Map<String, Object> s : sources) {
            sb.append("[[").append(i).append("]] ").append(s.get("title"));
            Object url = s.get("url");
            if (url != null && !url.toString().isBlank()) {
                sb.append("  (").append(url).append(")");
            }
            Object text = s.get("summary");
            if (text == null || text.toString().isBlank()) {
                text = s.get("snippet");
            }
            if (text != null && !text.toString().isBlank()) {
                sb.append("\n    ").append(text);
            }
            sb.append("\n");
            i++;
        }
        return sb.toString();
    }
}
