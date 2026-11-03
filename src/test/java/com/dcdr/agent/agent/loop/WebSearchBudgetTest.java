package com.dcdr.agent.agent.loop;

import com.dcdr.agent.agent.memory.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 联网搜索预算度量的口径测试。
 *
 * <p>这两个数直接决定"还要不要允许再搜一次", 算错的后果不对称:
 * 算少了 → 该拦没拦, 一次对话又搜十几轮; 算多了 → <b>正常提问也搜不了</b>,
 * 而且第一个问题永远测不出来(要问第二个问题才暴露)。
 */
class WebSearchBudgetTest {

    private static final String SEARCH = "web_search";

    @Test
    @DisplayName("只数本次请求内的搜索: 遇到上一条 user 消息就停")
    void stopsAtLastUserMessage() {
        List<Message> history = new ArrayList<>();
        // 上一轮对话(用户第一个问题)里搜了 5 次
        history.add(user());
        history.add(assistantCall(SEARCH, 5));
        history.add(user());          // 本次请求的起点
        history.add(assistantCall(SEARCH, 2));

        // 只该数这 2 次, 而不是 7 次 —— 否则用户追问第二个问题时预算已被上个问题耗尽
        assertEquals(2, WebSearchBudget.searchesSinceLastUser(history, SEARCH));
    }

    @Test
    @DisplayName("只数 web_search, 抓取与其它工具不算")
    void countsOnlySearchTool() {
        List<Message> history = new ArrayList<>();
        history.add(user());
        history.add(assistantCall(SEARCH, 2, "web_extract", 3, "knowledge_base_search", 1));

        assertEquals(2, WebSearchBudget.searchesSinceLastUser(history, SEARCH));
    }

    @Test
    @DisplayName("还没有任何 user 消息时也能数(不会因为找不到边界就返回 0)")
    void countsWhenNoUserBoundary() {
        List<Message> history = new ArrayList<>();
        history.add(assistantCall(SEARCH, 3));

        assertEquals(3, WebSearchBudget.searchesSinceLastUser(history, SEARCH));
    }

    @Test
    @DisplayName("空/ null 历史返回 0, 不抛异常")
    void handlesEmptyHistory() {
        assertEquals(0, WebSearchBudget.searchesSinceLastUser(null, SEARCH));
        assertEquals(0, WebSearchBudget.searchesSinceLastUser(List.of(), SEARCH));
    }

    @Test
    @DisplayName("去重来源数: 同一 URL 反复出现只算一条")
    void distinctSourcesDedupes() {
        List<Map<String, Object>> sources = List.of(
                src("https://a.com/1"), src("https://a.com/1"), src("https://b.com/2"),
                src("https://a.com/1"));

        assertEquals(2, WebSearchBudget.distinctSources(sources),
                "4 条结果但只有 2 个不同来源 —— 这才是真实信息量");
    }

    @Test
    @DisplayName("缺 url 的条目不计入(否则脏数据会虚高饱和判断)")
    void distinctSourcesSkipsMissingUrl() {
        List<Map<String, Object>> sources = List.of(
                src("https://a.com/1"), src(null), src(""));

        assertEquals(1, WebSearchBudget.distinctSources(sources));
    }

    @Test
    @DisplayName("空来源返回 0")
    void distinctSourcesHandlesEmpty() {
        assertEquals(0, WebSearchBudget.distinctSources(null));
        assertEquals(0, WebSearchBudget.distinctSources(List.of()));
    }

    // ===== 收敛判定(连续无新增) =====

    @Test
    @DisplayName("搜索失败不计入连续无新增: 一次 DNS 故障不该被当成「关键词已收敛」")
    void failedSearchDoesNotCountAsConvergence() {
        int streak = WebSearchBudget.nextNoGainStreak(0, false, 5, 5);
        assertEquals(0, streak, "没拿到来源是外部故障, 不是收敛证据");

        assertFalse(WebSearchBudget.noGainConverged(streak),
                "否则一次网络抖动就会把整次联网检索收口");
    }

    @Test
    @DisplayName("失败也不清零: 已有的一次无新增仍保留, 但换词重试照样允许")
    void failureKeepsExistingStreak() {
        // 已有 1 次"真的搜了但没新站点"
        int streak = WebSearchBudget.nextNoGainStreak(1, false, 5, 5);
        assertEquals(1, streak, "失败当作「这一次没发生」, 不清零也不累加");
        assertFalse(WebSearchBudget.noGainConverged(streak), "还不到阈值, 允许换词再试");

        // 再来一次确实执行了但仍无新站点 → 收敛
        streak = WebSearchBudget.nextNoGainStreak(streak, true, 5, 5);
        assertEquals(2, streak);
        assertTrue(WebSearchBudget.noGainConverged(streak));
    }

    @Test
    @DisplayName("带回新站点即清零: 收敛计数必须是「连续」而不是「累计」")
    void newSourceResetsStreak() {
        assertEquals(0, WebSearchBudget.nextNoGainStreak(2, true, 5, 9),
                "本轮真的推进了, 之前的无新增记录作废");
    }

    @Test
    @DisplayName("第一次无新增还不算收敛: 单次撞上同批转载站点很常见")
    void singleNoGainIsNotConverged() {
        int streak = WebSearchBudget.nextNoGainStreak(0, true, 5, 5);
        assertEquals(1, streak);
        assertFalse(WebSearchBudget.noGainConverged(streak));
    }

    // ===== 可达性熔断 =====

    @Test
    @DisplayName("连续 3 次请求失败才熔断: 单次超时不值得直接放弃")
    void breakerTripsAfterThreeFailures() {
        int s = WebSearchBudget.nextFailureStreak(0, true);
        assertEquals(1, s);
        assertFalse(WebSearchBudget.webUnavailable(s), "一次失败可能只是偶发超时");

        s = WebSearchBudget.nextFailureStreak(s, true);
        assertEquals(2, s);
        assertFalse(WebSearchBudget.webUnavailable(s));

        s = WebSearchBudget.nextFailureStreak(s, true);
        assertEquals(3, s);
        assertTrue(WebSearchBudget.webUnavailable(s), "3 次已等掉 15 秒, 该收手了");
    }

    @Test
    @DisplayName("上游返回 0 条不算失败: 链路是通的, 只是这次没内容")
    void emptyResultsResetFailureStreak() {
        int s = WebSearchBudget.nextFailureStreak(2, false);
        assertEquals(0, s,
                "NO_RESULTS / ALL_FILTERED 说明请求发出去也收到了响应, 网络是好的");
        assertFalse(WebSearchBudget.webUnavailable(s));
    }

    @Test
    @DisplayName("熔断与收敛互不干扰: 失败不清零收敛计数, 也不累加")
    void failureAndNoGainAreIndependent() {
        // 网络挂了 → failureStreak 涨, noGainStreak 不动(那不算收敛证据)
        assertEquals(0, WebSearchBudget.nextNoGainStreak(0, false, 3, 3),
                "请求异常不该被当成「关键词已收敛」");
        // 搜到了但没有新站点 → noGainStreak 涨, failureStreak 清零(链路正常)
        assertEquals(0, WebSearchBudget.nextFailureStreak(2, false));
        assertEquals(1, WebSearchBudget.nextNoGainStreak(0, true, 3, 3));
    }

    // ===== 抓取篇数分配 =====

    @Test
    @DisplayName("一轮 3 次搜索共享 6 篇预算: 依次 3/2/1, 每次都有正文")
    void extractTakeKeepsLastSearchAlive() {
        int budget = 6;
        int topN = 3;
        int searchCalls = 3;

        int t1 = WebSearchBudget.extractTake(budget, searchCalls - 1, topN);
        assertEquals(3, t1, "第 1 次: 后面还有 2 次, 预留 2 篇后仍有 4 篇可用");
        budget -= t1;

        int t2 = WebSearchBudget.extractTake(budget, searchCalls - 2, topN);
        assertEquals(2, t2, "第 2 次: 后面还有 1 次, 只能用 2 篇");
        budget -= t2;

        int t3 = WebSearchBudget.extractTake(budget, 0, topN);
        assertEquals(1, t3, "第 3 次: 旧实现这里是 0(预算被前两次吃光), 也是搜了没读的根因");
        budget -= t3;

        assertEquals(0, budget, "三次合计正好用完 6 篇, 总量没有放大");
    }

    @Test
    @DisplayName("单次搜索: 直接给满 topN, 不受保底逻辑影响")
    void extractTakeSingleSearch() {
        assertEquals(3, WebSearchBudget.extractTake(6, 0, 3));
    }

    @Test
    @DisplayName("预算为 0 时不抓")
    void extractTakeNoBudget() {
        assertEquals(0, WebSearchBudget.extractTake(0, 0, 3));
        assertEquals(0, WebSearchBudget.extractTake(0, 3, 3));
    }

    @Test
    @DisplayName("搜索次数多于预算: 仍保证每次至少 1 篇")
    void extractTakeMoreSearchesThanBudget() {
        // 4 次搜索只有 3 篇预算: 每次至少 1 篇, 允许超支 1 篇(比"有人一篇都拿不到"更可接受)
        assertEquals(1, WebSearchBudget.extractTake(3, 3, 3));
        assertEquals(1, WebSearchBudget.extractTake(1, 2, 3));
    }

    // ===== 辅助 =====

    private static Message user() {
        Message m = new Message();
        m.setRole("user");
        m.setContent("问题");
        return m;
    }

    /** 构造一条带若干工具调用的 assistant 消息; 参数是 name, count, name, count … */
    private static Message assistantCall(Object... nameCountPairs) {
        List<Map<String, Object>> calls = new ArrayList<>();
        for (int i = 0; i < nameCountPairs.length; i += 2) {
            String name = String.valueOf(nameCountPairs[i]);
            int count = (Integer) nameCountPairs[i + 1];
            for (int k = 0; k < count; k++) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("id", name + "-" + k + "-" + System.nanoTime());
                c.put("name", name);
                c.put("arguments", "{}");
                calls.add(c);
            }
        }
        Message m = new Message();
        m.setRole("assistant");
        m.setToolCalls(calls);
        return m;
    }

    private static Map<String, Object> src(String url) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (url != null) {
            m.put("url", url);
        }
        return m;
    }
}
