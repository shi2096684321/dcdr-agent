package com.dcdr.agent.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检索来源取值域的契约测试。
 *
 * <p>这个取值同时落到三处: 调用链步骤(前端面板 + 落库 JSON)、库侧聚合、以及前端那份
 * {@code frontend/src/constants/enums.js} 的镜像。它<b>没有编译期保护</b> ——
 * 后端改了取值, 前端只是静默地不再命中分支。故把字符串本身钉住。
 */
class RagSourceTest {

    @Test
    @DisplayName("取值字符串稳定 —— 它是面板、CSV 与库侧聚合共同的契约")
    void valuesAreStable() {
        assertEquals("forced", RagSource.FORCED.value());
        assertEquals("auto", RagSource.AUTO.value());
        assertEquals("tool", RagSource.TOOL.value());
    }

    @Test
    @DisplayName("每个取值都有中文名 —— 前端不再自己维护映射表")
    void everySourceHasLabel() {
        for (RagSource s : RagSource.values()) {
            assertTrue(!s.label().isBlank(), s + " 缺少中文名");
        }
    }

    @Test
    @DisplayName("解析宽容: 历史数据里的来源认得出, 认不出的不抛异常")
    void fromIsLenient() {
        assertEquals(RagSource.TOOL, RagSource.from("TOOL"), "日志/存量数据里大小写不一定一致");
        assertEquals(RagSource.AUTO, RagSource.from(" auto "));
        assertNull(RagSource.from("unknown-source"), "上游/历史出现新取值时不该让链路记录失败");
        assertNull(RagSource.from(""));
        assertNull(RagSource.from(null));
    }
}
