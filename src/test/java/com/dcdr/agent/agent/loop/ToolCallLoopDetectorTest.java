package com.dcdr.agent.agent.loop;

import com.dcdr.agent.agent.memory.Message;
import com.dcdr.agent.dto.ChatChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具调用循环检测的口径测试。
 *
 * <p><b>为什么必须钉住</b>: 这个检测器的两个方向都会伤到用户 ——
 * 判松了等于没有(模型照样反复搜), 判严了会把"搜一次没搜到、换个说法再搜"误判成循环直接掐断。
 * 而两者在日志上都只表现为"某次调用被拦截", 从外部看不出是哪种。
 */
class ToolCallLoopDetectorTest {

    private static final String TOOL = "web_search";
    private static final String ARGS = "{\"query\":\"Spring Boot 3 最新版本\",\"maxResults\":8}";

    @Test
    @DisplayName("连续第 5 次相同调用才拦截, 前 4 次放行")
    void blocksOnlyAfterThreshold() {
        List<ChatChunk> calls = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            calls.add(ChatChunk.toolCall("c" + i, TOOL, ARGS));
        }

        Set<String> blocked = ToolCallLoopDetector.detectRepeats(List.of(), calls);

        assertEquals(Set.of("c5"), blocked, "只该拦第 5 次; 前 4 次是正常重试空间");
    }

    @Test
    @DisplayName("恰好 4 次相同: 不拦 —— 这是允许的上限")
    void fourTimesIsAllowed() {
        List<ChatChunk> calls = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            calls.add(ChatChunk.toolCall("c" + i, TOOL, ARGS));
        }

        assertTrue(ToolCallLoopDetector.detectRepeats(List.of(), calls).isEmpty());
    }

    @Test
    @DisplayName("参数键序不同但语义相同: 视为同一调用(否则归一化就白做了)")
    void treatsReorderedJsonAsSame() {
        List<ChatChunk> calls = new ArrayList<>();
        calls.add(ChatChunk.toolCall("c1", TOOL, "{\"query\":\"AI 监管\",\"maxResults\":8}"));
        calls.add(ChatChunk.toolCall("c2", TOOL, "{ \"maxResults\": 8, \"query\": \"AI 监管\" }"));
        calls.add(ChatChunk.toolCall("c3", TOOL, "{\"maxResults\":8,\"query\":\"AI 监管\"}"));
        calls.add(ChatChunk.toolCall("c4", TOOL, "{\"query\":\"AI 监管\",\"maxResults\":8}"));
        calls.add(ChatChunk.toolCall("c5", TOOL, "{ \"query\" : \"AI 监管\" , \"maxResults\" : 8 }"));

        Set<String> blocked = ToolCallLoopDetector.detectRepeats(List.of(), calls);

        assertEquals(Set.of("c5"), blocked, "同一语义的 5 种写法应连续累计, 而不是当成 5 个不同调用");
    }

    @Test
    @DisplayName("换了 query 就不算循环: 多角度检索是正常行为")
    void differentQueriesAreNotLoop() {
        List<ChatChunk> calls = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            calls.add(ChatChunk.toolCall("c" + i, TOOL, "{\"query\":\"主题 " + i + "\"}"));
        }

        assertTrue(ToolCallLoopDetector.detectRepeats(List.of(), calls).isEmpty(),
                "并行搜不同主题不能被当成循环掐断");
    }

    @Test
    @DisplayName("跨轮累计: 历史 3 次 + 本轮 2 次 = 第 5 次被拦")
    void countsAcrossRounds() {
        List<Message> history = List.of(assistantCall(TOOL, ARGS), assistantCall(TOOL, ARGS),
                assistantCall(TOOL, ARGS));
        List<ChatChunk> calls = List.of(
                ChatChunk.toolCall("c1", TOOL, ARGS),
                ChatChunk.toolCall("c2", TOOL, ARGS));

        Set<String> blocked = ToolCallLoopDetector.detectRepeats(history, calls);

        assertEquals(Set.of("c2"), blocked, "第 4 次放行、第 5 次拦截, 且计数要跨轮累计");
    }

    @Test
    @DisplayName("中间换过参数就重新计数: 连续 3 次 A → 1 次 B → 1 次 A 不算循环")
    void resetsWhenSignatureChanges() {
        List<Message> history = List.of(assistantCall(TOOL, "{\"query\":\"A\"}"),
                assistantCall(TOOL, "{\"query\":\"A\"}"),
                assistantCall(TOOL, "{\"query\":\"A\"}"),
                assistantCall(TOOL, "{\"query\":\"B\"}"));
        List<ChatChunk> calls = List.of(ChatChunk.toolCall("c1", TOOL, "{\"query\":\"A\"}"));

        assertTrue(ToolCallLoopDetector.detectRepeats(history, calls).isEmpty());
    }

    @Test
    @DisplayName("签名归一化: 空参数统一成 {}, 解析失败退回原文而不是抛异常")
    void signatureHandlesBadInput() {
        assertFalse(ToolCallLoopDetector.canonicalJson(null).isEmpty());
        assertEquals("{}", ToolCallLoopDetector.canonicalJson(""));
        assertEquals("{}", ToolCallLoopDetector.canonicalJson("   "));
        // 非法 JSON 不能让检测器抛异常打断对话, 原样返回(trim)即可
        assertEquals("not-json", ToolCallLoopDetector.canonicalJson("  not-json  "));
    }

    private static Message assistantCall(String tool, String args) {
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("id", "h-" + Math.abs((tool + args).hashCode()));
        call.put("name", tool);
        call.put("arguments", args);
        Message m = new Message();
        m.setRole("assistant");
        m.setToolCalls(List.of(call));
        return m;
    }
}
