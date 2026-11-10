package com.dcdr.agent.agent.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolPairingRepair} 单元测试。
 *
 * <p>被测逻辑有一个容易踩反的地方: 修复结果<b>不落库</b>, 每次请求都会重算一遍。
 * 于是"答案从哪来"必须按位置判定 —— 只有「历史最后一条」的那个提问才用本轮输入作答,
 * 更早的提问要用它后面紧邻的那条用户消息。判反了的后果很隐蔽: 用户下一轮随口的
 * 一句话会把上一次的答案覆盖掉, 而模型看到的历史是"合理的", 没有任何报错。
 */
class ToolPairingRepairTest {

    @Test
    @DisplayName("无悬空调用时原样返回(同一对象, 不做无谓拷贝)")
    void noRepairWhenNothingPending() {
        List<Message> history = List.of(Message.user("你好"), Message.assistant("你好，有什么可以帮你？", null));

        List<Message> result = ToolPairingRepair.repair(history, "随便说说", null);

        assertSame(history, result);
    }

    @Test
    @DisplayName("提问是最后一条: 用本轮输入补出 tool 结果, 紧跟 assistant 之后")
    void answerLastPendingAsk() {
        List<Message> history = new ArrayList<>();
        history.add(Message.user("帮我部署"));
        history.add(askMessage("call_1"));

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("q1", List.of("docker"));

        List<Message> result = ToolPairingRepair.repair(history, "Docker Compose", answer);

        assertEquals(3, result.size());
        Message tool = result.get(2);
        assertEquals("tool", tool.getRole());
        assertEquals("call_1", tool.getToolCallId());
        assertEquals("ask_user", tool.getName());
        assertTrue(tool.getContent().contains("\"docker\""), tool.getContent());
        assertTrue(tool.getContent().contains("Docker Compose"), tool.getContent());
    }

    @Test
    @DisplayName("提问后面已有用户回复: 从那条回复取答案, 不被本轮新输入覆盖")
    void answerFromFollowingUserMessage() {
        List<Message> history = new ArrayList<>();
        history.add(Message.user("帮我部署"));
        history.add(askMessage("call_1"));
        history.add(Message.user("Docker Compose"));

        List<Message> result = ToolPairingRepair.repair(history, "顺便问下天气", null);

        Message tool = result.get(2);
        assertEquals("tool", tool.getRole());
        assertTrue(tool.getContent().contains("Docker Compose"), tool.getContent());
        assertTrue(!tool.getContent().contains("天气"), "更早的提问不该拿本轮输入当答案: " + tool.getContent());
    }

    @Test
    @DisplayName("提问之后紧跟的不是用户消息: 明确记为跳过, 不让模型脑补")
    void skipWhenFollowerIsNotUser() {
        List<Message> history = new ArrayList<>();
        history.add(askMessage("call_1"));
        history.add(Message.assistant("我先自己猜一个", null));

        List<Message> result = ToolPairingRepair.repair(history, "无关的话", null);

        Message tool = result.get(1);
        assertEquals("tool", tool.getRole());
        assertTrue(tool.getContent().contains("跳过"), tool.getContent());
    }

    @Test
    @DisplayName("非提问类悬空调用: 补『未执行』, 且每条调用都有配对")
    void nonAskMissingCalls() {
        List<Message> history = new ArrayList<>();
        history.add(Message.user("做个事"));
        history.add(assistantWithCalls(Map.<String, Object>of("id", "call_w", "name", "web_search", "arguments", "{}"),
                Map.<String, Object>of("id", "call_i", "name", "image_generation", "arguments", "{}")));

        List<Message> result = ToolPairingRepair.repair(history, "继续", null);

        // 两条调用各补一条 tool 结果, 顺序按 tool_calls 原序紧跟 assistant 之后
        assertEquals(4, result.size());
        assertEquals("call_w", result.get(2).getToolCallId());
        assertEquals("call_i", result.get(3).getToolCallId());
        assertTrue(result.get(2).getContent().contains("未执行"), result.get(2).getContent());
    }

    @Test
    @DisplayName("已有 tool 结果的调用不重复补")
    void skipAlreadyResolved() {
        List<Message> history = new ArrayList<>();
        history.add(assistantWithCalls(Map.<String, Object>of("id", "call_1", "name", "time", "arguments", "{}")));
        Message toolMsg = new Message("tool", "当前时间 12:00", null);
        toolMsg.setToolCallId("call_1");
        toolMsg.setName("time");
        history.add(toolMsg);

        List<Message> result = ToolPairingRepair.repair(history, "继续", null);

        assertSame(history, result);
    }

    @Test
    @DisplayName("结构化答案兼容 {questions:[{id,answers}]} 形态")
    void nestedAnswerShape() {
        List<Message> history = new ArrayList<>();
        history.add(askMessage("call_1"));

        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("questions", List.of(Map.of("id", "q1", "answers", List.of("mysql", "redis"))));

        List<Message> result = ToolPairingRepair.repair(history, "都行", nested);

        String payload = result.get(1).getContent();
        assertNotNull(payload);
        assertTrue(payload.contains("mysql") && payload.contains("redis"), payload);
    }

    /** 一条只带 ask_user 调用的 assistant 消息 */
    private static Message askMessage(String callId) {
        return assistantWithCalls(Map.<String, Object>of("id", callId, "name", "ask_user",
                "arguments", "{\"questions\":[{\"question\":\"用什么部署?\"}]}"));
    }

    @SafeVarargs
    private static Message assistantWithCalls(Map<String, Object>... calls) {
        Message m = new Message();
        m.setRole("assistant");
        m.setContent("我需要先确认一下。");
        m.setToolCalls(new ArrayList<>(List.of(calls)));
        return m;
    }
}
