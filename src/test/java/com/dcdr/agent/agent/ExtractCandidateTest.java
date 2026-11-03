package com.dcdr.agent.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentOrchestrator#pickExtractCandidates(List, int)} 的口径测试。
 *
 * <p><b>锁的是一个真实偏差</b>: 候选此前按 {@code articleScore}(URL 特征)降序排,
 * 于是官方 rerank 第 1 的详情页(路径不含 {@code /job/}、无数字 id, 得 0 分)
 * 会被排到第 8 位的列表页({@code /zhaogong/341962660.html}, 得 40 分)之后 ——
 * "抓最相关的那篇"变成了"抓最像文章的那篇"。{@code EXTRACT_TOP_N}=1 之后,
 * 选错一篇就等于整次搜索没有正文兜底。
 *
 * <p>正确口径: 顺序由搜索源给(DeepSeek 按 rerank_score 降序, SearXNG 按 score 降序),
 * 这里只按传入顺序取, {@code articleScore} 只负责剔除"抓了也没正文"的链接。
 */
class ExtractCandidateTest {

    /** 官方排第 1, 但 URL 特征很弱(无 /job/、无数字 id、非 .html) */
    private static final String WEAK_BUT_RELEVANT = "https://acme.com/careers/backend-engineer";
    /** 官方排第 8, URL 特征很强(.html + 数字 id + 文章路径关键词) */
    private static final String STRONG_BUT_LOW_RANK = "https://job.example.com/zhaogong/341962660.html";

    @Test
    @DisplayName("按搜索源排名取候选: 官方第 1 就该抓它, 哪怕 URL 不像文章")
    void keepsProviderOrder() {
        List<Map<String, Object>> srcs = List.of(
                src(WEAK_BUT_RELEVANT, "后端工程师"),
                src(STRONG_BUT_LOW_RANK, "某岗位列表"));

        List<Map<String, Object>> picked = AgentOrchestrator.pickExtractCandidates(srcs, 1);

        assertEquals(1, picked.size());
        assertEquals(WEAK_BUT_RELEVANT, picked.get(0).get("url"),
                "排序信号由上游给, 不能用 URL 特征覆盖 —— 否则抓的是第 8 条而不是最相关的第 1 条");
    }

    @Test
    @DisplayName("取前 N 篇时保持上游顺序, 不重排")
    void preservesOrderWhenTakingSeveral() {
        List<Map<String, Object>> srcs = List.of(
                src(WEAK_BUT_RELEVANT, "后端工程师"),
                src(STRONG_BUT_LOW_RANK, "某岗位列表"));

        List<Map<String, Object>> picked = AgentOrchestrator.pickExtractCandidates(srcs, 2);

        assertEquals(2, picked.size());
        assertEquals(WEAK_BUT_RELEVANT, picked.get(0).get("url"), "第 1 个位置必须还是官方第 1");
        assertEquals(STRONG_BUT_LOW_RANK, picked.get(1).get("url"));
    }

    @Test
    @DisplayName("聚合首页/栏目页跳过, 顺延到下一篇能抓的")
    void skipsAggregatePages() {
        List<Map<String, Object>> srcs = List.of(
                src("https://news.example.com/news", "新闻首页"),
                src(WEAK_BUT_RELEVANT, "后端工程师"));

        List<Map<String, Object>> picked = AgentOrchestrator.pickExtractCandidates(srcs, 1);

        assertEquals(1, picked.size());
        assertEquals(WEAK_BUT_RELEVANT, picked.get(0).get("url"),
                "聚合页抓不到正文, 但也不能因此把这次抓取浪费掉, 应顺延给下一篇");
    }

    @Test
    @DisplayName("已有正文的来源跳过: 部分搜索源(need_content)自己就带回了正文")
    void skipsSourcesWithContent() {
        Map<String, Object> withContent = src(WEAK_BUT_RELEVANT, "后端工程师");
        withContent.put("content", "已经有的正文");

        List<Map<String, Object>> picked = AgentOrchestrator.pickExtractCandidates(
                List.of(withContent, src(STRONG_BUT_LOW_RANK, "某岗位列表")), 1);

        assertEquals(1, picked.size());
        assertEquals(STRONG_BUT_LOW_RANK, picked.get(0).get("url"));
    }

    @Test
    @DisplayName("重复 URL 只抓一次")
    void dedupesByUrl() {
        List<Map<String, Object>> srcs = List.of(
                src(WEAK_BUT_RELEVANT, "标题甲"),
                src(WEAK_BUT_RELEVANT, "标题乙"),
                src(STRONG_BUT_LOW_RANK, "标题丙"));

        List<Map<String, Object>> picked = AgentOrchestrator.pickExtractCandidates(srcs, 2);

        assertEquals(2, picked.size(), "同一 URL 出现两次只该算一篇");
        assertEquals(STRONG_BUT_LOW_RANK, picked.get(1).get("url"));
    }

    @Test
    @DisplayName("非法入参不炸")
    void handlesEmptyInput() {
        assertTrue(AgentOrchestrator.pickExtractCandidates(null, 3).isEmpty());
        assertTrue(AgentOrchestrator.pickExtractCandidates(List.of(), 3).isEmpty());
        assertTrue(AgentOrchestrator.pickExtractCandidates(List.of(src(WEAK_BUT_RELEVANT, "t")), 0).isEmpty());
    }

    // ===== 辅助 =====

    private static Map<String, Object> src(String url, String title) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("url", url);
        m.put("title", title);
        return m;
    }
}
