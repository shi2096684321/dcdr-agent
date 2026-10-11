package com.dcdr.agent.tool.websearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SearchResultCleaner} 单元测试(V3.2)。
 *
 * <p>纯单元测试: 无状态、零外部依赖, 不启 Spring 上下文, 毫秒级完成。
 *
 * <p><b>为什么这个类值得优先测</b>: SearXNG 返回的是搜索引擎原始结果(不像 Tavily 已为 LLM 清洗过),
 * 清洗管线的每一条规则(过滤聚合页 / 剥离追踪参数 / 去重)都直接影响<b>喂给模型的上下文质量</b>;
 * 而这些规则全部是正则与字符串处理, 最容易在"调整一条正则"时悄悄破坏另一条 ——
 * 典型的"改 A 坏 B"场景, 必须由测试兜住。
 *
 * <p>测试按<b>行为契约</b>组织(而非复述实现): 每条断言都在声明"输入什么样、应该得到什么样"。
 */
class SearchResultCleanerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 把 JSON 数组字面量解析为 SearXNG results 数组的元素列表 */
    private static List<JsonNode> results(String json) {
        try {
            JsonNode array = MAPPER.readTree(json);
            List<JsonNode> list = new ArrayList<>();
            array.forEach(list::add);
            return list;
        } catch (Exception e) {
            throw new IllegalStateException("测试 JSON 构造失败", e);
        }
    }

    // ==================================================================
    //  cleanUrl: 去 hash / 剥追踪参数 / 去尾斜杠
    // ==================================================================

    @Nested
    @DisplayName("cleanUrl")
    class CleanUrl {

        @Test
        @DisplayName("去掉 hash 片段")
        void removesHash() {
            assertEquals("https://a.com/x", SearchResultCleaner.cleanUrl("https://a.com/x#frag"));
        }

        @Test
        @DisplayName("剥离追踪参数, 保留业务参数")
        void stripsTrackingParams() {
            assertEquals("https://a.com/x?id=1",
                    SearchResultCleaner.cleanUrl("https://a.com/x?uc_param_str=abc&id=1&spm=2"));
        }

        @Test
        @DisplayName("参数全是追踪参数时只留 base(不保留问号)")
        void keepsBaseWhenAllParamsTracked() {
            assertEquals("https://a.com/x",
                    SearchResultCleaner.cleanUrl("https://a.com/x?from=wx&saref=1"));
        }

        @Test
        @DisplayName("丢弃无键值对的噪声参数(如 ?COM)")
        void dropsKeylessParam() {
            assertEquals("https://a.com/x", SearchResultCleaner.cleanUrl("https://a.com/x?COM"));
        }

        @Test
        @DisplayName("去掉尾部斜杠")
        void trimsTrailingSlash() {
            assertEquals("https://a.com/news/123",
                    SearchResultCleaner.cleanUrl("https://a.com/news/123/"));
        }

        @Test
        @DisplayName("null / 空白返回空串")
        void handlesBlank() {
            assertEquals("", SearchResultCleaner.cleanUrl(null));
            assertEquals("", SearchResultCleaner.cleanUrl("   "));
        }
    }

    // ==================================================================
    //  cleanTitle: 去话题标签 / @账号 / 站点后缀 / 尾部省略号
    // ==================================================================

    @Nested
    @DisplayName("cleanTitle")
    class CleanTitle {

        @Test
        @DisplayName("去掉话题标签与 @账号")
        void removesTagsAndMentions() {
            assertEquals("某地暴雨", SearchResultCleaner.cleanTitle("#热门 某地暴雨 @央视新闻"));
        }

        @Test
        @DisplayName("去掉站点后缀(竖线 / 下划线 / 连字符 三种形式)")
        void removesSiteSuffix() {
            assertEquals("量子计算突破", SearchResultCleaner.cleanTitle("量子计算突破 | 雷锋网"));
            assertEquals("量子计算突破", SearchResultCleaner.cleanTitle("量子计算突破_光明网"));
            assertEquals("量子计算突破", SearchResultCleaner.cleanTitle("量子计算突破 - 百家号"));
        }

        @Test
        @DisplayName("去掉尾部省略号并压缩空白")
        void removesTrailingEllipsis() {
            assertEquals("某地暴雨", SearchResultCleaner.cleanTitle("某地暴雨……"));
            assertEquals("某地 暴雨", SearchResultCleaner.cleanTitle("某地   暴雨"));
        }

        @Test
        @DisplayName("null / 空白返回空串")
        void handlesBlank() {
            assertEquals("", SearchResultCleaner.cleanTitle(null));
            assertEquals("", SearchResultCleaner.cleanTitle("  "));
        }
    }

    // ==================================================================
    //  cleanSnippet: 去时间前缀 / 列表页堆砌整体丢弃
    // ==================================================================

    @Nested
    @DisplayName("cleanSnippet")
    class CleanSnippet {

        @Test
        @DisplayName("去掉「N 小时之前 · 」这类时间前缀")
        void removesRelativeTimePrefix() {
            assertEquals("台风即将登陆", SearchResultCleaner.cleanSnippet("10 小时之前 · 台风即将登陆"));
        }

        @Test
        @DisplayName("去掉绝对时间前缀(中文格式与 ISO 格式)")
        void removesAbsoluteTimePrefix() {
            assertEquals("台风即将登陆", SearchResultCleaner.cleanSnippet("2026年9月15日 · 台风即将登陆"));
            assertEquals("台风即将登陆", SearchResultCleaner.cleanSnippet("2026-09-15 · 台风即将登陆"));
        }

        @Test
        @DisplayName("列表页堆砌(出现 2 个以上完整时间戳)整体丢弃")
        void dropsListPageDump() {
            String listPage = "2026-09-15 18:35:02 某新闻标题 2026-09-15 18:30:01 另一条标题";
            assertEquals("", SearchResultCleaner.cleanSnippet(listPage));
        }

        @Test
        @DisplayName("只有一个时间戳时仍保留正文(不误杀)")
        void keepsContentWithSingleTimestamp() {
            String content = "发布于 2026-09-15 18:35:02 的公告内容";
            assertTrue(SearchResultCleaner.cleanSnippet(content).contains("公告内容"));
        }

        @Test
        @DisplayName("null / 空白返回空串")
        void handlesBlank() {
            assertEquals("", SearchResultCleaner.cleanSnippet(null));
            assertEquals("", SearchResultCleaner.cleanSnippet("   "));
        }
    }

    // ==================================================================
    //  clean: 整条管线(过滤 → 清洗 → 去重 → 重新编号)
    // ==================================================================

    @Nested
    @DisplayName("clean 主流程")
    class CleanPipeline {

        @Test
        @DisplayName("空输入 / limit<=0 返回空列表")
        void returnsEmptyOnBlankInput() {
            assertTrue(SearchResultCleaner.clean(null, 5).isEmpty());
            assertTrue(SearchResultCleaner.clean(List.of(), 5).isEmpty());
            assertTrue(SearchResultCleaner.clean(
                    results("[{\"url\":\"https://a.com/1\",\"title\":\"标题\"}]"), 0).isEmpty());
        }

        @Test
        @DisplayName("过滤聚合页: 路径特征 / 栏目短路径 / 标题特征 / 屏蔽域名")
        void filtersNonArticlePages() {
            String json = """
                    [
                      {"url":"https://a.com/tag/ai","title":"AI 标签页"},
                      {"url":"https://a.com/ai","title":"AI 频道"},
                      {"url":"https://a.com/x1","title":"最新资讯"},
                      {"url":"https://page.sm.cn/x","title":"某视频"},
                      {"url":"https://a.com/newsDetail_forward_34218660","title":"真实文章标题"}
                    ]
                    """;
            List<Map<String, Object>> out = SearchResultCleaner.clean(results(json), 10);

            // 前 4 条分别命中四条过滤规则; 第 5 条是真实文章路径(含数字 ID), 必须保留
            assertEquals(1, out.size(), "应只保留真实文章, 实际: " + out);
            assertEquals("真实文章标题", out.get(0).get("title"));
        }

        @Test
        @DisplayName("过滤缺 url 或 title 的残缺条目")
        void filtersIncompleteEntries() {
            String json = """
                    [
                      {"url":"","title":"只有标题"},
                      {"url":"https://a.com/1","title":""},
                      {"url":"https://a.com/2","title":"完整条目"}
                    ]
                    """;
            List<Map<String, Object>> out = SearchResultCleaner.clean(results(json), 10);
            assertEquals(1, out.size());
            assertEquals("完整条目", out.get(0).get("title"));
        }

        @Test
        @DisplayName("去重: http/https、www 变体视为同一条")
        void deduplicatesUrlVariants() {
            // 注: URL 用两层以上的真实文章路径 —— 单层短路径(如 /x)会被 SHALLOW_SECTION_PATH
            // 判为栏目页而过滤, 那是「过滤」规则的覆盖范围, 会干扰本条「去重」断言的意图。
            // 两条标题不同, 以确保只有"URL 规范化后相同"这一条能拦住第二条(否则会与标题去重混淆)。
            String json = """
                    [
                      {"url":"https://www.a.com/article/123","title":"标题一"},
                      {"url":"http://a.com/article/123","title":"标题二"}
                    ]
                    """;
            List<Map<String, Object>> out = SearchResultCleaner.clean(results(json), 10);
            assertEquals(1, out.size(), "同一 URL 的协议/www 变体应视为重复");
        }

        @Test
        @DisplayName("去重: 不同来源的同一新闻(标题相同、仅标点差异)")
        void deduplicatesSameTitleFromDifferentSources() {
            String json = """
                    [
                      {"url":"https://a.com/1","title":"某地暴雨蓝色预警"},
                      {"url":"https://b.com/2","title":"某地暴雨蓝色预警！"}
                    ]
                    """;
            List<Map<String, Object>> out = SearchResultCleaner.clean(results(json), 10);
            assertEquals(1, out.size(), "标题规范化后相同应视为重复");
        }

        @Test
        @DisplayName("过滤后继续向后扫描, 补齐到 limit")
        void keepsScanningUntilLimit() {
            String json = """
                    [
                      {"url":"https://a.com/tag/1","title":"聚合一"},
                      {"url":"https://a.com/tag/2","title":"聚合二"},
                      {"url":"https://a.com/1","title":"标题一"},
                      {"url":"https://a.com/2","title":"标题二"}
                    ]
                    """;
            List<Map<String, Object>> out = SearchResultCleaner.clean(results(json), 2);

            // 若实现是"取前 limit 条再过滤", 这里会只剩 0 条 —— 该断言正是防止这种退化
            assertEquals(2, out.size());
            assertEquals("标题一", out.get(0).get("title"));
            assertEquals("标题二", out.get(1).get("title"));
        }

        @Test
        @DisplayName("index 从 1 重新连续编号(不受被过滤条目影响)")
        void reindexesFromOne() {
            String json = """
                    [
                      {"url":"https://a.com/tag/x","title":"聚合页"},
                      {"url":"https://a.com/1","title":"标题一"},
                      {"url":"https://a.com/2","title":"标题二"}
                    ]
                    """;
            List<Map<String, Object>> out = SearchResultCleaner.clean(results(json), 10);
            assertEquals(2, out.size());
            assertEquals(1, out.get(0).get("index"));
            assertEquals(2, out.get(1).get("index"));
        }

        @Test
        @DisplayName("有发布时间时附在摘要尾部(供模型判断时效)")
        void appendsPublishedDate() {
            String json = """
                    [{"url":"https://a.com/1","title":"标题","content":"正文摘要","publishedDate":"2026-10-11"}]
                    """;
            List<Map<String, Object>> out = SearchResultCleaner.clean(results(json), 10);
            assertEquals("正文摘要 (发布于 2026-10-11)", out.get(0).get("snippet"));
        }
    }
}
