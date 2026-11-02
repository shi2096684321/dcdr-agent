package com.dcdr.agent.tool.websearch;

/**
 * 一次「网页抓取」的统一结果(抓取策略的返回载体)。
 *
 * <p><b>为什么需要这一层</b>: 本地抓取链路的产物是 {@code (title, Markdown 正文)},
 * DeepSeek Fetch 的产物也是 {@code (title, Markdown 正文)} —— 协议不同、错误码不同,
 * 但<b>语义完全一致</b>。抽出这个载体后, 门面只需处理一种形状,
 * 新增抓取源也不必再动 {@code WebExtractTool}。
 *
 * @param title   页面标题; 缺失时为空字符串, 由调用方决定是否回落到 host
 * @param content 已转成 Markdown 的正文(可能已被截断到配置上限)
 */
public record FetchedPage(String title, String content) {

    /** 正文为空即视为"没抓到" —— 纯 JS 渲染页与拦截页都表现为空正文 */
    public boolean isEmpty() {
        return content == null || content.isBlank();
    }
}
