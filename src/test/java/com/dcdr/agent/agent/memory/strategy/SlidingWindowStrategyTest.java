package com.dcdr.agent.agent.memory.strategy;

import com.dcdr.agent.agent.memory.Message;
import com.dcdr.agent.config.AgentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 滑动窗口策略的规则测试(V3.4)。
 *
 * <p>它现在是"可选策略 + 摘要失败时的降级路径", 所以这里锁的其实是两件事:
 * 窗口规则本身没变(旧行为不能因为重构而悄悄改), 以及 system 消息永不占窗口配额
 * (占了的话会话级约束会在长对话里被挤掉 —— 这是最隐蔽的一类回归)。
 */
class SlidingWindowStrategyTest {

    private static SlidingWindowStrategy strategy(int maxMessages) {
        AgentProperties props = new AgentProperties();
        props.getMemory().setMaxMessages(maxMessages);
        return new SlidingWindowStrategy(props);
    }

    private static List<Message> dialogue(int turns) {
        List<Message> out = new ArrayList<>();
        for (int i = 0; i < turns; i++) {
            Message u = Message.user("问题 " + i);
            u.setSeq(i * 2);
            Message a = Message.assistant("回答 " + i, null);
            a.setSeq(i * 2 + 1);
            out.add(u);
            out.add(a);
        }
        return out;
    }

    @Test
    @DisplayName("未超窗口: 原样返回且不产生提示")
    void passesThroughWhenUnderLimit() {
        List<Message> history = dialogue(2);
        ContextStrategy.View view = strategy(6).apply("s1", null, history, 300_000);

        assertEquals(4, view.messages().size());
        assertNull(view.notice(), "没裁剪就不该弹提示");
    }

    @Test
    @DisplayName("超窗口: 只保留最近 N 条非 system 消息, 并提示省略了多少")
    void keepsLatestMessagesAndNotifies() {
        ContextStrategy.View view = strategy(4).apply("s1", null, dialogue(5), 300_000);

        assertEquals(4, view.messages().size());
        assertEquals("问题 3", view.messages().get(0).getContent(), "应保留最近 4 条: 问题3/回答3/问题4/回答4");
        assertNotNull(view.notice());
        assertTrue(view.notice().contains("6"), "应告知省略了 10-4=6 条: " + view.notice());
    }

    @Test
    @DisplayName("system 消息不占窗口配额 —— 占了会把会话级约束挤出长对话")
    void systemMessagesNeverConsumedByWindow() {
        List<Message> history = new ArrayList<>();
        Message sys = Message.system("平台收敛约束");
        history.add(sys);
        history.addAll(dialogue(5));

        ContextStrategy.View view = strategy(2).apply("s1", null, history, 300_000);

        assertEquals(1 + 2, view.messages().size());
        assertEquals("system", view.messages().get(0).getRole(), "system 必须在最前且被保留");
    }

    @Test
    @DisplayName("空历史: 返回空列表, 不抛异常")
    void handlesEmptyHistory() {
        ContextStrategy.View view = strategy(4).apply("s1", null, List.of(), 300_000);
        assertTrue(view.messages().isEmpty());
        assertNull(view.notice());
    }
}
