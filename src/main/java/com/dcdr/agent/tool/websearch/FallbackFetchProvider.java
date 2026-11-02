package com.dcdr.agent.tool.websearch;

import lombok.extern.slf4j.Slf4j;

/**
 * 抓取兜底链(责任链的极简形态): 主源拿不到正文时切到兜底源。
 *
 * <p><b>这不是"为了用设计模式而套一层"</b>, 而是两个抓取源的真实差异决定的:
 * DeepSeek Fetch 是黑盒, {@code 403}(安全策略拦截) / {@code 404}(提不出可用 Markdown)
 * 都无法改进; 本地 crux 链路对中文站编码、结构化重建做了大量适配, 在中文语料上
 * 反而可能更稳。让它们互补, 比"二选一然后赌一个"更划算。
 *
 * <p><b>只在"完全没拿到正文"时触发兜底, 不做"薄页也重抓"</b>: 后者要为每个 URL 多付
 * 一次往返, 而"登录页 / 拦截页"判定本身也不可靠 —— 换来的是更慢且不确定的结果。
 */
@Slf4j
public class FallbackFetchProvider implements WebFetchProvider {

    private final WebFetchProvider primary;
    private final WebFetchProvider fallback;
    private final int primaryBudgetMs;

    public FallbackFetchProvider(WebFetchProvider primary, WebFetchProvider fallback, int primaryBudgetMs) {
        this.primary = primary;
        this.fallback = fallback;
        this.primaryBudgetMs = primaryBudgetMs;
    }

    /** 组合标识, 如 {@code deepseek+local}; 只用于日志与排查 */
    @Override
    public String id() {
        return primary.id() + "+" + fallback.id();
    }

    @Override
    public boolean available() {
        return primary.available() || fallback.available();
    }

    @Override
    public FetchedPage fetch(String url, String query) {
        long start = System.nanoTime();
        FetchedPage page = primary.available() ? primary.fetch(url, query) : null;
        long primaryCostMs = (System.nanoTime() - start) / 1_000_000;
        if (page != null && !page.isEmpty()) {
            return page;
        }
        if (!fallback.available()) {
            return null;
        }
        // 主源已经耗掉大半超时预算时不再兜底: 换源重试只会把"慢"变成"更慢"。
        // 兜底真正救得回来的, 是 403/404 这类**秒级快速失败**的页面。
        if (primaryCostMs > primaryBudgetMs) {
            log.warn("抓取主源 {} 耗时 {}ms 已超兜底预算 {}ms, 放弃换源(避免超时叠加): url={}",
                    primary.id(), primaryCostMs, primaryBudgetMs, url);
            return null;
        }
        log.info("抓取主源 {} 未拿到正文(耗时 {}ms), 回落到 {}", primary.id(), primaryCostMs, fallback.id());
        FetchedPage fb = fallback.fetch(url, query);
        return fb != null && !fb.isEmpty() ? fb : null;
    }
}
