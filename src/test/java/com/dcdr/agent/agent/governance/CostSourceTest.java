package com.dcdr.agent.agent.governance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 费用来源取值域的契约测试。
 *
 * <p>为什么要钉: 这个值落 {@code llm_usage_logs.source}, 而<b>历史数据不可回改</b> ——
 * 一旦取值字符串被"重构"顺手改掉, 报表会从那天起把同一类费用拆成两行, 且没有任何报错。
 * 报表的 CSV 导出与界面都读它, 所以它还兼着展示职责。
 */
class CostSourceTest {

    @Test
    @DisplayName("取值字符串稳定 —— 它同时是落库口径与报表口径")
    void valuesAreStable() {
        assertEquals("chat", CostSource.CHAT.value());
        assertEquals("workflow", CostSource.WORKFLOW.value());
        assertEquals("tool_summary", CostSource.SUMMARY.value());
        assertEquals("memory_extract", CostSource.MEMORY_EXTRACT.value());
    }

    @Test
    @DisplayName("报表展示: 认得出用中文名, 认不出原样带出 —— 不统一吞成'未知'")
    void labelOfFallsBackToRawValue() {
        assertEquals("记忆自动抽取", CostSource.labelOf("memory_extract"));

        // 存量库里存在已弃用的来源: 吞成"未知"会让历史报表解释不通, 原样显示至少还能看出是什么
        assertEquals("routing", CostSource.labelOf("routing"));
        assertEquals("未知", CostSource.labelOf(null));
        assertEquals("未知", CostSource.labelOf("  "));
    }

    @Test
    @DisplayName("解析: 未知返回 null(读取侧宽容), 精确匹配(写入侧严格)")
    void fromIsLenientOnRead() {
        assertEquals(CostSource.CHAT, CostSource.from("CHAT"));
        assertNull(CostSource.from("legacy-source"));
        assertNull(CostSource.from(null));
    }
}
