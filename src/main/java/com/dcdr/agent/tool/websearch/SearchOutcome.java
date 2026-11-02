package com.dcdr.agent.tool.websearch;

import java.util.List;
import java.util.Map;

/**
 * 一次「联网搜索」的统一结果(搜索策略的返回载体)。
 *
 * <p><b>为什么要有这一层, 而不是让 Provider 直接返回 ToolInfo</b>:
 * {@code ToolInfo} 是<b>工具契约</b>(工具名、sources 形状、给模型的紧凑文本),
 * 而 Provider 只该回答"这次检索拿到了什么"。两者混在一起, 换一个搜索源就得重新实现
 * 一遍 {@code [[n]]} 引用格式与 {@code WebStat} 统计 —— 那部分逻辑在源之间完全共享。
 *
 * <p><b>为什么 {@code rawCount} 与 {@code filtered} 必须分开</b>: 沿用 {@code WebStat} 的口径 ——
 * "返回 0 条"要能区分"上游没给"和"我们自己过滤掉了"。两个检索源都在这个位置有过滤
 * (SearXNG 走 {@link SearchResultCleaner} 剔聚合页, DeepSeek 走相关性/权威性阈值),
 * 合并成一个数字就再也查不出是谁的问题。
 *
 * <p><b>这里刻意没有 {@code emptyReason} 字段</b>: 空结果的成因由 {@code WebStat.search}
 * 依据 {@code rawCount} 推断(NO_RESULTS / ALL_FILTERED), 再放一份就是两个真值源。
 * Provider 表达"请求失败"的唯一方式是<b>抛异常</b>, 由门面统一转成 {@code ERROR}。
 *
 * @param rawCount   上游原始返回条数(过滤前)
 * @param filtered   被过滤规则/阈值剔除的条数
 * @param duplicated 被 URL 去重剔除的条数
 * @param sources    最终进入 sources 的条目, 每条形如 {@code {index,title,url,snippet}}
 */
public record SearchOutcome(
        int rawCount,
        int filtered,
        int duplicated,
        List<Map<String, Object>> sources) {

    public boolean isEmpty() {
        return sources == null || sources.isEmpty();
    }
}
