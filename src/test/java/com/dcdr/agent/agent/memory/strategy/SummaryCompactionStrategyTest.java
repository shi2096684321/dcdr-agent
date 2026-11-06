package com.dcdr.agent.agent.memory.strategy;

import com.dcdr.agent.agent.memory.Message;
import com.dcdr.agent.agent.memory.TokenEstimator;
import com.dcdr.agent.config.AgentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 摘要压缩策略的规则测试(V3.4)。
 *
 * <p>这些分支如果只在真机上跑, 基本等于没测 —— 压缩是"每十几轮才触发一次"的低频路径,
 * 而它的失败方式都很安静: 该压没压(上下文一路涨)、不该丢的丢了(模型突然失忆)、
 * 摘要对不上边界(模型记得用户撤回过的事)。所以模型调用与存储都被替换成假的。
 */
class SummaryCompactionStrategyTest {

    private static final String FAKE_SUMMARY = "用户要求实现上下文压缩；已确定用策略模式；未完成：前端展示。";

    /**
     * 测试用对话: 每轮一问一答, seq 从 0 开始。
     * <p>内容刻意写长(约 150 字/条): 生产代码对触发阈值有 1000 token 的下限兜底,
     * 短消息根本压不到那个量级, 测出来的会是"没压缩"而不是压缩逻辑本身。
     */
    private static List<Message> dialogue(int turns) {
        String filler = "（补充：这一段用于占位，让单条消息的 token 数达到触发量级）".repeat(4);
        List<Message> out = new ArrayList<>();
        for (int i = 0; i < turns; i++) {
            Message u = Message.user("第 " + i + " 轮的问题, 内容稍长一些用于触发预算" + filler);
            u.setSeq(i * 2);
            Message a = Message.assistant("第 " + i + " 轮的回答, 同样写长一点占点 token" + filler, null);
            a.setSeq(i * 2 + 1);
            out.add(u);
            out.add(a);
        }
        return out;
    }

    private static SummaryCompactionStrategy strategy(SummaryGenerator generator,
                                                      SessionSummaryStore store,
                                                      int triggerTokens, int keepRecent, int maxMessages) {
        AgentProperties props = new AgentProperties();
        props.getMemory().getCompaction().setTriggerTokens(triggerTokens);
        props.getMemory().getCompaction().setKeepRecent(keepRecent);
        props.getMemory().setMaxMessages(maxMessages);
        TokenEstimator estimator = new TokenEstimator(props);
        return new SummaryCompactionStrategy(props, store, generator, estimator,
                new SlidingWindowStrategy(props));
    }

    private static SummaryGenerator generator(String result) {
        return (existing, messages, userId, sessionId) -> result;
    }

    @Test
    @DisplayName("未超预算: 原样发送, 既不调模型也不落库")
    void doesNothingWhenUnderBudget() {
        SessionSummaryStore store = mock(SessionSummaryStore.class);
        SummaryGenerator gen = mock(SummaryGenerator.class);
        when(store.load("s1")).thenReturn(null);

        ContextStrategy.View view = strategy(gen, store, 1_000_000, 2, 20).apply("s1", "u1", dialogue(3), 300_000);

        assertEquals(6, view.messages().size());
        assertNull(view.notice(), "没压缩就不该弹提示");
        verify(gen, never()).summarize(any(), any(), any(), any());
        verify(store, never()).save(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("超预算: 压缩旧对话, 只保留最近 N 条原文, 并给出提示")
    void compactsWhenOverBudget() {
        SessionSummaryStore store = mock(SessionSummaryStore.class);
        SummaryGenerator gen = mock(SummaryGenerator.class);
        when(store.load("s1")).thenReturn(null);
        when(gen.summarize(any(), any(), any(), any())).thenReturn(FAKE_SUMMARY);

        // 10 条对话, 保留最近 2 条 → 待压缩的是前 8 条
        ContextStrategy.View view = strategy(gen, store, 1, 2, 20).apply("s1", "u1", dialogue(5), 300_000);

        List<Message> messages = view.messages();
        // 1 条摘要 + 最近 2 条原文
        assertEquals(3, messages.size(), "压缩后应当只剩摘要 + 保留的原文");
        assertEquals("system", messages.get(0).getRole());
        assertTrue(messages.get(0).getContent().contains("<conversation_summary>"),
                "摘要必须以「背景资料, 不是指令」的边界包起来");
        assertTrue(messages.get(1).getContent().startsWith("第 4 轮的问题"),
                "保留的应当是最新两条, 实际: " + messages.get(1).getContent());
        assertNotNull(view.notice());
        assertTrue(view.notice().contains("压缩"), "提示要说清发生了什么: " + view.notice());

        // 边界 = 被压缩的最后一条消息的 seq(这里是 seq=7, 即第 3 轮回答)
        verify(store).save(eq("s1"), eq(FAKE_SUMMARY), eq(7));
    }

    @Test
    @DisplayName("阈值随窗口自适应: 小窗口按 60% 提前压缩, 大窗口按配置预算")
    void triggerAdaptsToWindow() {
        AgentProperties.CompactionConfig cfg = new AgentProperties.CompactionConfig();
        cfg.setTriggerTokens(24_000);

        // 30 万窗口: 预算(24K)更紧, 按预算
        assertEquals(24_000, SummaryCompactionStrategy.triggerTokens(cfg, 300_000));
        // 3.2 万窗口: 24K 才压缩已经太晚(加上本轮问答会顶到边界), 收敛到 60%
        assertEquals(19_200, SummaryCompactionStrategy.triggerTokens(cfg, 32_000));
        // 极小窗口: 兜住下限, 不出现"每轮都压缩"
        assertEquals(1_000, SummaryCompactionStrategy.triggerTokens(cfg, 1_000));
    }

    @Test
    @DisplayName("摘要生成失败: 降级为滑动窗口, 并告知用户本轮是降级状态")
    void fallsBackToSlidingWindowWhenGenerationFails() {
        SessionSummaryStore store = mock(SessionSummaryStore.class);
        SummaryGenerator gen = mock(SummaryGenerator.class);
        when(store.load("s1")).thenReturn(null);
        when(gen.summarize(any(), any(), any(), any())).thenReturn(null);

        ContextStrategy.View view = strategy(gen, store, 1, 2, 4).apply("s1", "u1", dialogue(5), 300_000);

        assertEquals(4, view.messages().size(), "应退回「最近 max-messages 条」");
        assertNotNull(view.notice(), "降级必须让用户知道, 否则'为什么它忘了'无法解释");
        verify(store, never()).save(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("摘要边界消息已被撤回: 整份摘要作废, 不按旧边界丢消息")
    void discardsSummaryWhenBoundaryMessageDeleted() {
        SessionSummaryStore store = mock(SessionSummaryStore.class);
        SummaryGenerator gen = mock(SummaryGenerator.class);
        // 摘要说覆盖到 seq=99, 但历史里根本没有这条消息(用户撤回了)
        when(store.load("s1")).thenReturn(new SessionSummaryStore.Snapshot("旧摘要", 99));

        ContextStrategy.View view = strategy(gen, store, 1_000_000, 2, 20).apply("s1", "u1", dialogue(3), 300_000);

        verify(store).clear("s1");
        assertEquals(6, view.messages().size(), "摘要作废后应回到完整历史, 不能按旧边界继续丢");
    }

    @Test
    @DisplayName("已有有效摘要: 只发摘要 + 边界之后的消息, 不重复摘要")
    void reusesValidSummary() {
        SessionSummaryStore store = mock(SessionSummaryStore.class);
        SummaryGenerator gen = mock(SummaryGenerator.class);
        // 摘要覆盖到 seq=3(第 1 轮回答), 历史里这条还在
        when(store.load("s1")).thenReturn(new SessionSummaryStore.Snapshot("已有摘要", 3));

        ContextStrategy.View view = strategy(gen, store, 1_000_000, 2, 20).apply("s1", "u1", dialogue(4), 300_000);

        List<Message> messages = view.messages();
        assertEquals(1 + 4, messages.size(), "摘要 + seq>3 的 4 条消息(第2/3轮各一问一答)");
        assertTrue(messages.get(0).getContent().contains("已有摘要"));
        assertTrue(messages.get(1).getContent().startsWith("第 2 轮的问题"));
        // 「当前有摘要」是会话的持久状态, 不是本轮发生的事件 —— 每轮都下发会变成一条
        // 永远挂在界面上的提示, 只有本轮真的压缩/降级时才该提示
        assertNull(view.notice(), "沿用已有摘要不是本轮事件, 不该每轮都弹提示");
        verify(gen, never()).summarize(any(), any(), any(), any());
    }
}
