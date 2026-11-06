package com.dcdr.agent.agent.prompt;

import com.dcdr.agent.config.AgentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「上下文长度」档位的规则测试(V3.4)。
 *
 * <p>锁的是两件事: <b>部署默认值必须出现在档位里</b>(否则用户点了「跟随默认」之后,
 * 下拉框找不到与生效值对应的项, 会显示成空白), 以及<b>档位升序去重</b>
 * (顺序错乱的下拉框会让人以为选错了档)。
 *
 * <p>{@code UserRepository} 传 null: 本测试只碰部署默认值与档位拼装, 不读写用户偏好。
 */
class ContextLimitPreferenceTest {

    private static ContextLimitPreference preference(int deploymentDefault) {
        AgentProperties props = new AgentProperties();
        props.getMemory().setContextTokenLimit(deploymentDefault);
        return new ContextLimitPreference(null, props);
    }

    @Test
    @DisplayName("首项是「跟随默认」且 value=null —— 界面靠它清除个人设置")
    void firstOptionIsFollowDefault() {
        List<ContextLimitPreference.Option> options = preference(300_000).options();

        assertNull(options.get(0).value());
        assertTrue(options.get(0).label().contains("跟随默认"));
        assertTrue(options.size() > 1, "除默认项外还要有可选档位");
    }

    @Test
    @DisplayName("部署默认值不在预设阶梯里时也要并入, 并保持升序")
    void includesDeploymentDefaultInOrder() {
        // 120000 不在 {32K,128K,200K,300K,1M} 里
        List<ContextLimitPreference.Option> options = preference(120_000).options();

        List<Integer> values = options.stream()
                .map(ContextLimitPreference.Option::value)
                .filter(v -> v != null)
                .toList();
        assertTrue(values.contains(120_000), "默认值必须可选, 否则「恢复默认」后下拉框无从对应");
        for (int i = 1; i < values.size(); i++) {
            assertTrue(values.get(i) > values.get(i - 1), "档位必须严格升序: " + values);
        }
    }

    @Test
    @DisplayName("默认档只出现一次, 且标签标出「当前默认」")
    void marksDefaultTierOnce() {
        List<ContextLimitPreference.Option> options = preference(300_000).options();

        long hits = options.stream().filter(o -> o.label().contains("当前默认")).count();
        assertEquals(1, hits, "「当前默认」标记应恰好出现一次");
    }

    @Test
    @DisplayName("token 缩写口径与前端图例一致: 1.0M / 300.0K / 800")
    void formatsTokensLikeTheLegend() {
        assertEquals("1.0M", ContextLimitPreference.formatTokens(1_000_000));
        assertEquals("300.0K", ContextLimitPreference.formatTokens(300_000));
        assertEquals("32.0K", ContextLimitPreference.formatTokens(32_000));
        assertEquals("800", ContextLimitPreference.formatTokens(800));
    }

    @Test
    @DisplayName("配置被写坏(0/负数)时兜到下限, 不让分母变成 0")
    void guardsBrokenDeploymentValue() {
        assertEquals(ContextLimitPreference.MIN_LIMIT, preference(0).deploymentDefault());
        assertEquals(ContextLimitPreference.MIN_LIMIT, preference(-1).deploymentDefault());
    }
}
