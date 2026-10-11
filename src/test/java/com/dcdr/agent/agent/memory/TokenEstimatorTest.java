package com.dcdr.agent.agent.memory;

import com.dcdr.agent.config.AgentProperties;
import com.dcdr.agent.dto.ChatResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TokenEstimator} 单元测试(V3.2)。
 *
 * <p>纯单元测试: jtokkit 的 {@code Encoding} 无状态且线程安全, 构造只读两个配置值,
 * 无需 Spring 上下文、无 IO(不调用任何模型)。
 *
 * <p><b>为什么值得测</b>: 它决定两件事 —— "是否提示用户开启新对话", 以及"模型未返回 usage 时
 * 记账多少 token"。前者错报会让用户被无谓打断, 后者错报会污染成本统计。
 * 而它的实现里有一批<b>容易漏算</b>的字段(思维链、工具调用的结构化 JSON、工具名与 tool_call id),
 * 这些恰恰是 agentic 场景下 token 消耗的大头 —— 漏算会让"接近上限"的判定长期偏乐观。
 */
class TokenEstimatorTest {

    private static TokenEstimator estimator(int limit, double warnRatio) {
        AgentProperties properties = new AgentProperties();
        properties.getMemory().setContextTokenLimit(limit);
        properties.getMemory().setContextWarnRatio(warnRatio);
        return new TokenEstimator(properties);
    }

    private static TokenEstimator defaultEstimator() {
        return estimator(8000, 0.8);
    }

    // ==================================================================
    //  单段文本估算
    // ==================================================================

    @Nested
    @DisplayName("estimate(String)")
    class EstimateText {

        @Test
        @DisplayName("null / 空串返回 0")
        void blankReturnsZero() {
            TokenEstimator estimator = defaultEstimator();

            assertEquals(0, estimator.estimate((String) null));
            assertEquals(0, estimator.estimate(""));
        }

        @Test
        @DisplayName("非空文本给出正数")
        void nonBlankReturnsPositive() {
            assertTrue(defaultEstimator().estimate("hello world") > 0);
        }

        @Test
        @DisplayName("更长文本消耗更多 token(单调性)")
        void longerTextCostsMore() {
            TokenEstimator estimator = defaultEstimator();
            String shortText = "hello";
            String longText = "hello".repeat(50);

            assertTrue(estimator.estimate(longText) > estimator.estimate(shortText));
        }

        @Test
        @DisplayName("同一输入结果稳定(BPE 分词确定性)")
        void deterministic() {
            TokenEstimator estimator = defaultEstimator();

            assertEquals(estimator.estimate("一致性检查"), estimator.estimate("一致性检查"));
        }

        @Test
        @DisplayName("中文同样能被分词(编码器支持多语言)")
        void handlesChinese() {
            assertTrue(defaultEstimator().estimate("这是一段中文测试文本") > 0);
        }
    }

    // ==================================================================
    //  消息列表估算
    // ==================================================================

    @Nested
    @DisplayName("estimate(List<Message>)")
    class EstimateMessages {

        @Test
        @DisplayName("空列表返回 0")
        void emptyListIsZero() {
            assertEquals(0, defaultEstimator().estimate(List.of()));
        }

        @Test
        @DisplayName("单条文本消息: 等于其正文的估算值")
        void singleTextMessage() {
            TokenEstimator estimator = defaultEstimator();
            String content = "用户的一条提问";

            assertEquals(estimator.estimate(content),
                    estimator.estimate(List.of(Message.user(content))));
        }

        @Test
        @DisplayName("多条消息累加")
        void messagesAccumulate() {
            TokenEstimator estimator = defaultEstimator();
            int one = estimator.estimate(List.of(Message.user("第一条")));
            int two = estimator.estimate(List.of(Message.user("第一条"), Message.user("第二条")));

            assertTrue(two > one);
        }

        @Test
        @DisplayName("思维链(reasoningContent)也计入 —— 漏算会让上限判定偏乐观")
        void countsReasoningContent() {
            TokenEstimator estimator = defaultEstimator();

            int withoutReasoning = estimator.estimate(
                    List.of(Message.assistant("答案", null, "qwen")));
            int withReasoning = estimator.estimate(
                    List.of(Message.assistant("答案", "这是一段较长的推理过程内容", "qwen")));

            assertTrue(withReasoning > withoutReasoning);
        }

        @Test
        @DisplayName("工具调用结构(tool_calls)也计入")
        void countsToolCalls() {
            TokenEstimator estimator = defaultEstimator();

            Message withTools = Message.assistant("调用工具", "qwen");
            withTools.setToolCalls(List.of(Map.of(
                    "id", "call_1",
                    "type", "function",
                    "function", Map.of("name", "web_search", "arguments", "{\"query\":\"x\"}"))));

            int plain = estimator.estimate(List.of(Message.assistant("调用工具", "qwen")));
            int withToolCalls = estimator.estimate(List.of(withTools));

            assertTrue(withToolCalls > plain, "tool_calls 的 JSON 同样占用上下文");
        }

        @Test
        @DisplayName("工具名与 tool_call id 也计入(它们随每轮工具往返出现)")
        void countsToolNameAndCallId() {
            TokenEstimator estimator = defaultEstimator();

            Message plain = new Message("tool", "结果内容", null);
            Message enriched = new Message("tool", "结果内容", null);
            enriched.setName("web_search");
            enriched.setToolCallId("call_abcdef123456");

            assertTrue(estimator.estimate(List.of(enriched)) > estimator.estimate(List.of(plain)));
        }
    }

    // ==================================================================
    //  阈值判定
    // ==================================================================

    @Nested
    @DisplayName("isNearLimit")
    class NearLimit {

        @Test
        @DisplayName("短上下文未达阈值 → false")
        void shortContextIsNotNearLimit() {
            TokenEstimator estimator = estimator(100000, 1.0);

            assertFalse(estimator.isNearLimit(List.of(Message.user("短消息"))));
        }

        @Test
        @DisplayName("阈值是闭区间 >=: 恰好等于 limit×ratio 即判定为接近上限")
        void exactlyAtThresholdCountsAsNearLimit() {
            String content = "用来精确测定阈值的文本";
            int exact = defaultEstimator().estimate(content);

            // 令 limit×ratio 恰好等于该文本的估算值
            TokenEstimator atThreshold = estimator(exact, 1.0);
            assertTrue(atThreshold.isNearLimit(List.of(Message.user(content))),
                    "判定用的是 >=, 而非 >");

            // 阈值抬高 1 个 token 后即不满足
            TokenEstimator aboveThreshold = estimator(exact + 1, 1.0);
            assertFalse(aboveThreshold.isNearLimit(List.of(Message.user(content))));
        }
    }

    // ==================================================================
    //  usage 收敛入口
    // ==================================================================

    @Nested
    @DisplayName("resolveUsage")
    class ResolveUsage {

        @Test
        @DisplayName("模型返回了真实 usage 时直接透传(同一对象, 不重算)")
        void realUsageWins() {
            TokenEstimator estimator = defaultEstimator();
            ChatResponse.TokenUsage real = new ChatResponse.TokenUsage();
            real.setPrompt(100);
            real.setCompletion(50);
            real.setTotal(150);

            ChatResponse.TokenUsage resolved =
                    estimator.resolveUsage(real, List.of(Message.user("任意上下文")), "回答", null);

            assertSame(real, resolved, "有真实 usage 就不应走估算路径");
        }

        @Test
        @DisplayName("usage 为空或 total 为 0 时按 BPE 估算: prompt=上下文, completion=正文+思维链")
        void fallsBackToEstimate() {
            TokenEstimator estimator = defaultEstimator();
            List<Message> context = List.of(Message.user("上下文内容"));
            String content = "生成的正文";
            String reasoning = "思考过程";

            ChatResponse.TokenUsage resolved = estimator.resolveUsage(null, context, content, reasoning);

            assertEquals(estimator.estimate(context), resolved.getPrompt());
            assertEquals(estimator.estimate(content) + estimator.estimate(reasoning), resolved.getCompletion());
            assertEquals(resolved.getPrompt() + resolved.getCompletion(), resolved.getTotal());
        }

        @Test
        @DisplayName("usage 存在但 total 为 0(流式中断)时同样走估算兜底")
        void zeroTotalUsageFallsBack() {
            TokenEstimator estimator = defaultEstimator();
            ChatResponse.TokenUsage empty = new ChatResponse.TokenUsage();
            empty.setTotal(0);

            ChatResponse.TokenUsage resolved =
                    estimator.resolveUsage(empty, List.of(Message.user("上下文")), "正文", null);

            assertTrue(resolved.getTotal() > 0, "total 为 0 说明模型未回报, 必须兜底估算");
        }
    }

    @Test
    @DisplayName("getContextTokenLimit 回显配置值")
    void exposesConfiguredLimit() {
        assertEquals(12345, estimator(12345, 0.5).getContextTokenLimit());
    }
}
