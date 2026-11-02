package com.dcdr.agent.agent.schedule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 错过触发补跑的两条判定 (V3.3)。
 *
 * <p>这组断言守的是"什么时候该补跑", 因为两种写反的后果都很贵:
 * 判宽了 → 隔夜启动把一整天错过的任务全跑一遍(每个都烧模型费用);
 * 判严了 → 重启窗口内的任务永远不补, 表现与"推送坏了"完全一样(用户根本看不出区别)。
 */
class ScheduledTaskMisfireTest {

    @Test
    @DisplayName("过期时长在窗口内才补跑")
    void withinWindow() {
        assertTrue(ScheduledTaskService.withinCatchUpWindow(0, 3600), "刚到点应补");
        assertTrue(ScheduledTaskService.withinCatchUpWindow(600, 3600), "晚 10 分钟应补");
        assertTrue(ScheduledTaskService.withinCatchUpWindow(3600, 3600), "边界值应补");
    }

    @Test
    @DisplayName("超出窗口不补: 隔夜补出早上的日报毫无意义")
    void beyondWindow() {
        assertFalse(ScheduledTaskService.withinCatchUpWindow(3601, 3600));
        assertFalse(ScheduledTaskService.withinCatchUpWindow(24 * 3600, 3600), "隔天不补");
    }

    @Test
    @DisplayName("负数(还没到点)不算补跑")
    void futureIsNotCatchUp() {
        assertFalse(ScheduledTaskService.withinCatchUpWindow(-1, 3600));
    }

    @Test
    @DisplayName("该时间点之后已执行过就不补, 避免重启后重复跑同一时刻")
    void alreadyRanAfterDue() {
        LocalDateTime due = LocalDateTime.of(2026, 11, 2, 9, 0);
        assertTrue(ScheduledTaskService.ranAfter(due, due.plusSeconds(30)), "跑完但没回写 nextRun 就崩了");
        assertTrue(ScheduledTaskService.ranAfter(due, due.plusMinutes(5)));
    }

    @Test
    @DisplayName("上次运行早于该时间点 / 从没跑过 → 需要补")
    void notRanSinceDue() {
        LocalDateTime due = LocalDateTime.of(2026, 11, 2, 9, 0);
        assertFalse(ScheduledTaskService.ranAfter(due, null), "从未执行过, 应补");
        assertFalse(ScheduledTaskService.ranAfter(due, due.minusDays(1)), "昨天跑的, 今天这次要补");
    }
}
