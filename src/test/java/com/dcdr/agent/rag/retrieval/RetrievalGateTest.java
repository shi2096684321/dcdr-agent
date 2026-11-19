package com.dcdr.agent.rag.retrieval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检索门控的契约: 只拦"与资料无关"的寒暄。
 *
 * <p>最容易被改坏的一条是 {@code 继续} —— 看着像"不需要再查", 但检索片段只活在当轮、
 * 不落库, 跳过检索等于让模型空手作答。这条断言就是防止后人"优化"成拦掉它。
 */
class RetrievalGateTest {

    @Test
    @DisplayName("寒暄/致谢/确认/空消息 → 不检索")
    void skipsSmallTalk() {
        assertFalse(RetrievalGate.shouldRetrieve("谢谢"));
        assertFalse(RetrievalGate.shouldRetrieve("好的！"));
        assertFalse(RetrievalGate.shouldRetrieve("ok"));
        assertFalse(RetrievalGate.shouldRetrieve("👍"));
        assertFalse(RetrievalGate.shouldRetrieve("   "));
        assertFalse(RetrievalGate.shouldRetrieve(null));
    }

    @Test
    @DisplayName("真问题一律检索: 包括「继续」和单字问题")
    void keepsRealQuestions() {
        assertTrue(RetrievalGate.shouldRetrieve("报销流程是什么"));
        // 上文延续必须查: 片段不落库, 不查就等于没有资料
        assertTrue(RetrievalGate.shouldRetrieve("继续"));
        // 去掉标点只剩一个字, 但仍是真问题(不能按长度一刀切)
        assertTrue(RetrievalGate.shouldRetrieve("税？"));
        // 整句相等才算寒暄: 带了后半句就是新问题
        assertTrue(RetrievalGate.shouldRetrieve("谢谢，那报销流程呢"));
    }
}
