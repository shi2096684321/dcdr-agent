package com.dcdr.agent.tool.websearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link SearxngSearchProvider#sortByEngineScore(List)} 的口径测试。
 *
 * <p><b>为什么要有这一步</b>: DeepSeek 返回顺序即 {@code rerank_score} 降序, 保序即可;
 * 而 SearXNG 是多引擎聚合, 顺序是引擎间的混合次序, 不代表相关性 ——
 * 下游按"顺序取前 N 篇抓正文"时, 顺序错就等于抓错页面。
 */
class SearxngEngineRankTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("按引擎 score 降序重排")
    void sortsByScoreDescending() throws Exception {
        List<JsonNode> results = listOf(
                node("https://a.com/1", "第一条", 0.2),
                node("https://b.com/2", "第二条", 0.9),
                node("https://c.com/3", "第三条", 0.5));

        SearxngSearchProvider.sortByEngineScore(results);

        assertEquals("https://b.com/2", results.get(0).path("url").asText());
        assertEquals("https://c.com/3", results.get(1).path("url").asText());
        assertEquals("https://a.com/1", results.get(2).path("url").asText());
    }

    @Test
    @DisplayName("没有 score 字段时保持原有顺序(稳定排序)")
    void keepsOrderWithoutScore() throws Exception {
        List<JsonNode> results = listOf(
                node("https://a.com/1", "甲", null),
                node("https://b.com/2", "乙", null));

        SearxngSearchProvider.sortByEngineScore(results);

        assertEquals("https://a.com/1", results.get(0).path("url").asText(),
                "排序键全缺失时不能打乱顺序 —— 原顺序至少是引擎给出的次序");
        assertEquals("https://b.com/2", results.get(1).path("url").asText());
    }

    @Test
    @DisplayName("部分结果缺 score 时, 有分数的高分项排前面")
    void missingScoreRanksLast() throws Exception {
        List<JsonNode> results = listOf(
                node("https://a.com/1", "无分", null),
                node("https://b.com/2", "有分", 0.8));

        SearxngSearchProvider.sortByEngineScore(results);

        assertEquals("https://b.com/2", results.get(0).path("url").asText());
    }

    @Test
    @DisplayName("空列表/单条不炸")
    void handlesTrivialInput() {
        List<JsonNode> empty = new ArrayList<>();
        SearxngSearchProvider.sortByEngineScore(empty);
        SearxngSearchProvider.sortByEngineScore(null);
        assertEquals(0, empty.size());
    }

    // ===== 辅助 =====

    private static JsonNode node(String url, String title, Double score) {
        var obj = MAPPER.createObjectNode();
        obj.put("url", url);
        obj.put("title", title);
        if (score != null) {
            obj.put("score", score);
        }
        return obj;
    }

    private static List<JsonNode> listOf(JsonNode... nodes) {
        List<JsonNode> list = new ArrayList<>();
        for (JsonNode n : nodes) {
            list.add(n);
        }
        return list;
    }
}
