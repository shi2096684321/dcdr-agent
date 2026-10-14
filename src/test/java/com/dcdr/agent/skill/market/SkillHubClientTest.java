package com.dcdr.agent.skill.market;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SkillHub 客户端参数契约单元测试 (V3.1 技能市场)。
 *
 * <p>这里只钉一类东西: <b>上游的参数契约</b>。它是"外部事实" —— 没文档、只能实测,
 * 而且<b>被改掉不会有任何编译错误</b>。线上表现是某个接口静默 400, 排查成本很高
 * (本次就踩过: {@code page=0} 上游返回 400 且响应体为空, 只有对比 page=1 才能看出基准差异)。
 *
 * <p>不启 Spring, 不碰网络: 被测的是纯函数, 保持秒级。
 */
class SkillHubClientTest {

    @Test
    @DisplayName("页码: 对外 0 起 → 上游 1 起")
    void upstreamPageConvertsZeroBasedToUpstreamOneBased() {
        assertEquals("1", SkillHubClient.upstreamPage(0));
        assertEquals("2", SkillHubClient.upstreamPage(1));
        assertEquals("11", SkillHubClient.upstreamPage(10));
    }

    @Test
    @DisplayName("页码: 非法值夹到合法区间, 不把 400 留给上游")
    void upstreamPageClampsInvalidValues() {
        assertEquals("1", SkillHubClient.upstreamPage(-1));
        // 溢出边界: 若用 int 直接 +1, 这里会变成 2147483647 而不是 1
        assertEquals("1", SkillHubClient.upstreamPage(Integer.MIN_VALUE));
    }
}
