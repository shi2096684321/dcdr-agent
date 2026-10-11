package com.dcdr.agent.agent.memory.longterm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MemoryRelevanceScorer} 单元测试(V3.2)。
 *
 * <p>纯单元测试: 虽是 {@code @Component}, 但<b>无任何注入字段</b>, 可直接 {@code new} 构造,
 * 不启 Spring 上下文、零外部依赖。
 *
 * <p><b>为什么值得测</b>: 它决定长期记忆的"条件触发"召回 —— 命中则把记忆注入上下文,
 * 不命中则完全不注入。两个方向的错误都有代价: 漏召回 = 记忆形同虚设;
 * 误召回 = 把无关记忆塞进 prompt, 污染上下文。而它的核心是<b>字符 2-gram 覆盖率算法</b>
 * (为中文无空格分词而设计), 阈值判定与边界(空/纯标点/精确包含短路)都容易在调整时出偏差。
 */
class MemoryRelevanceScorerTest {

    private final MemoryRelevanceScorer scorer = new MemoryRelevanceScorer();

    // ==================================================================
    //  score: 相关性
    // ==================================================================

    @Nested
    @DisplayName("score 相关性计算")
    class Score {

        @Test
        @DisplayName("query 为 null / 空白 / 纯标点时返回 0(不参与条件触发)")
        void blankQueryScoresZero() {
            assertEquals(0.0, scorer.score(null, "部署环境", null), 1e-9);
            assertEquals(0.0, scorer.score("   ", "部署环境", null), 1e-9);
            // 纯标点归一化后为空串
            assertEquals(0.0, scorer.score("！！！", "部署环境", null), 1e-9);
        }

        @Test
        @DisplayName("提问直接包含触发键时满分(标识符/短语走此短路)")
        void directContainmentScoresOne() {
            assertEquals(1.0, scorer.score("部署环境怎么配", "部署环境", null), 1e-9);
        }

        @Test
        @DisplayName("重要: 中文近义表述靠 2-gram 部分命中(分词无法做到这件事)")
        void chineseParaphrasePartialHit() {
            // "部署到测试环境" vs "部署环境": 不构成直接包含, 但共享 部署/环境 两个 2-gram
            // candidateGrams = {部署, 署环, 环境} → overlap = 2 → 2/3
            double score = scorer.score("部署到测试环境", "部署环境", null);
            assertEquals(2.0 / 3.0, score, 1e-9);
            assertTrue(score >= MemoryRelevanceScorer.HIT_THRESHOLD,
                    "中文改写应能达到命中阈值, 否则条件触发在中文场景基本失效");
        }

        @Test
        @DisplayName("完全不相关返回 0")
        void unrelatedScoresZero() {
            assertEquals(0.0, scorer.score("今天天气不错", "部署环境", null), 1e-9);
        }

        @Test
        @DisplayName("topic 与 tags 取最大值(任一命中即视为相关)")
        void takesMaxAcrossTopicAndTags() {
            // topic 不相关, 第二个 tag 精确命中 → 取最大值 1.0
            double score = scorer.score("部署环境", "天气", List.of("天气", "部署"));
            assertEquals(1.0, score, 1e-9);
        }

        @Test
        @DisplayName("topic 与 tags 全为空时返回 0")
        void emptyCandidatesScoreZero() {
            assertEquals(0.0, scorer.score("部署环境", null, null), 1e-9);
            assertEquals(0.0, scorer.score("部署环境", null, List.of()), 1e-9);
            assertEquals(0.0, scorer.score("部署环境", "  ", List.of("")), 1e-9);
        }

        @Test
        @DisplayName("大小写与标点被归一化(Deploy ENV 命中 deploy)")
        void normalizationIgnoresCaseAndPunctuation() {
            assertEquals(1.0, scorer.score("Deploy ENV", "deploy", null), 1e-9);
            assertEquals(1.0, scorer.score("部署环境！", "部署环境", null), 1e-9);
        }

        @Test
        @DisplayName("提问短于触发键时不会因包含而满分, 按 2-gram 计算")
        void shorterQueryUsesNgrams() {
            // candidateGrams = {部署, 署环} → 只有 部署 命中 → 1/2 = 0.5
            assertEquals(0.5, scorer.score("部署", "部署环", null), 1e-9);
        }
    }

    // ==================================================================
    //  hit: 阈值判定(边界)
    // ==================================================================

    @Nested
    @DisplayName("hit 阈值判定")
    class Hit {

        @Test
        @DisplayName("超过阈值算命中")
        void aboveThresholdHits() {
            assertTrue(scorer.hit("部署到测试环境", "部署环境", null));
        }

        @Test
        @DisplayName("阈值是闭区间 >= : 恰好 0.5 算命中")
        void exactlyThresholdHits() {
            // 1/2 = 0.5, 与 HIT_THRESHOLD 相等 —— 判定用的是 >=, 不是 >
            double score = scorer.score("部署", "部署环", null);
            assertEquals(MemoryRelevanceScorer.HIT_THRESHOLD, score, 1e-9);
            assertTrue(scorer.hit("部署", "部署环", null));
        }

        @Test
        @DisplayName("低于阈值不命中")
        void belowThresholdMisses() {
            assertFalse(scorer.hit("今天天气不错", "部署环境", null));
            assertFalse(scorer.hit("", "部署环境", null));
        }
    }

    // ==================================================================
    //  标签序列化 / 反序列化
    // ==================================================================

    @Nested
    @DisplayName("parseTags / joinTags")
    class Tags {

        @Test
        @DisplayName("parseTags: 逗号切分并去掉空白项")
        void parseTagsTrimsAndDropsBlanks() {
            assertEquals(List.of("a", "b", "c"), MemoryRelevanceScorer.parseTags("a, b , ,c"));
            assertEquals(List.of("部署", "环境"), MemoryRelevanceScorer.parseTags("部署,环境"));
        }

        @Test
        @DisplayName("parseTags: null / 空白 / 全逗号返回空列表")
        void parseTagsHandlesBlank() {
            assertEquals(List.of(), MemoryRelevanceScorer.parseTags(null));
            assertEquals(List.of(), MemoryRelevanceScorer.parseTags("   "));
            assertEquals(List.of(), MemoryRelevanceScorer.parseTags(",,,"));
        }

        @Test
        @DisplayName("joinTags: 去空白、去空项、去重后逗号拼接")
        void joinTagsNormalizes() {
            assertEquals("a,b", MemoryRelevanceScorer.joinTags(List.of(" a ", "b", "a", "")));
            assertEquals("部署,环境", MemoryRelevanceScorer.joinTags(List.of("部署", "环境", "部署")));
        }

        @Test
        @DisplayName("joinTags: 空输入或全空项返回 null(与 DB 可空字段对应)")
        void joinTagsReturnsNullWhenEmpty() {
            assertNull(MemoryRelevanceScorer.joinTags(null));
            assertNull(MemoryRelevanceScorer.joinTags(List.of()));
            assertNull(MemoryRelevanceScorer.joinTags(List.of("", "   ")));
        }

        @Test
        @DisplayName("序列化 → 反序列化往返一致")
        void tagsRoundTrip() {
            List<String> tags = List.of("部署", "环境", "回滚");
            assertEquals(tags, MemoryRelevanceScorer.parseTags(MemoryRelevanceScorer.joinTags(tags)));
        }
    }
}
