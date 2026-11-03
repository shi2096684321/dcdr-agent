package com.dcdr.agent.agent.loop;

import com.dcdr.agent.agent.memory.Message;

import java.util.List;
import java.util.Map;

/**
 * 联网搜索预算的四个度量(纯静态, 无状态)。
 *
 * <h2>它补的是什么坑</h2>
 * 在此之前, 联网搜索只有 {@code MAX_TOOL_ROUNDS=10} 兜底 —— 而模型会在一轮里并行发
 * 3~4 个<b>不同关键词</b>的搜索。关键词不同 → {@link ToolCallLoopDetector} 的"相同签名"判定
 * 一个都不命中 → 于是能一路搜到第 10 轮。实测一次对话 40 次搜索、约 0.2 元,
 * 而回答只用到其中几条, 其余是纯粹的重复劳动。
 *
 * <h2>四个度量: 次数 / 饱和度 / 收敛度 / 可达性</h2>
 * 前两个是<b>零状态</b>的:
 * <ul>
 *   <li>已搜次数 —— 从 {@code history} 里数(只数本次请求内的);</li>
 *   <li>去重来源数 —— 跨轮累积的 {@code collectedSources} 直接去重统计。</li>
 * </ul>
 * 但它们都只能回答"够了没有", 回答不了"再搜还有没有用": 次数用完才停是<b>猜</b>,
 * 连续搜不到新站点才是<b>证据</b>。于是 V3.3 还是加了第三个度量 —— "连续几次搜索没带回新来源",
 * 状态由编排层的 {@code WebRoundState} 跨轮携带(见 {@link #nextNoGainStreak})。
 * 第四个是"还搜得出去吗": 连续请求失败要熔断(见 {@link #nextFailureStreak}), 否则网络断了
 * 模型会一路重试把额度打光, 而给用户的答复里只剩一句"未检索到"。
 *
 * <p><b>这个签名踩过的两个坑</b>(2026-11-03 修):
 * <ol>
 *   <li>拿"本轮开始时的去重数"和"上一轮结束时的快照"比 —— 这两个值本来就是同一个数
 *       (中间没人往 {@code collectedSources} 里加东西), 于是判定恒为"无新增",
 *       每轮从第二条搜索起必然被拦, 4 个并行关键词实际只跑了 1 个;</li>
 *   <li>搜索<b>失败</b>同样不涨来源数, 也被当成"关键词已收敛" —— 一次 DNS 抖动就让整次
 *       检索提前收口, 而正确做法是让模型换词重试。所以失败不计入, 见 {@link #nextNoGainStreak}。</li>
 * </ol>
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
     * 连续"执行了却没带回新来源"的搜索次数达到该值, 判定关键词已收敛。
     * <p>取 2 而不是 1: 单次没带新来源太正常了 —— 同一篇通稿常被多个站点转载, 换一个关键词
     * 撞上同一批站点说明不了什么, 还值得再试一次; 连着两次都撞同一批, 才是"再搜也没用"。
     * <p>它只是<b>提前</b>收口, 兜底仍是 {@code max-search-per-turn} 与 {@code MAX_TOOL_ROUNDS}。
     */
    public static final int NO_GAIN_THRESHOLD = 2;

    /**
     * 记一次<b>已执行</b>的 {@code web_search} 的推进结果, 返回新的连续无新增次数。
     *
     * <p><b>为什么 {@code produced=false} 时不累加也不清零</b>: 请求异常 / 联网未启用 / 上游返回 0 条,
     * 这三种情况的共同点是"这次搜索没拿到东西", 但成因在外面 —— 既不能当作收敛证据(否则一次 DNS
     * 抖动就把整次检索关掉), 也不该清掉此前攒下的无新增计数(那会让收敛永远攒不满)。
     * 让它保持原值, 等价于"这一次没发生"。
     *
     * @param current        此前的连续无新增次数
     * @param produced       本次是否拿到了可用来源
     * @param distinctBefore 本次执行前的去重来源数
     * @param distinctAfter  本次执行后的去重来源数(含本次新增)
     * @return 新的连续无新增次数
     */
    public static int nextNoGainStreak(int current, boolean produced, int distinctBefore, int distinctAfter) {
        if (!produced) {
            return current;
        }
        return distinctAfter > distinctBefore ? 0 : current + 1;
    }

    /** 连续无新增是否已达到收敛阈值(达到即不再允许新的搜索) */
    public static boolean noGainConverged(int noGainStreak) {
        return noGainStreak >= NO_GAIN_THRESHOLD;
    }

    /**
     * 连续"根本没法搜"的搜索次数达到该值, 判定联网不可用(熔断)。
     * <p>取 3 而不是 1: 单次请求失败可能是上游偶发 5xx 或一次超时, 换一次仍然值得试。
     * 但也不宜更大 —— 每次失败要等满 Netty 的 5 秒查询超时, 3 次已是 15 秒; 实测 2026-11-03
     * 那次 DNS 故障, 模型一路重试到把 8 次搜索额度打光(约 40 秒), 最后拿着空结果作答。
     */
    public static final int FAILURE_THRESHOLD = 3;

    /**
     * 记一次搜索的失败/恢复, 返回新的连续失败次数。
     *
     * <p><b>与 {@link #nextNoGainStreak} 的分工</b>: 那个问"还搜得出新东西吗"(收敛), 这个问
     * "搜得出去吗"(可达性)。两件事必须分开计数 —— 网络断了属于后者, 若混进前者就会被判成
     * "关键词收敛", 模型收到的提示变成"不要换词试探", 而它真正该做的是告诉用户联网不可用。
     *
     * <p>只要这次<b>真的发出去并拿到了响应</b>就清零: 上游返回 0 条、结果全被清洗掉,
     * 都说明链路是通的, 只是这次没内容。
     *
     * @param current     此前的连续失败次数
     * @param hardFailure 本次是否属于"根本没发出去"(请求异常 / 联网未启用)
     */
    public static int nextFailureStreak(int current, boolean hardFailure) {
        return hardFailure ? current + 1 : 0;
    }

    /** 是否已判定联网不可用(连续失败达到阈值) */
    public static boolean webUnavailable(int failureStreak) {
        return failureStreak >= FAILURE_THRESHOLD;
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
