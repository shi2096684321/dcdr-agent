package com.dcdr.agent.agent;

import com.dcdr.agent.agent.memory.Message;
import com.dcdr.agent.rag.tool.KnowledgeSearchTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 方案B（检索结果落成 tool 消息）的历史裁剪规则。
 *
 * <p>它守的是两条硬约束：
 * <ol>
 *   <li><b>只留最近 N 轮</b>：检索片段每轮一份、内容高度重叠，不裁会让上下文随对话线性膨胀；</li>
 *   <li><b>必须成对删</b>：assistant(tool_calls) 与对应 tool 结果一起删。只删一边，
 *       上游会以"tool_calls 缺少对应结果"直接 400 —— 这是最容易被后续改动破坏的地方。</li>
 * </ol>
 */
class RagToolPairTrimTest {

    private static Message user(String text) {
        Message m = new Message();
        m.setRole("user");
        m.setContent(text);
        return m;
    }

    private static Message assistantCall(String... ids) {
        Message m = new Message();
        m.setRole("assistant");
        m.setContent("");
        List<Map<String, Object>> calls = new ArrayList<>();
        for (String id : ids) {
            Map<String, Object> tc = new LinkedHashMap<>();
            tc.put("id", id);
            tc.put("name", KnowledgeSearchTool.NAME);
            tc.put("arguments", "{}");
            calls.add(tc);
        }
        m.setToolCalls(calls);
        return m;
    }

    private static Message toolResult(String callId, String content) {
        Message m = new Message();
        m.setRole("tool");
        m.setToolCallId(callId);
        m.setName(KnowledgeSearchTool.NAME);
        m.setContent(content);
        return m;
    }

    @Test
    @DisplayName("超过 keep 的旧检索对整对移除, 最近一轮保留")
    void trimsOldPairs() {
        List<Message> history = new ArrayList<>(List.of(
                user("第一问"), assistantCall("c1"), toolResult("c1", "旧片段"),
                user("第二问"), assistantCall("c2"), toolResult("c2", "新片段")));

        List<Message> out = AgentOrchestrator.trimRagToolPairs(history, 1);

        assertEquals(4, out.size());
        assertTrue(out.stream().noneMatch(m -> "c1".equals(m.getToolCallId())));
        assertTrue(out.stream().anyMatch(m -> "c2".equals(m.getToolCallId())));
        // 只剩两条 user + 一对新检索
        assertEquals("第一问", out.get(0).getContent());
        assertEquals("第二问", out.get(1).getContent());
    }

    @Test
    @DisplayName("混合调用只摘掉检索那一项, 其余调用与其结果保留")
    void keepsSiblingToolCalls() {
        Message mixed = new Message();
        mixed.setRole("assistant");
        mixed.setContent("");
        List<Map<String, Object>> calls = new ArrayList<>();
        Map<String, Object> rag = new LinkedHashMap<>();
        rag.put("id", "c1");
        rag.put("name", KnowledgeSearchTool.NAME);
        Map<String, Object> web = new LinkedHashMap<>();
        web.put("id", "w1");
        web.put("name", "web_search");
        calls.add(rag);
        calls.add(web);
        mixed.setToolCalls(calls);

        List<Message> history = new ArrayList<>(List.of(
                user("问"), mixed, toolResult("c1", "片段"), toolResult("w1", "网页"),
                assistantCall("c2"), toolResult("c2", "新片段")));

        List<Message> out = AgentOrchestrator.trimRagToolPairs(history, 1);

        Message keptAssistant = out.stream()
                .filter(m -> "assistant".equals(m.getRole()) && m.getToolCalls() != null
                        && m.getToolCalls().stream().anyMatch(tc -> "w1".equals(tc.get("id"))))
                .findFirst().orElseThrow();
        assertEquals(1, keptAssistant.getToolCalls().size());
        assertTrue(out.stream().anyMatch(m -> "w1".equals(m.getToolCallId())));
        assertTrue(out.stream().noneMatch(m -> "c1".equals(m.getToolCallId())));
    }

    @Test
    @DisplayName("未超过 keep 时原样返回(不拷贝, 调用方可靠引用相等省开销)")
    void returnsSameListWhenNothingToTrim() {
        List<Message> history = new ArrayList<>(List.of(
                user("问"), assistantCall("c1"), toolResult("c1", "片段")));
        assertSame(history, AgentOrchestrator.trimRagToolPairs(history, 1));
    }

    @Test
    @DisplayName("keep<=0 表示不裁剪: 历史只追加, 前缀缓存才不会被中途删除打断")
    void keepZeroMeansNoTrim() {
        List<Message> history = new ArrayList<>(List.of(
                user("问1"), assistantCall("c1"), toolResult("c1", "a"),
                user("问2"), assistantCall("c2"), toolResult("c2", "b")));
        assertSame(history, AgentOrchestrator.trimRagToolPairs(history, 0));
        assertSame(history, AgentOrchestrator.trimRagToolPairs(history, -1));
    }

    @Test
    @DisplayName("历史里的 chunkId 能收集出来, 供本轮增量去重")
    void collectsKnownChunkIds() {
        Message t = toolResult("c1", "片段");
        t.setSources(List.of(Map.of("chunkId", "k1"), Map.of("chunkId", "k2")));
        assertEquals(java.util.Set.of("k1", "k2"),
                AgentOrchestrator.knownRagChunkIds(List.of(t, user("问"))));
    }
}
