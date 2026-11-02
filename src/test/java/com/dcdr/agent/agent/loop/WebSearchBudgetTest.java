package com.dcdr.agent.agent.loop;

import com.dcdr.agent.agent.memory.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
