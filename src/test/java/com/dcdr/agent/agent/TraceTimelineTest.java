package com.dcdr.agent.agent;

import com.dcdr.agent.dto.ChatChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时间线（{@code Message.trace}）的记录口径测试。
 *
 * <p><b>锁的是一个显示层的真实偏差</b>: {@code reasoningContent} 存的是本轮所有轮次思考的拼接，
 * 只靠它前端切不出"想了 → 做了 → 又想了"，历史消息里工具只能整堆排在思考后面。
 * 这里记录的是"每段思考多少字符 + 接着调了哪个工具"，一旦切片长度算错，
 * 前端还原出来的文本会串段（把第二段的思考裁到第一段的位置上），比不记录更糟。
 */
class TraceTimelineTest {

    @Test
    @DisplayName("两轮工具: 思考切片与工具按发生顺序交替, 切片长度之和等于累计思考长度")
    void interleavesReasoningAndTools() {
        List<Map<String, Object>> trace = new ArrayList<>();
        int[] marked = new int[1];

        // 第 1 轮: 想了 120 字, 调了两个工具
        AgentOrchestrator.markTraceRound(trace, marked, 120,
                List.of(ChatChunk.toolCall("call_a", "workspace_read", "{}"),
                        ChatChunk.toolCall("call_b", "workspace_search", "{}")));
        // 第 2 轮: 又想了 80 字(累计 200), 调了一个工具
        AgentOrchestrator.markTraceRound(trace, marked, 200,
                List.of(ChatChunk.toolCall("call_c", "web_search", "{}")));
        // 最后一轮: 再想 30 字(累计 230), 没有工具
        AgentOrchestrator.markTraceTail(trace, marked, 230);

        assertEquals(List.of("reason", "tool", "tool", "reason", "tool", "reason"),
                kinds(trace), "顺序必须是 想→做→想→做→想, 而不是先全部想法再全部工具");
        assertEquals(120, n(trace.get(0)));
        assertEquals(80, n(trace.get(3)));
        assertEquals(30, n(trace.get(5)));
        assertEquals(230, totalReasonChars(trace), "切片长度之和必须等于累计思考长度, 否则前端会串段");
        assertEquals("call_a", trace.get(1).get("id"));
        assertEquals("call_c", trace.get(4).get("id"));
    }

    @Test
    @DisplayName("模型这一轮没思考: 不记空切片, 免得前端多出一个空段落")
    void skipsEmptyReasoning() {
        List<Map<String, Object>> trace = new ArrayList<>();
        int[] marked = new int[1];

        AgentOrchestrator.markTraceRound(trace, marked, 0,
                List.of(ChatChunk.toolCall("call_a", "calculator", "{}")));
        AgentOrchestrator.markTraceTail(trace, marked, 0);

        assertEquals(List.of("tool"), kinds(trace));
    }

    @Test
    @DisplayName("收尾补记幂等: 重复调用不会把最后一段思考记两遍")
    void tailMarkIsIdempotent() {
        List<Map<String, Object>> trace = new ArrayList<>();
        int[] marked = new int[1];

        AgentOrchestrator.markTraceRound(trace, marked, 50, List.of());
        AgentOrchestrator.markTraceTail(trace, marked, 90);
        AgentOrchestrator.markTraceTail(trace, marked, 90);

        assertEquals(2, trace.size(), "只有两段思考: 50 字 + 40 字");
        assertEquals(90, totalReasonChars(trace));
    }

    @Test
    @DisplayName("中断场景: 只有思考没有工具时也留下时间线, 刷新后不会被当成老数据")
    void keepsReasoningOnlyTrace() {
        List<Map<String, Object>> trace = new ArrayList<>();
        int[] marked = new int[1];

        AgentOrchestrator.markTraceTail(trace, marked, 64);

        assertTrue(trace.size() == 1 && "reason".equals(trace.get(0).get("t")));
    }

    private static List<String> kinds(List<Map<String, Object>> trace) {
        return trace.stream().map(s -> String.valueOf(s.get("t"))).toList();
    }

    private static int n(Map<String, Object> step) {
        return ((Number) step.get("n")).intValue();
    }

    private static int totalReasonChars(List<Map<String, Object>> trace) {
        return trace.stream()
                .filter(s -> "reason".equals(s.get("t")))
                .mapToInt(TraceTimelineTest::n)
                .sum();
    }
}
