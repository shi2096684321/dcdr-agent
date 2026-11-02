package com.dcdr.agent.tool.websearch;

import com.dcdr.agent.config.ToolsProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实页面的<b>联网冒烟测试</b>(默认不执行)。
 *
 * <p><b>它补的是 fixture 评测补不了的那一半</b>: {@code WebExtractFixtureTest} 用的是存档 HTML,
 * 能保证"算法对已知结构没有退化", 但答不了"真实站点现在还抓得到吗" ——
 * 后者会被反爬、改版、CDN 拦截、编码变更影响, 只有真发一次请求才知道。
 *
 * <p>两者刻意分开: 冒烟测试依赖外部网络与第三方站点的配合度, 放进每次构建会让
 * 一个与我们无关的改版把 CI 搞红; 而它又确实是"上线前该跑一次"的东西。
 *
 * <p><b>开关</b>(默认跳过):
 * <pre>
 *   WEB_EXTRACT_SMOKE_TEST=true mvn -B test -Dtest=WebExtractLiveSmokeTest
 *   # 指定目标页面(默认取维基百科, 结构复杂、包含大量导航与信息框, 是个不错的压力样本)
 *   WEB_EXTRACT_SMOKE_TEST=true mvn -B test -Dtest=WebExtractLiveSmokeTest -Dweb.smoke.url=https://...
 * </pre>
 */
class WebExtractLiveSmokeTest {

    /**
     * 默认目标选 MDN 中文文档: 结构复杂(多级标题、代码块、侧边导航)、体积大(200KB+)、
     * 且是典型的技术类查询落点 —— 正是 crux 会丢标题与代码块的那种页面。
     */
    private static final String DEFAULT_URL =
            "https://developer.mozilla.org/zh-CN/docs/Web/JavaScript";

    @Test
    @DisplayName("真实页面: 能取到内容且正文非空(默认跳过, 见类注释的开关)")
    void fetchesRealPage() {
        assumeTrue("true".equalsIgnoreCase(System.getenv("WEB_EXTRACT_SMOKE_TEST")),
                "未开启冒烟测试(设置 WEB_EXTRACT_SMOKE_TEST=true 后执行)");

        String url = System.getProperty("web.smoke.url", DEFAULT_URL);
        // V3.3: 工具改成了门面, 抓取策略由工厂装配。冒烟测试强制走本地抓取,
        // 免得这条用例依赖 DeepSeek 的额度; 但走的是真实装配链, 所以"装不上"也会在这里暴露
        ToolsProperties properties = new ToolsProperties();
        properties.getWeb().setFetchProvider(LocalHtmlFetchProvider.ID);
        WebProviderFactory factory = new WebProviderFactory(properties,
                List.of(new SearxngSearchProvider(properties),
                        new DeepseekSearchProvider(properties, new DeepseekApiClient(properties))),
                List.of(new LocalHtmlFetchProvider(properties),
                        new DeepseekFetchProvider(properties, new DeepseekApiClient(properties))));
        WebExtractTool tool = new WebExtractTool(properties, factory);

        long start = System.nanoTime();
        var info = tool.executeWithInfo(Map.of("urls", List.of(url)));
        long costMs = (System.nanoTime() - start) / 1_000_000;

        assertNotNull(info, "工具不应返回 null");
        List<Map<String, Object>> sources = info.getSources();
        // 抓取全失败时工具返回的是纯文本提示, sources 为 null(不是空列表) ——
        // 断言前必须先判空, 否则真正的失败原因("网页抓取失败: ...")会被一句
        // "expected not <null>" 盖掉
        if (sources == null || sources.isEmpty()) {
            fail("抓取失败(工具返回): " + info.getContent());
        }
        String content = String.valueOf(sources.get(0).get("content"));
        System.out.printf("[smoke] %s → 标题=%s, 正文 %d 字, 耗时 %d ms%n",
                url, sources.get(0).get("title"), content.length(), costMs);
        // 只做下限断言, 不比对具体内容 —— 真实页面的正文会随编辑变化,
        // 写死任何一句都会让这条冒烟测试在下一次上游改版后永久失败。
        // 1000 字是"技术文档被正常抽取"的量级: 掉到两百字基本就是抓到了登录页/空壳页,
        // 而那种情况下工具仍会报"抓取成功" —— 正是这条断言要拦住的情形
        assertTrue(content.length() > 1000,
                "正文过短(" + content.length() + " 字), 疑似被拦截、需要登录、站点已改版, "
                        + "或正文容器定位失效。工具返回: " + content);
    }
}
