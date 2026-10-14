package com.dcdr.agent.agent.prompt;

import com.dcdr.agent.config.ContextBudgetProperties;
import com.dcdr.agent.rag.config.RagProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 背景信息统一预算的规则测试。
 *
 * <p>被测算的是"记忆与检索片段抢上下文时先砍谁"这件事。它此前没有规则 ——
 * 两者各有一套上限、互不感知, 叠加时全靠默认值恰好不冲突。
 *
 * <p>这类逻辑出错的后果是<b>静默的</b>: 记忆被挤没、或检索片段被放开到失控,
 * 都不会报错, 也不会让任何测试变红 —— 只能靠这里显式锁定。
 */
class BackgroundContextBudgetTest {

    private static BackgroundContextBudget budget(int total, int memoryMax, int ragOwn) {
        ContextBudgetProperties p = new ContextBudgetProperties();
        p.setBackgroundMaxTotalChars(total);
        p.setMemoryMaxChars(memoryMax);

        RagProperties.InjectConfig inject = new RagProperties.InjectConfig();
        inject.setMaxTotalChars(ragOwn);
        RagProperties rag = new RagProperties();
        rag.setInject(inject);
        return new BackgroundContextBudget(p, rag);
    }

    @Test
    @DisplayName("默认配置: 记忆占用不大时, 检索片段拿到的就是它自己配置的额度(行为不变)")
    void keepsRagOwnBudgetWhenMemoryIsLight() {
        BackgroundContextBudget b = budget(9000, 6000, 6000);
        assertEquals(6000, b.ragBudget(1500),
                "9000 - 1500 = 7500 > 6000, 应以检索自己的 6000 为准 —— "
                        + "统一预算不该在常态下改变既有行为");
    }

    @Test
    @DisplayName("记忆变重时才收紧: 检索片段只能拿剩余额度")
    void tightensWhenMemoryGrows() {
        BackgroundContextBudget b = budget(9000, 6000, 6000);
        assertEquals(3000, b.ragBudget(6000), "9000 - 6000 = 3000 < 6000 → 取 3000");
    }

    @Test
    @DisplayName("记忆吃光额度时返回 1 而不是 0 —— 0 在注入侧的含义是「不限制」")
    void neverReturnsZero() {
        BackgroundContextBudget b = budget(9000, 6000, 6000);
        assertEquals(1, b.ragBudget(20000),
                "返回 0 会被注入侧解释成「不限制」, 与本意完全相反 —— 这是本类最容易踩的坑");
        assertEquals(1, b.ragBudget(9000), "恰好用尽时同样是 1, 落到「至少注入一条」的规则上");
    }

    @Test
    @DisplayName("未设总量上限时完全沿用检索侧配置, 不引入任何新的收紧")
    void withoutTotalLimitDelegatesToRag() {
        assertEquals(6000, budget(0, 6000, 6000).ragBudget(100000));
        assertEquals(0, budget(0, 6000, 0).ragBudget(100000),
                "检索侧 0 表示不限制, 应原样透传");
    }

    @Test
    @DisplayName("检索侧不限量(<=0)时, 仍受背景总量约束")
    void ragUnlimitedStillBoundedByTotal() {
        assertEquals(7000, budget(9000, 6000, 0).ragBudget(2000));
    }

    @Test
    @DisplayName("记忆上限原样透传配置值")
    void exposesMemoryBudget() {
        assertEquals(6000, budget(9000, 6000, 6000).memoryMaxChars());
    }
}
