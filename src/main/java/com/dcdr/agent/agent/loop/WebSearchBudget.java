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
}
