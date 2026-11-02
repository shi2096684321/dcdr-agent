package com.dcdr.agent.agent.loop;

import com.dcdr.agent.agent.memory.Message;

import java.util.List;
import java.util.Map;

/**
 * 联网搜索预算的两个度量(纯静态, 无状态)。
 *
 * <h2>它补的是什么坑</h2>
 * 在此之前, 联网搜索只有 {@code MAX_TOOL_ROUNDS=10} 兜底 —— 而模型会在一轮里并行发
 * 3~4 个<b>不同关键词</b>的搜索。关键词不同 → {@link ToolCallLoopDetector} 的"相同签名"判定
 * 一个都不命中 → 于是能一路搜到第 10 轮。实测一次对话 40 次搜索、约 0.2 元,
 * 而回答只用到其中几条, 其余是纯粹的重复劳动。
 *
 * <h2>为什么不用"新增来源为 0 就停"</h2>
 * 那个信号更准, 但需要跨轮状态(要记住上一轮的新增数), 而响应式链路里跨轮状态只能靠
 * 往 {@code executeTools} 再加参数或 ThreadLocal。这里取两个<b>零状态</b>的度量:
 * <ul>
 *   <li>已搜次数 —— 从 {@code history} 里数(只数本次请求内的);</li>
 *   <li>去重来源数 —— 跨轮累积的 {@code collectedSources} 直接去重统计。</li>
 * </ul>
 * 两者合起来已经能表达"搜了很多次但没拿到新东西", 且实现上没有额外的一致性负担。
 */
public final class WebSearchBudget {

    private WebSearchBudget() {
    }

    /**
     * 数<b>本次请求内</b>已发起的 {@code web_search} 次数。
     *
     * <p><b>只数到最近一条 user 消息为止</b>: 若数整个 history, 那么用户在同一个会话里
     * 追问第二个问题时, 预算会被上一个问题用掉的额度吃掉 —— 表现为"第二个问题完全搜不了",
     * 而且这种 bug 在第一个问题上永远测不出来。
     *
     * @param history 应当已经排除掉本轮的 {@code assistant(tool_calls)}(见编排层的
     *                {@code historyBeforeCurrentRound}), 否则同一批调用会被数两次
     */
    public static int searchesSinceLastUser(List<Message> history, String searchToolName) {
        if (history == null || history.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            Message m = history.get(i);
            if ("user".equals(m.getRole())) {
                break;   // 本次请求的起点, 再往前是上一轮对话
            }
            if (m.getToolCalls() == null || m.getToolCalls().isEmpty()) {
                continue;
            }
            for (Map<String, Object> call : m.getToolCalls()) {
                if (searchToolName.equals(String.valueOf(call.get("name")))) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * 跨轮累积来源里<b>去重</b>后的 URL 数。
     * <p>同一个站点被反复搜到时这个数不会增长 —— 它比"总条数"更能反映"真的拿到了多少不同信息"。
     */
    public static long distinctSources(List<Map<String, Object>> sources) {
        if (sources == null || sources.isEmpty()) {
            return 0;
        }
        return sources.stream()
                .map(s -> String.valueOf(s.get("url")))
                .filter(u -> !"null".equals(u) && !u.isBlank())
                .distinct()
                .count();
    }

    /**
     * 本轮第 N 次搜索可以抓取几篇正文。
     *
     * <h2>为什么不能简单地 {@code min(topN, budgetLeft)}</h2>
     * 那样是<b>先到先得</b>: 一轮 3 次搜索、预算 6 篇时, 前两次各吃 3 篇就把额度耗尽,
     * 第三次 take=0 —— 它的 15 条结果一条正文都抓不到, 只剩标题摘要。
     * 而模型一轮并发多个关键词时, 后面几个词往往才是主题最准的(前面的词常是宽度过大的泛搜),
     * 结果是最该读的那批页面反而只有标题。实测 2026-11-02 21:18 那次 turn:
     * 广州那次搜索 15 条结果 extractOk 为空(一条都没抓), 模型随即又换词重搜。
     *
     * <h2>算法</h2>
     * 给"后面还没执行的搜索"各预留 1 篇保底, 剩下的才给当前这次; 当前这次至少 1 篇。
     * 3 次搜索 + 6 篇预算 → 3 / 2 / 1, 总量不变而每次都有正文。
     *
     * @param budgetLeft        本轮剩余可抓篇数
     * @param remainingSearches 本轮**尚未执行**的搜索次数(不含当前这次)
     * @param topN              单次抓取上限
     * @return 本次可抓篇数, 0 表示不抓(只剩摘要)
     */
    public static int extractTake(int budgetLeft, int remainingSearches, int topN) {
        if (budgetLeft <= 0 || topN <= 0) {
            return 0;
        }
        int reserved = Math.max(0, remainingSearches);
        int available = budgetLeft - reserved;
        return Math.max(1, Math.min(topN, available));
    }
}
