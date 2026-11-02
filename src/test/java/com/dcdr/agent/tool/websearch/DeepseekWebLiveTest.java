package com.dcdr.agent.tool.websearch;

import com.dcdr.agent.config.ToolsProperties;
import com.dcdr.agent.tool.ToolInfo;
import com.dcdr.agent.tool.WebStat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * DeepSeek 搜索/抓取的<b>真实链路冒烟测试</b>(默认不执行)。
 *
 * <p><b>它补的是单测补不了的那一半</b>: {@code WebProviderFactoryTest} 只能证明"选路逻辑对",
 * 证明不了"我们的字段映射对" —— {@code rerank_score} 写成 {@code rerankScore}、{@code summary}
 * 取错层级这类问题, 单测全绿而线上每条结果的摘要都是空字符串。只有真发一次请求才知道。
 *
 * <p><b>为什么要跑整条 Tool 链路而不只是 Provider</b>: 门面负责搬 {@code index} /
 * 拼 {@code [[n]]} / 填 {@code WebStat}, 这些也是"错了不报、只表现为效果差"的地方。
 *
 * <p><b>开关</b>(默认跳过, 会真实计费约 1 分钱: 每 1000 次成功请求 5 元):
 * <pre>
 *   DEEPSEEK_LIVE_TEST=true DEEPSEEK_API_KEY=sk-xxx \
 *     mvn -B test -Dtest=DeepseekWebLiveTest
 * </pre>
 * 不要把它设成默认执行: 它依赖账户余额与外网, 会把一次与我们无关的余额耗尽变成 CI 红灯。
 */
class DeepseekWebLiveTest {

    private static final String QUERY = "Spring Boot 3 最新版本";

    @Test
    @DisplayName("真实调用: 搜索返回带 summary/authority 的来源, 且能抓取第一条的正文")
    void searchAndFetchRealPage() {
        assumeTrue("true".equalsIgnoreCase(System.getenv("DEEPSEEK_LIVE_TEST")),
                "未开启实测(设置 DEEPSEEK_LIVE_TEST=true 后执行)");
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "未提供 DEEPSEEK_API_KEY");

        ToolsProperties properties = deepseekProperties(apiKey);
        WebProviderFactory factory = factory(properties);
        WebSearchTool searchTool = new WebSearchTool(properties, factory);

        ToolInfo info = searchTool.executeWithInfo(Map.of("query", QUERY, "maxResults", 3));

        List<Map<String, Object>> sources = info.getSources();
        assertNotNull(sources, "搜索应返回 sources; 实际返回文本: " + info.getContent());
        assertTrue(!sources.isEmpty(), "真实查询应至少命中一条; 返回文本: " + info.getContent());
        assertNotNull(info.getWebStat(), "搜索必须上报 WebStat, 否则联网健康度无法统计");
        assertTrue(info.getWebStat().rawCount() > 0, "rawCount 应记录上游原始条数");

        Map<String, Object> first = sources.get(0);
        assertTrue(String.valueOf(first.get("title")).length() > 0, "标题不应为空");
        assertTrue(String.valueOf(first.get("url")).startsWith("http"), "URL 应已归一化为 http(s)");
        assertTrue(first.get("index") instanceof Integer, "index 必须是整数, 门面的 [[n]] 引用依赖它");
        assertTrue(first.containsKey("summary"),
                "DeepSeek 的核心优势就是 600~800 字符的 summary, 丢了等于退化成 SearXNG: " + first.keySet());
        assertNotNull(first.get("authority"), "authority 应被保留以供排查");

        // 再走一次抓取, 验证 /v1/tools/fetch 的映射(title 与 Markdown 正文)。
        // 【必须逐条尝试, 不能只抓第一条】实测: 同一个查询的 top1 可能是「作者页 / 栏目聚合页」,
        // DeepSeek Fetch 对它返回 404、本地也拿不到正文 —— 这不是链路故障, 而是真实网页的常态。
        // 编排层同样是对 top-N 逐个抓、失败保留摘要, 这里的断言口径要跟它一致。
        WebExtractTool extractTool = new WebExtractTool(properties, factory);
        ToolInfo extracted = null;
        for (Map<String, Object> source : sources) {
            String url = String.valueOf(source.get("url"));
            ToolInfo attempt = extractTool.executeWithInfo(Map.of("urls", List.of(url)));
            if (attempt.getSources() != null && !attempt.getSources().isEmpty()) {
                extracted = attempt;
                break;
            }
        }
        assertNotNull(extracted, "前 " + sources.size()
                + " 条结果全部抓不到正文 —— 这已不是个别页面反爬, 而是抓取链路本身有问题");

        List<Map<String, Object>> pages = extracted.getSources();
        String content = String.valueOf(pages.get(0).get("content"));
        assertTrue(content.length() > WebStat.THIN_CONTENT_CHARS,
                "正文应超过薄页阈值(" + WebStat.THIN_CONTENT_CHARS + " 字符), 实际 " + content.length());
    }

    private static ToolsProperties deepseekProperties(String apiKey) {
        ToolsProperties properties = new ToolsProperties();
        properties.getWeb().setProvider(DeepseekSearchProvider.ID);
        properties.getWeb().setFetchProvider("auto");
        properties.getWeb().getDeepseek().setApiKey(apiKey);
        return properties;
    }

    private static WebProviderFactory factory(ToolsProperties properties) {
        DeepseekApiClient client = new DeepseekApiClient(properties);
        return new WebProviderFactory(properties,
                List.of(new SearxngSearchProvider(properties), new DeepseekSearchProvider(properties, client)),
                List.of(new LocalHtmlFetchProvider(properties), new DeepseekFetchProvider(properties, client)));
    }
}
