package com.dcdr.agent.tool.websearch;

import com.dcdr.agent.config.ToolsProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 搜索/抓取策略选择器的单元测试(纯逻辑, 不发请求)。
 *
 * <p><b>为什么要专门测这个类</b>: 它是"配错配置会在什么时候炸"的唯一决定点。三种结果都要锁住 ——
 * 配置名写错必须<b>启动即失败</b>(否则线上表现为"偶发搜不到"), 依赖缺失必须<b>降级而不是崩</b>,
 * {@code auto} 必须真的跟随搜索源。这三条任何一条退化, 都不会有任何报错,
 * 只会让人在排查"为什么搜索变差了"时绕远路。
 *
 * <p>这里不 mock WebClient: 两个 Provider 的构造只做连接池装配, 不发起请求,
 * 所以直接 new 真实实现即可 —— 也就顺带验证了"装配不依赖网络"这一点。
 */
class WebProviderFactoryTest {

    private static List<WebSearchProvider> searchProviders(ToolsProperties properties) {
        DeepseekApiClient client = new DeepseekApiClient(properties);
        return List.of(new SearxngSearchProvider(properties), new DeepseekSearchProvider(properties, client));
    }

    private static List<WebFetchProvider> fetchProviders(ToolsProperties properties) {
        DeepseekApiClient client = new DeepseekApiClient(properties);
        return List.of(new LocalHtmlFetchProvider(properties), new DeepseekFetchProvider(properties, client));
    }

    private static ToolsProperties properties(String provider, String fetchProvider) {
        ToolsProperties properties = new ToolsProperties();
        properties.getWeb().setProvider(provider);
        properties.getWeb().setFetchProvider(fetchProvider);
        return properties;
    }

    @Test
    @DisplayName("默认配置(searxng + auto): 搜索走 searxng, 抓取走本地")
    void defaultsToSearxngAndLocal() {
        ToolsProperties properties = properties("searxng", "auto");
        WebProviderFactory factory = new WebProviderFactory(
                properties, searchProviders(properties), fetchProviders(properties));

        assertEquals(SearxngSearchProvider.ID, factory.searchProvider().id());
        assertEquals(LocalHtmlFetchProvider.ID, factory.fetchProvider().id());
    }

    @Test
    @DisplayName("provider 写了不存在的名字: 启动即失败, 不允许静默回退")
    void failsFastOnUnknownProvider() {
        ToolsProperties properties = properties("bing", "auto");
        List<WebSearchProvider> searchers = searchProviders(properties);
        List<WebFetchProvider> fetchers = fetchProviders(properties);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new WebProviderFactory(properties, searchers, fetchers));
        assertTrue(e.getMessage().contains("bing"), "错误信息要带上写错的值, 否则排查要靠猜: " + e.getMessage());
    }

    @Test
    @DisplayName("选 deepseek 但没配 Key: 降级为 searxng 而不是启动失败")
    void degradesWhenDeepseekKeyMissing() {
        ToolsProperties properties = properties("deepseek", "auto");
        properties.getWeb().getDeepseek().setApiKey("");
        WebProviderFactory factory = new WebProviderFactory(
                properties, searchProviders(properties), fetchProviders(properties));

        assertEquals(SearxngSearchProvider.ID, factory.searchProvider().id(),
                "缺 Key 时联网能力仍然可用(降级), 不该把整个应用拖崩");
        assertEquals(LocalHtmlFetchProvider.ID, factory.fetchProvider().id(),
                "auto 跟随的是【实际选中的】搜索源, 不是配置里写的那个");
    }

    @Test
    @DisplayName("deepseek 有 Key + auto: 搜索与抓取都走 deepseek, 且抓取挂上本地兜底")
    void usesDeepseekWithLocalFallback() {
        ToolsProperties properties = properties("deepseek", "auto");
        properties.getWeb().getDeepseek().setApiKey("sk-test");
        properties.getWeb().setFetchFallback(true);
        WebProviderFactory factory = new WebProviderFactory(
                properties, searchProviders(properties), fetchProviders(properties));

        assertEquals(DeepseekSearchProvider.ID, factory.searchProvider().id());
        assertEquals("deepseek+local", factory.fetchProvider().id());
    }

    @Test
    @DisplayName("关掉 fetch-fallback: 抓取只用主源, 不再多跑一次兜底")
    void respectsFallbackSwitch() {
        ToolsProperties properties = properties("deepseek", "auto");
        properties.getWeb().getDeepseek().setApiKey("sk-test");
        properties.getWeb().setFetchFallback(false);
        WebProviderFactory factory = new WebProviderFactory(
                properties, searchProviders(properties), fetchProviders(properties));

        assertEquals(DeepseekFetchProvider.ID, factory.fetchProvider().id());
    }

    @Test
    @DisplayName("主源本来就是本地时, 不该把本地再包一层当自己的兜底")
    void doesNotWrapLocalWithItself() {
        ToolsProperties properties = properties("searxng", "local");
        WebProviderFactory factory = new WebProviderFactory(
                properties, searchProviders(properties), fetchProviders(properties));

        assertEquals(LocalHtmlFetchProvider.ID, factory.fetchProvider().id(),
                "自身兜底自身会白跑两次请求");
    }
}
