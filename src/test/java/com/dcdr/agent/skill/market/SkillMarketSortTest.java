package com.dcdr.agent.skill.market;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 技能市场排序枚举单元测试 (V3.1)。
 *
 * <p>为什么这个枚举值得单独测: 它唯一的职责是把<b>用户输入</b>关进一个小集合里。
 * 上游的 {@code sortBy} 是严格白名单 —— 未知值直接 400(实测 {@code created_at} /
 * {@code name} / {@code trending} 都是 400)。所以"漏了一个枚举值"或"顺手改成透传"
 * 的后果不是排序失效, 而是<b>整页打不开</b>(502)。这条约束没有任何编译期保证, 只能靠测试。
 */
class SkillMarketSortTest {

    /**
     * 上游实测可接受的 sortBy 字段。
     * 枚举里出现的上游字段必须落在这一集合内 —— 多一个都会被上游 400。
     */
    private static final Set<String> UPSTREAM_ACCEPTED = Set.of(
            "downloads", "installs", "stars", "score", "updated_at", "updatedAt", "updated");

    @Test
    @DisplayName("白名单: 所有项的上游字段都在上游可接受集合内")
    void upstreamFieldsAreWithinUpstreamAcceptedSet() {
        for (SkillMarketSort s : SkillMarketSort.values()) {
            assertTrue(UPSTREAM_ACCEPTED.contains(s.getUpstreamField()),
                    s.name() + " 使用了上游不接受的 sortBy 值: " + s.getUpstreamField() + "(上游会返回 400)");
        }
    }

    @Test
    @DisplayName("每一项都带完整上游参数(已无'不传排序'的选项)")
    void everyOptionSendsUpstreamParams() {
        for (SkillMarketSort s : SkillMarketSort.values()) {
            assertNotNull(s.getUpstreamField(), s.name() + " 缺少上游 sortBy");
            assertNotNull(s.getOrder(), s.name() + " 缺少上游 order");
            assertTrue("asc".equals(s.getOrder()) || "desc".equals(s.getOrder()),
                    s.name() + " 的 order 非法: " + s.getOrder());
        }
    }

    @Test
    @DisplayName("推荐排首位(下拉顺序来自枚举声明顺序), 且是唯一本地排序项")
    void recommendedIsFirstAndOnlyLocallyRanked() {
        assertEquals(SkillMarketSort.RECOMMENDED, SkillMarketSort.values()[0]);
        assertTrue(SkillMarketSort.RECOMMENDED.isLocallyRanked());
        for (SkillMarketSort s : SkillMarketSort.values()) {
            if (s != SkillMarketSort.RECOMMENDED) {
                assertFalse(s.isLocallyRanked(), s.name() + " 不该是本地排序项");
            }
        }
    }

    @Test
    @DisplayName("解析: 大小写不敏感, 且 trim 掉空白")
    void fromKeyIsCaseInsensitiveAndTrimmed() {
        assertEquals(SkillMarketSort.DOWNLOADS, SkillMarketSort.fromKey("downloads"));
        assertEquals(SkillMarketSort.DOWNLOADS, SkillMarketSort.fromKey("DOWNLOADS"));
        assertEquals(SkillMarketSort.DOWNLOADS, SkillMarketSort.fromKey("  downloads  "));
        assertEquals(SkillMarketSort.RECENT, SkillMarketSort.fromKey("recent"));
        assertEquals(SkillMarketSort.RECOMMENDED, SkillMarketSort.fromKey("recommended"));
    }

    @Test
    @DisplayName("解析: 未知/空值一律回落推荐(丢一个排序好过整页报错)")
    void unknownKeyFallsBackToRecommended() {
        assertEquals(SkillMarketSort.RECOMMENDED, SkillMarketSort.fromKey(null));
        assertEquals(SkillMarketSort.RECOMMENDED, SkillMarketSort.fromKey(""));
        assertEquals(SkillMarketSort.RECOMMENDED, SkillMarketSort.fromKey("   "));
        assertEquals(SkillMarketSort.RECOMMENDED, SkillMarketSort.fromKey("bogus"));
        // 上游会 400 的字段名: 被透传就会把整页打成 502, 必须在此被拦下
        assertEquals(SkillMarketSort.RECOMMENDED, SkillMarketSort.fromKey("created_at"));
        assertEquals(SkillMarketSort.RECOMMENDED, SkillMarketSort.fromKey("name"));
        // 上游字段名(updated_at)不是对外 key, 不应被当成合法入参
        assertEquals(SkillMarketSort.RECOMMENDED, SkillMarketSort.fromKey("updated_at"));
    }

    @Test
    @DisplayName("对外 key 与标签齐备(经 /sorts 下发给前端, 不能有空项)")
    void keysAndLabelsArePresent() {
        for (SkillMarketSort s : SkillMarketSort.values()) {
            assertTrue(s.getKey() != null && !s.getKey().isBlank(), s.name() + " 缺少 key");
            assertTrue(s.getLabel() != null && !s.getLabel().isBlank(), s.name() + " 缺少 label");
        }
    }
}
