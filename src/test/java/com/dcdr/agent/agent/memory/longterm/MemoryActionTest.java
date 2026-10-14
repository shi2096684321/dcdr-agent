package com.dcdr.agent.agent.memory.longterm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 记忆动作词表的契约测试。
 *
 * <p>为什么值得单独测: 这个枚举的输入<b>全部来自模型</b>(工具参数与自动抽取的 JSON 输出),
 * 是不可信数据。解析策略一旦变成"抛异常", 后果不是报错而是<b>整条链路静默失效</b> ——
 * 模型多说一个字段, 这次抽取就整批作废; 反过来, 若解析过宽(把 NOOP 也当成某个动作),
 * 就会凭空写入或归档记忆。两头都不能靠"读代码时小心"来保证。
 */
class MemoryActionTest {

    @Test
    @DisplayName("解析对大小写不敏感 —— 工具与自动抽取两条链路历史上用了不同大小写")
    void fromIsCaseInsensitive() {
        assertEquals(MemoryAction.ADD, MemoryAction.from("add"));
        assertEquals(MemoryAction.ADD, MemoryAction.from("ADD"));
        assertEquals(MemoryAction.UPDATE, MemoryAction.from(" Update "));
        assertEquals(MemoryAction.DELETE, MemoryAction.from("delete"));
    }

    @Test
    @DisplayName("认不出的动作返回 null 而不抛异常 —— 输入是模型输出, 认不出属正常情况")
    void fromReturnsNullForUnknown() {
        // NOOP 是提示词约定的"无需动作", 确实没有对应动作
        assertNull(MemoryAction.from("NOOP"));
        // 模型编出来的动作
        assertNull(MemoryAction.from("archived"));
        assertNull(MemoryAction.from(""));
        assertNull(MemoryAction.from("   "));
        assertNull(MemoryAction.from(null));
    }

    @Test
    @DisplayName("下发给模型的取值与中文名出自同一处: describeAll 不漏任何动作")
    void describeAllCoversEveryAction() {
        String desc = MemoryAction.describeAll();
        for (MemoryAction a : MemoryAction.values()) {
            assertTrue(desc.contains(a.value() + "(" + a.label() + ")"),
                    "动作 " + a + " 必须出现在给模型的说明里, 否则模型看不见这个动作: " + desc);
        }
        assertTrue(desc.contains("归档"),
                "DELETE 两条链路都实现为归档(可恢复), 说明里写「删除」会让模型与用户都以为数据没了");
    }

    @Test
    @DisplayName("取值与 enum 名解耦 —— 改名不该悄悄改掉前后端契约")
    void valueIsStable() {
        // 这三个字符串是 tool schema 与抽取提示词的约定, 也是日志里 grep 的对象
        assertEquals("add", MemoryAction.ADD.value());
        assertEquals("update", MemoryAction.UPDATE.value());
        assertEquals("delete", MemoryAction.DELETE.value());
        assertNotNull(MemoryAction.ADD.label());
    }
}
