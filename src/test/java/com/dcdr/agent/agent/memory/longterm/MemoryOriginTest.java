package com.dcdr.agent.agent.memory.longterm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 记忆写入来源的归因测试 (V3.1)。
 *
 * <p>这里钉的是<b>存量数据</b>那条路: {@code source_kind} 是后加的列, 老行没有值。
 * 兜底规则一旦搞反, 界面上就会把"AI 自动提炼的"显示成"你要求记的"(或反过来) ——
 * 而用户看这个标识恰恰是为了回答"我让 AI 记住的那条到底记下来没有", 显示反了比不显示更糟。
 */
class MemoryOriginTest {

    @Test
    @DisplayName("解析: 取值容错大小写与空白, 未知/空一律返回 null 而不抛异常")
    void fromIsLenient() {
        assertEquals(MemoryOrigin.AUTO, MemoryOrigin.from("AUTO"));
        assertEquals(MemoryOrigin.REQUESTED, MemoryOrigin.from(" requested "));
        assertEquals(MemoryOrigin.MANUAL, MemoryOrigin.from("manual"));
        // 该列可空且历史脏值不该炸列表
        assertNull(MemoryOrigin.from(null));
        assertNull(MemoryOrigin.from(""));
        assertNull(MemoryOrigin.from("LEGACY_UNKNOWN"));
    }

    @Test
    @DisplayName("归因: 有值就用值")
    void resolvePrefersColumn() {
        assertEquals(MemoryOrigin.REQUESTED, MemoryOrigin.resolve("REQUESTED", "s1"));
        // 有来源会话但列里明确写着 MANUAL(理论上不会同时出现): 以列里的明确值为准
        assertEquals(MemoryOrigin.MANUAL, MemoryOrigin.resolve("MANUAL", "s1"));
    }

    @Test
    @DisplayName("存量数据兜底: 有来源会话 = AI 在对话中写入, 没有 = 记忆页手动新增")
    void resolveFallsBackForLegacyRows() {
        assertEquals(MemoryOrigin.AUTO, MemoryOrigin.resolve(null, "s1"),
                "该列上线前只有自动抽取会在对话里写记忆, 所以有来源会话就是 AI 记的");
        assertEquals(MemoryOrigin.MANUAL, MemoryOrigin.resolve(null, null),
                "没有来源会话 = 用户在记忆页手动新增");
        assertEquals(MemoryOrigin.MANUAL, MemoryOrigin.resolve(null, "   "),
                "空白来源会话按没有处理");
        // 脏值同样走兜底, 不能因为读不出来就当成"手动"
        assertEquals(MemoryOrigin.AUTO, MemoryOrigin.resolve("LEGACY_UNKNOWN", "s1"));
    }

    @Test
    @DisplayName("每个取值都有中文展示名 —— 界面上漏一个就会出现空白标签")
    void everyOriginHasLabel() {
        for (MemoryOrigin o : MemoryOrigin.values()) {
            assertNotNull(o.label(), "缺少展示名: " + o);
            assertFalse(o.label().isBlank(), "展示名不能是空白: " + o);
        }
    }
}
