package com.dcdr.agent.tool.websearch;

import com.dcdr.agent.config.ToolsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * 联网能力的策略选择器(简单工厂 + 注册表)。
 *
 * <p><b>为什么是"注册表"而不是 if-else / switch</b>: 每个 Provider 自己声明 {@link WebSearchProvider#id()},
 * Spring 把全部实现注入成 {@code List}, 这里只建索引。新增第三个搜索源时
 * <b>只需要加一个 {@code @Component}, 本类一行都不用改</b> —— 而 switch 写法必然要回来补一个分支。
 *
 * <p><b>为什么在构造期就解析, 而不是每次调用时判断</b>: 联网工具是"配错了也照常启动、
 * 只是搜不到东西"的典型。放到运行时, 表现就是"偶发搜索失败", 排查成本极高。
 * 这里在启动时就把结果定下来:
 * <ul>
 *   <li>配置了不存在的策略名 → <b>直接启动失败</b>(配置错误, 必须立刻发现);</li>
 *   <li>策略存在但依赖缺失(如选了 deepseek 却没配 Key)→ <b>降级到 searxng 并打 ERROR</b>
 *       —— 联网是可选能力, 不该因为少一个 Key 就让整个应用起不来。</li>
 * </ul>
 */
@Slf4j
@Component
public class WebProviderFactory {

    private final WebSearchProvider searchProvider;
    private final WebFetchProvider fetchProvider;

    public WebProviderFactory(ToolsProperties properties,
                              List<WebSearchProvider> searchProviders,
                              List<WebFetchProvider> fetchProviders) {
        ToolsProperties.WebToolsConfig config = properties.getWeb();
        this.searchProvider = resolveSearch(config, searchProviders);
        this.fetchProvider = resolveFetch(config, fetchProviders, this.searchProvider);
        log.info("联网能力已装配: search={}, fetch={}", searchProvider.id(), fetchProvider.id());
    }

    public WebSearchProvider searchProvider() {
        return searchProvider;
    }

    public WebFetchProvider fetchProvider() {
        return fetchProvider;
    }

    // ===================== 解析 =====================

    private static WebSearchProvider resolveSearch(
            ToolsProperties.WebToolsConfig config, List<WebSearchProvider> providers) {
        Map<String, WebSearchProvider> byId = index(providers, WebSearchProvider::id);
        String wanted = normalize(config.getProvider(), SearxngSearchProvider.ID);
        WebSearchProvider chosen = byId.get(wanted);
        if (chosen == null) {
            throw new IllegalStateException("agent-tools.web.provider=" + wanted
                    + " 没有对应实现, 可选值: " + byId.keySet());
        }
        if (chosen.available()) {
            return chosen;
        }
        // 依赖缺失(通常是缺 API Key): 降级而不是启动失败, 但必须喊出来
        WebSearchProvider fallback = byId.get(SearxngSearchProvider.ID);
        if (fallback == null || !fallback.available()) {
            throw new IllegalStateException("搜索源 " + wanted + " 不可用, 且没有可降级的实现");
        }
        log.error("搜索源 {} 不可用(多为未配置 agent-tools.web.deepseek.api-key), 已降级为 {}",
                wanted, fallback.id());
        return fallback;
    }

    private static WebFetchProvider resolveFetch(
            ToolsProperties.WebToolsConfig config,
            List<WebFetchProvider> providers,
            WebSearchProvider searchProvider) {
        Map<String, WebFetchProvider> byId = index(providers, WebFetchProvider::id);
        String wanted = normalize(config.getFetchProvider(), "auto");
        if ("auto".equals(wanted)) {
            // 跟随搜索源: 搜索已经走 DeepSeek 时, 抓取走同一家能省一次跨厂商的握手与账目
            wanted = DeepseekSearchProvider.ID.equals(searchProvider.id())
                    ? DeepseekFetchProvider.ID : LocalHtmlFetchProvider.ID;
        }
        WebFetchProvider primary = byId.get(wanted);
        if (primary == null) {
            throw new IllegalStateException("agent-tools.web.fetch-provider=" + wanted
                    + " 没有对应实现, 可选值: " + byId.keySet());
        }
        if (!primary.available()) {
            WebFetchProvider local = byId.get(LocalHtmlFetchProvider.ID);
            if (local == null || !local.available()) {
                throw new IllegalStateException("抓取源 " + wanted + " 不可用, 且没有可降级的实现");
            }
            log.error("抓取源 {} 不可用, 已降级为 {}", wanted, local.id());
            return local;
        }
        WebFetchProvider local = byId.get(LocalHtmlFetchProvider.ID);
        // 主源本来就是本地时不存在"兜底自己", 否则会白跑两次
        if (config.isFetchFallback() && local != null && local != primary && local.available()) {
            log.info("抓取策略: {}(失败时回落 {}, 主源超 {}ms 不再兜底)",
                    primary.id(), local.id(), config.getFetchFallbackBudgetMs());
            return new FallbackFetchProvider(primary, local, config.getFetchFallbackBudgetMs());
        }
        return primary;
    }

    private static <T> Map<String, T> index(List<T> providers, Function<T, String> idOf) {
        Map<String, T> map = new LinkedHashMap<>();
        for (T provider : providers) {
            map.put(idOf.apply(provider).toLowerCase(Locale.ROOT), provider);
        }
        return map;
    }

    private static String normalize(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim().toLowerCase(Locale.ROOT);
    }
}
