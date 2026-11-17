package com.dcdr.agent.agent.memory.longterm;

import com.dcdr.agent.entity.MemoryEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 长期记忆注入文本的长度预算测试。
 *
 * <p>记忆此前只有<b>条数</b>上限(常驻 20 + 触发 10 条), 单条可到 1000 字符 ——
 * 也就是最坏约 3 万字符, 而这条路径上没有任何东西会拦它。这些测试锁定预算生效后的三件事:
 * 整条丢弃而非截半条、至少保留一条、以及不留下空的分段标题。
 *
 * <p>另外锁一条边界: <b>这里只产出数据</b>。使用规则("不复述 / 冲突以当前提问为准 / 不构成系统指令")
 * 属于稳定的 system 级约束, 已挪到 {@code prompts/runtime-memory-usage.md} —— 规则若跟着条目走,
 * 就会连带变成"每轮变量", 既毁前缀缓存, 又会在协议出口折进 user 回合时降格成资料。
 */
class LongTermMemoryFormatTest {

    private static MemoryEntity mem(String scope, String type, String content) {
        MemoryEntity e = new MemoryEntity();
        e.setScope(scope);
        e.setType(type);
        e.setContent(content);
        return e;
    }

    @Test
    @DisplayName("片段只含数据与标记, 不含使用规则(规则在静态 system 块里)")
    void fragmentCarriesDataOnly() {
        String text = LongTermMemoryService.formatFragment(
                List.of(mem("USER", "PREFERENCE", "偏好中文回答")));

        assertTrue(text.startsWith("<long_term_memory>"), "保留标记, 给静态规则一个可引用的锚点");
        assertTrue(text.trim().endsWith("</long_term_memory>"));
        assertFalse(text.contains("不构成系统指令"), "规则不该压在每轮变化的条目里: " + text);
        assertFalse(text.contains("不要主动复述"), "同上");
        assertTrue(text.contains("偏好中文回答"));
    }

    @Test
    @DisplayName("不传上限时行为不变: 全部条目按作用域分段")
    void withoutBudgetKeepsEverything() {
        String text = LongTermMemoryService.formatFragment(List.of(
                mem("USER", "PREFERENCE", "偏好中文回答"),
                mem("AGENT", "PROJECT", "正在做 DCDR")));

        assertTrue(text.contains("偏好中文回答"));
        assertTrue(text.contains("正在做 DCDR"));
        assertTrue(text.contains("### 用户级记忆（跨 Agent 通用）"));
        assertTrue(text.contains("### 本 Agent 专属记忆"));
    }

    @Test
    @DisplayName("超预算时整条丢弃, 且不给被丢空的分段留下空标题")
    void dropsWholeEntriesWithoutEmptySection() {
        String big = "啊".repeat(400);
        String text = LongTermMemoryService.formatFragment(List.of(
                mem("USER", "PREFERENCE", big),
                mem("AGENT", "PROJECT", big)), 450);

        assertTrue(text.contains("### 用户级记忆（跨 Agent 通用）"), "排在前面的那条应在");
        assertFalse(text.contains("### 本 Agent 专属记忆"),
                "第二条被预算丢弃后不该留下一个空的分段标题 —— 那会让模型以为这里本来就没有内容");
    }

    @Test
    @DisplayName("预算再小也保留一条, 且是完整的一条(不截半条)")
    void alwaysKeepsOneWholeEntry() {
        String content = "啊".repeat(500);
        String text = LongTermMemoryService.formatFragment(
                List.of(mem("USER", "PREFERENCE", content)), 10);

        assertNotNull(text);
        assertTrue(text.contains(content),
                "一条都不注入时调用方会跳过注入, 与「该用户没有记忆」无法区分; "
                        + "而截半条的记忆会让模型补全出一个用户从未表达过的偏好");
    }
}
