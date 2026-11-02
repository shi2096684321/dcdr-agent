package com.dcdr.agent.tool.websearch;

/**
 * 网页抓取策略(Strategy)。
 *
 * <p><b>存在两个实现</b>: {@code local}(jsoup + crux + flexmark, 自己解析 HTML) 与
 * {@code deepseek}({@code /v1/tools/fetch}, 直接拿 Markdown)。两者都产出
 * {@link FetchedPage}, 所以上层不必关心正文是怎么来的。
 *
 * <p><b>失败语义</b>: 返回 {@code null} 表示"这个 URL 抓不到", <b>不要抛异常</b> ——
 * 抓取是逐 URL 进行的, 一个失败不该中断其余 URL; 而 {@link FallbackFetchProvider}
 * 正是靠这个返回值来决定要不要切换到兜底源。
 */
public interface WebFetchProvider {

    /** 策略标识, 与配置值一致(如 {@code local} / {@code deepseek}) */
    String id();

    /**
     * 本策略当前是否可用(需要外部凭据的实现覆写)。
     * <p>不可用的策略不会入选, 也就不会让 {@link FallbackFetchProvider} 白跑一次主源。
     */
    default boolean available() {
        return true;
    }

    /**
     * 抓取并抽取单个 URL 的正文。
     *
     * @param url   目标地址(调用方已做过 SSRF 预检)
     * @param query 用户原始问题, 供支持相关性聚焦的源使用(可为 null)
     * @return 抓取结果; 抓不到正文(纯 JS 渲染页 / 被拦截 / 提取失败)时返回 {@code null}
     */
    FetchedPage fetch(String url, String query);
}
