package com.dcdr.agent.common.log;

import com.dcdr.agent.rag.RagSource;
import com.dcdr.agent.tool.WebStat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 调用链步骤的<b>字段契约</b>测试。
 *
 * <p>锁的不是"日志好不好看", 而是字段名本身 —— 它同时被三处消费:
 * 前端调用链面板按 {@code kind} 渲染、库侧聚合 SQL 按字段名解析
 * (见 {@code docs/eval/memory-health.sql} 与 {@code docs/eval/retrieval-health.sql})、
 * 以及落库的 {@code steps_json}。改字段名不会有任何编译错误, 只会让那几处的解析<b>静默失效</b> ——
 * 表现为"看板一直是空的", 而不是报错。
 */
class LlmCallTraceTest {

    @Test
    @DisplayName("记忆召回步骤: kind/query/hits/chars/costMs/memoryIds 一个都不能少")
    void memoryStepKeepsFieldContract() {
        LlmCallTrace trace = LlmCallTrace.begin();
        trace.recordMemory("用户问的这句话", 3, 412, 7, List.of("m1", "m2", "m3"));

        Map<String, Object> step = trace.snapshot().stream()
                .filter(s -> "memory".equals(s.get("kind")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未写入 kind=memory 的步骤"));

        assertEquals("用户问的这句话", step.get("query"), "条件触发的分析要靠它");
        assertEquals(3, step.get("hits"));
        assertEquals(412, step.get("chars"), "注入字数: 用来验证背景信息预算是否真的在起作用");
        assertEquals(7L, step.get("costMs"));
        assertEquals(List.of("m1", "m2", "m3"), step.get("memoryIds"),
                "库侧靠它反查「从未命中过的记忆」—— 少了它这条观测就没有意义");
    }

    @Test
    @DisplayName("RAG 步骤: 必须带 ragSource —— 否则分不清这次检索是谁发起的")
    void ragStepCarriesSource() {
        LlmCallTrace trace = LlmCallTrace.begin();
        trace.recordRag("用户问的这句话", 2, 3, 88, 0.82, List.of("doc1"), RagSource.TOOL);

        Map<String, Object> step = trace.snapshot().stream()
                .filter(s -> "rag".equals(s.get("kind")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未写入 kind=rag 的步骤"));

        assertEquals("tool", step.get("ragSource"),
                "看到 rag 步骤却在工具调用里找不到 knowledge_base_search 时, 全靠这个字段判断"
                        + "是模型自主调用、还是平台预检索/用户强制检索");
        assertEquals("用户问的这句话", step.get("query"));
        assertEquals(3, step.get("hits"));
        assertEquals(88L, step.get("costMs"));
        assertEquals(List.of("doc1"), step.get("docIds"));
    }

    // 此处原有「来源为空时不写该字段」的用例。参数类型改为 RagSource 后, "来源为空"
    // 在编译期就不可能发生 —— 该用例守的是一个不再存在的分支, 故删除。
    @Test
    @DisplayName("RAG 步骤: 中文名随值下发 —— 面板不再自己维护一份「取值 → 人话」的映射表")
    void ragStepCarriesSourceLabel() {
        LlmCallTrace trace = LlmCallTrace.begin();
        trace.recordRag("q", 1, 0, 5, 0.0, List.of(), RagSource.FORCED);

        Map<String, Object> step = trace.snapshot().stream()
                .filter(s -> "rag".equals(s.get("kind")))
                .findFirst()
                .orElseThrow();
        assertEquals("forced", step.get("ragSource"),
                "取值是面板、CSV、库侧聚合共同的契约, 不能随 enum 改名而变");
        assertEquals(RagSource.FORCED.label(), step.get("ragSourceLabel"),
                "中文名与取值出自同一处定义: 改了取值, 中文名跟着走, 不会一边中文一边英文");
    }

    @Test
    @DisplayName("失败留痕: 首个失败为准, 并补一个 kind=error 的收尾节点")
    void failureIsRecordedOnceAtFirstCause() {
        LlmCallTrace trace = LlmCallTrace.begin();
        trace.recordTool("web_search", 120, false);
        trace.fail("工具 web_search", "工具执行失败: 连接超时");
        trace.fail("第 2 轮生成", "模型调用失败: 后续级联错误");

        List<Map<String, Object>> steps = trace.snapshot();
        List<Map<String, Object>> errors = steps.stream()
                .filter(s -> "error".equals(s.get("kind")))
                .toList();
        // 级联错误会盖掉最初的错误 —— 那样详情页给出的永远是"最后一环", 排查价值最低的那条
        assertEquals(1, errors.size(), "首个失败为准, 后续 fail 调用应被忽略");
        assertEquals("工具 web_search", errors.get(0).get("failedStep"));
        assertEquals("工具执行失败: 连接超时", errors.get(0).get("message"));
        assertEquals(steps.size(), errors.get(0).get("seq"), "错误节点是时间轴的最后一步");
    }

    @Test
    @DisplayName("记忆召回步骤: 未命中时不写 memoryIds(与 rag 的 docIds 同口径, 省存储)")
    void memoryStepOmitsEmptyIds() {
        LlmCallTrace trace = LlmCallTrace.begin();
        trace.recordMemory("q", 0, 0, 1, List.of());

        Map<String, Object> step = trace.snapshot().stream()
                .filter(s -> "memory".equals(s.get("kind")))
                .findFirst()
                .orElseThrow();
        assertFalse(step.containsKey("memoryIds"), "空列表不该白占一个字段");
        assertEquals(0, step.get("hits"));
    }

    @Test
    @DisplayName("联网搜索步骤: rawCount 与 results 必须并存 —— 缺一个就分不清是谁的问题")
    void webSearchStepKeepsRawAndKept() {
        LlmCallTrace trace = LlmCallTrace.begin();
        trace.recordWeb(WebStat.search("searxng", "最新的 Java 版本", 20, 8), 1830);

        Map<String, Object> step = webStep(trace);
        assertEquals("web_search", step.get("tool"));
        assertEquals("最新的 Java 版本", step.get("query"), "空结果时全靠它去回溯是哪类查询失效了");
        assertEquals(20, step.get("rawCount"), "上游给了几条: 用来区分限流与规则过严");
        assertEquals(8, step.get("results"));
        assertEquals(1830L, step.get("costMs"));
        assertFalse(step.containsKey("emptyReason"), "有结果时不该带空结果原因");
    }

    @Test
    @DisplayName("联网搜索全被过滤: 必须记下 all_filtered, 而不是笼统的“没搜到”")
    void webSearchAllFilteredCarriesReason() {
        LlmCallTrace trace = LlmCallTrace.begin();
        // 上游给了 20 条, 清洗后 0 条 —— 这是我们自己的规则太严, 与上游限流是两回事
        trace.recordWeb(WebStat.search("searxng", "某话题", 20, 0), 900);

        Map<String, Object> step = webStep(trace);
        assertEquals(0, step.get("results"));
        assertEquals("all_filtered", step.get("emptyReason"));
        assertEquals("结果全被过滤", step.get("emptyReasonLabel"),
                "中文名由后端下发: 前端与库侧不再各自维护映射表");
    }

    @Test
    @DisplayName("联网搜索上游无返回: 成因是 no_results, 排查方向指向 SearXNG 而非清洗规则")
    void webSearchNoResultsCarriesReason() {
        LlmCallTrace trace = LlmCallTrace.begin();
        trace.recordWeb(WebStat.search("searxng", "某个极冷门的词", 0, 0), 1200);

        Map<String, Object> step = webStep(trace);
        assertEquals("no_results", step.get("emptyReason"));
    }

    @Test
    @DisplayName("网页抓取步骤: 不写 rawCount(该工具没有“上游返回几条”的概念, 写 0 会污染空结果率)")
    void webExtractStepOmitsRawCount() {
        LlmCallTrace trace = LlmCallTrace.begin();
        trace.recordWeb(WebStat.extract("local", "https://example.com/a", 2, 1, 9000, 0), 2500);

        Map<String, Object> step = webStep(trace);
        assertEquals("web_extract", step.get("tool"));
        assertFalse(step.containsKey("rawCount"), "web_extract 无上游概念, 不该出现该字段");
        // results 记成功篇数(2), 不是处理篇数(3): 混入失败会让"全失败"看起来像"拿到了东西"
        assertEquals(2, step.get("results"));
        assertEquals(2, step.get("extractOk"));
        assertEquals(1, step.get("extractFailed"));
        assertEquals(9000, step.get("contentChars"));
        assertEquals(0, step.get("thinPages"));
    }

    @Test
    @DisplayName("抓取全部失败: results 必须为 0, 否则健康度的空结果率统计不到 (旧实现填 ok+failed 会漏成 3)")
    void webExtractAllFailedReportsZeroResults() {
        LlmCallTrace trace = LlmCallTrace.begin();
        trace.recordWeb(WebStat.extract("local", "https://example.com/gone", 0, 3, 0, 0), 1800);

        Map<String, Object> step = webStep(trace);
        assertEquals(0, step.get("results"), "全失败要落到 0 —— emptyRate 正是用 results==0 判'什么都没拿到'");
        assertEquals(0, step.get("extractOk"));
        assertEquals(3, step.get("extractFailed"), "失败篇数不能丢, 否则看不出试了几个");
        assertEquals("no_results", step.get("emptyReason"));
    }

    @Test
    @DisplayName("合并统计(工具层能力): withExtract 把抓取数字补到同一条步骤, 只产出一条 web 步骤")
    void webSearchStepMergesExtractResult() {
        // 注意: 编排层已不再走这条路 —— 搜索与抓取现在是两条独立步骤(见下一个用例)。
        // 这个用例锁的是 WebStat.withExtract 本身的行为, 供"确实只需要一条合并统计"的场景使用。
        LlmCallTrace trace = LlmCallTrace.begin();
        WebStat merged = WebStat.search("searxng", "q", 20, 8).withExtract(8, 3, 1, 12000, 1);
        trace.recordWeb(merged, 5000);

        java.util.List<Map<String, Object>> all = trace.snapshot().stream()
                .filter(s -> "web".equals(s.get("kind"))).toList();
        assertEquals(1, all.size(), "withExtract 只回填字段, 不新增步骤");
        Map<String, Object> step = all.get(0);
        assertEquals(3, step.get("extractOk"));
        assertEquals(1, step.get("extractFailed"));
        assertEquals(1, step.get("thinPages"), "薄页数: 抓取成功率虚高的唯一证据");
        assertEquals(12000, step.get("contentChars"));
    }

    @Test
    @DisplayName("内容明细: 搜索记 items, 抓取记 pages 并标 phase=enrich —— 两者必须分开成两条步骤")
    void webSearchAndExtractKeepSeparateContent() {
        LlmCallTrace trace = LlmCallTrace.begin();

        // ① 搜索: 记它搜到的条目(标题/URL/摘要), 不再混入抓取数字
        trace.recordWeb(WebStat.search("searxng", "长沙 Java 招聘", 15, 15), 3000,
                LlmCallTrace.WebContent.ofItems(List.of(
                        Map.of("index", 1, "title", "某招聘页", "url", "https://a.com/job/1",
                                "snippet", "岗位职责……"))));
        // ② 抓取: 独立一条, 记抓了哪几篇、正文是什么
        trace.recordWeb(WebStat.extract("deepseek", "长沙 Java 招聘", 1, 1, 3200, 0), 12000,
                LlmCallTrace.WebContent.ofEnrichPages(List.of(
                        Map.of("index", 1, "title", "某招聘页", "url", "https://a.com/job/1",
                                "ok", true, "chars", 3200, "content", "岗位职责全文……"),
                        Map.of("index", 2, "title", "某站点", "url", "https://b.com/x",
                                "ok", false, "chars", 0, "reason", "返回正文为空"))));

        List<Map<String, Object>> webSteps = trace.snapshot().stream()
                .filter(s -> "web".equals(s.get("kind"))).toList();
        assertEquals(2, webSteps.size(), "搜索与抓取各一条 —— 合并就看不到'抓了哪几篇、正文是什么'");

        Map<String, Object> search = webSteps.get(0);
        assertEquals("web_search", search.get("tool"));
        assertFalse(search.containsKey("pages"), "搜索步骤不该混入抓取明细");
        assertEquals(1, ((List<?>) search.get("items")).size());
        assertFalse(search.containsKey("extractOk"), "拆开后搜索步骤不再背抓取数字");

        Map<String, Object> extract = webSteps.get(1);
        assertEquals("web_extract", extract.get("tool"));
        assertEquals("enrich", extract.get("phase"), "健康度靠 phase 把串联抓取排除在'联网调用数'之外");
        assertFalse(extract.containsKey("items"), "抓取步骤不该混入搜索条目");
        List<?> pages = (List<?>) extract.get("pages");
        assertEquals(2, pages.size());
        Map<?, ?> okPage = (Map<?, ?>) pages.get(0);
        assertEquals("岗位职责全文……", okPage.get("content"), "正文原样保留: 抓取到什么就记什么");
        Map<?, ?> failedPage = (Map<?, ?>) pages.get(1);
        assertEquals(false, failedPage.get("ok"));
        assertEquals("返回正文为空", failedPage.get("reason"), "失败也要留原因, 否则不知道是反爬还是空壳页");
    }

    @Test
    @DisplayName("联网统计为 null(非联网工具)时不产生步骤")
    void nullWebStatIsIgnored() {
        LlmCallTrace trace = LlmCallTrace.begin();
        trace.recordWeb(null, 100);
        assertEquals(0, trace.snapshot().size());
    }

    private static Map<String, Object> webStep(LlmCallTrace trace) {
        return trace.snapshot().stream()
                .filter(s -> "web".equals(s.get("kind")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未写入 kind=web 的步骤"));
    }
}
