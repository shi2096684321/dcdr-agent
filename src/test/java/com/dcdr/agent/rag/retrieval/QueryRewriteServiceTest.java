package com.dcdr.agent.rag.retrieval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 改写的触发条件: 只有"依赖上文"的句子才值得多花一次模型调用。
 *
 * <p>两类信号: 含指代/省略词, 或短到无法独立成句。漏判的代价是检索打偏(静默),
 * 误判的代价是每轮多一次小调用 —— 所以边界要刻意收紧, 但"短句"这条不能省:
 * 「多少钱」「怎么弄」这类看着完整, 离开上文根本检索不到东西。
 */
class QueryRewriteServiceTest {

    @Test
    @DisplayName("含指代或过短 → 需要改写")
    void needsRewriteForDependentUtterance() {
        assertTrue(QueryRewriteService.needsRewrite("它的价格呢"));
        assertTrue(QueryRewriteService.needsRewrite("这个怎么配置"));
        assertTrue(QueryRewriteService.needsRewrite("多少钱"));
        assertTrue(QueryRewriteService.needsRewrite("上述条款还有效吗"));
    }

    @Test
    @DisplayName("可独立成句的问题不改写, 空白/空值不触发")
    void skipsStandaloneQuestion() {
        assertFalse(QueryRewriteService.needsRewrite("介绍一下产品的整体架构"));
        assertFalse(QueryRewriteService.needsRewrite("报销标准是多少"));
        assertFalse(QueryRewriteService.needsRewrite(""));
        assertFalse(QueryRewriteService.needsRewrite(null));
    }
}
