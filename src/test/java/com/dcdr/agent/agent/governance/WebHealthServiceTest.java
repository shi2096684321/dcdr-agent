package com.dcdr.agent.agent.governance;

import com.dcdr.agent.dto.WebHealthDtos;
import com.dcdr.agent.entity.LlmCallLogEntity;
import com.dcdr.agent.repository.LlmCallLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link WebHealthService} 的<b>口径</b>测试。
 *
 * <p><b>为什么这些口径必须锁住</b>: 看板上的每个百分比都会被用来判断
 * "要不要去查 SearXNG / 要不要改清洗规则", 而分母选错不会报错, 只会给出一个
 * 看着正常、方向相反的结论。几个真实的坑:
 * <ul>
 *   <li>{@code web_extract} 的 {@code rawCount} 是 -1(不适用), 若当成 0 计入存活率,
 *       会把"没抓取"算成"存活率 0%";</li>
 *   <li>薄页率的分母必须是<b>抓取成功篇数</b>而不是调用次数, 否则一次抓 3 篇里有 1 篇薄页
 *       与一次抓 1 篇且是薄页, 会被算成同一个比率;</li>
 *   <li>空结果率的口径是 {@code results == 0}, 搜索没搜到与抓取全失败都算 ——
 *       两者对下游的影响一样(模型手里没有资料)。</li>
 * </ul>
 */
class WebHealthServiceTest {

    private LlmCallLogRepository repository;
    private WebHealthService service;

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 30, 12, 0);

    @BeforeEach
    void setUp() {
        repository = mock(LlmCallLogRepository.class);
        service = new WebHealthService(repository);
    }

    @Test
    @DisplayName("空结果率: results=0 才算空, 有结果的不计入")
    void emptyRateCountsOnlyZeroResults() {
        given(
                log("l1", NOW.minusHours(1), stepSearch("q1", 20, 8, 3, 1, 12000, 0)),
                log("l2", NOW.minusHours(2), stepSearch("q2", 0, 0, 0, 0, 0, 0)),
                log("l3", NOW.minusHours(3), stepSearch("q3", 20, 0, 0, 0, 0, 0)),
                log("l4", NOW.minusHours(4), stepSearch("q4", 15, 5, 2, 0, 8000, 0)));

        WebHealthDtos.HealthOverview vo = service.overview(null, 30);

        assertEquals(4, vo.getWebCalls());
        // l2(上游没给) 与 l3(被过滤光) 都是空结果 —— 对下游而言都是"模型手里没资料"
        assertEquals(0.5, vo.getEmptyRate(), 0.001);
    }

    @Test
    @DisplayName("清洗存活率: web_extract 的 rawCount=-1 不得计入, 否则被算成 0% 存活")
    void keepRatioIgnoresExtractCalls() {
        given(
                log("l1", NOW.minusHours(1), stepSearch("q1", 20, 10, 0, 0, 0, 0)),
                // 直接抓取: 没有"上游返回几条"的概念, rawCount 字段根本不存在
                log("l2", NOW.minusHours(2), stepExtract("https://a.com/x", 2, 1, 9000, 0)));

        WebHealthDtos.HealthOverview vo = service.overview(null, 30);

        assertEquals(2, vo.getWebCalls());
        assertEquals(1, vo.getSearchCalls());
        assertEquals(1, vo.getExtractCalls());
        // 只有 l1 参与: 10/20 = 0.5。若把 l2 的 -1 也算进来, 分母会变成 2、结果掉到 0.25 附近
        assertEquals(0.5, vo.getAvgKeepRatio(), 0.001);
    }

    @Test
    @DisplayName("抓取成功率与薄页率: 都以「篇」为单位, 不是以「调用次数」为单位")
    void extractRatesArePerPage() {
        given(
                // 一次调用抓 4 篇: 3 成功 1 失败, 其中 1 篇是薄页
                log("l1", NOW.minusHours(1), stepExtract("https://a.com/x", 3, 1, 6000, 1)),
                // 另一次抓 1 篇, 且是薄页
                log("l2", NOW.minusHours(2), stepExtract("https://b.com/y", 1, 0, 80, 1)));

        WebHealthDtos.HealthOverview vo = service.overview(null, 30);

        // 成功 4 篇 / 总 5 篇
        assertEquals(0.8, vo.getExtractSuccessRate(), 0.001);
        // 薄页 2 篇 / 成功 4 篇 —— 若按调用次数算(2/2)会得到 100%, 明显失真
        assertEquals(0.5, vo.getThinRate(), 0.001);
    }

    @Test
    @DisplayName("空结果成因分布: 上游无返回 与 清洗全过滤 必须分开统计")
    void reasonBucketsSeparateUpstreamFromFiltering() {
        given(
                log("l1", NOW.minusHours(1), stepSearchEmpty("q1", 0, "no_results", "搜索引擎无返回")),
                log("l2", NOW.minusHours(2), stepSearchEmpty("q2", 20, "all_filtered", "结果全被过滤")),
                log("l3", NOW.minusHours(3), stepSearchEmpty("q3", 20, "all_filtered", "结果全被过滤")),
                log("l4", NOW.minusHours(4), stepSearch("q4", 20, 8, 0, 0, 0, 0)));

        WebHealthDtos.HealthOverview vo = service.overview(null, 30);

        assertEquals(2, vo.getByReason().size());
        WebHealthDtos.ReasonBucket filtered = vo.getByReason().get(0);
        assertEquals("all_filtered", filtered.getReason(), "次数多的排前面");
        assertEquals("结果全被过滤", filtered.getLabel());
        assertEquals(2, filtered.getCalls());
        assertEquals(0.5, filtered.getShare(), 0.001);
        assertEquals(20L, filtered.getAvgRawCount(), "上游给了 20 条却全被过滤 —— 这是规则过严的直接证据");
    }

    @Test
    @DisplayName("异常样本: 上游 >=10 条却只留 <=2 条才进列表")
    void weakSamplesOnlyIncludeSevereLoss() {
        given(
                log("l1", NOW.minusHours(1), stepSearch("严重", 20, 1, 0, 0, 0, 0)),
                log("l2", NOW.minusHours(2), stepSearch("正常", 20, 15, 0, 0, 0, 0)),
                log("l3", NOW.minusHours(3), stepSearch("量太小", 5, 0, 0, 0, 0, 0)));

        WebHealthDtos.HealthOverview vo = service.overview(null, 30);

        assertEquals(1, vo.getWeakSamples().size(), "只有第 1 条够得上『上游>=10 且保留<=2』");
        WebHealthDtos.WeakSample w = vo.getWeakSamples().get(0);
        assertEquals("严重", w.getQuery());
        assertEquals(20, w.getRawCount());
        assertEquals(1, w.getResults());
        assertEquals(0.05, w.getKeepRatio(), 0.001);
    }

    @Test
    @DisplayName("薄页样本: 只有『抓取成功且全是薄页』才进列表")
    void thinSamplesRequireAllPagesThin() {
        given(
                log("l1", NOW.minusHours(1), stepExtract("https://login.example/a", 2, 0, 120, 2)),
                // 抓了 2 篇只有 1 篇薄页 —— 属正常(混了一篇好文), 不该进样本
                log("l2", NOW.minusHours(2), stepExtract("https://ok.example/b", 2, 0, 9000, 1)));

        WebHealthDtos.HealthOverview vo = service.overview(null, 30);

        assertEquals(1, vo.getThinSamples().size());
        assertEquals("https://login.example/a", vo.getThinSamples().get(0).getQuery());
    }

    @Test
    @DisplayName("平均正文长度: 没抓到正文的调用(contentChars=0)不计入均值")
    void avgContentCharsSkipsEmptyExtractions() {
        given(
                log("l1", NOW.minusHours(1), stepExtract("https://a.com/x", 1, 0, 1000, 0)),
                log("l2", NOW.minusHours(2), stepExtract("https://b.com/y", 0, 1, 0, 0)));

        WebHealthDtos.HealthOverview vo = service.overview(null, 30);

        assertEquals(1000L, vo.getAvgContentChars(), "抓失败的那次若按 0 计入, 均值会掉到 500");
    }

    @Test
    @DisplayName("无联网记录: 所有比率返回 null 或 0, 不能除零")
    void emptyWindowYieldsNulls() {
        List<LlmCallLogEntity> realLogs = List.of(log("l1", NOW.minusHours(1), "[]"));
        when(repository.findForHealthAggregation(any(), any(), any())).thenReturn(realLogs);

        WebHealthDtos.HealthOverview vo = service.overview(null, 30);

        assertEquals(0, vo.getWebCalls());
        assertEquals(0, vo.getEmptyRate());
        assertNull(vo.getAvgKeepRatio(), "没有搜索调用 → 存活率无意义, 返回 null 而不是 0");
        assertNull(vo.getExtractSuccessRate());
        assertNull(vo.getThinRate());
        assertNull(vo.getAvgContentChars());
        assertTrue(vo.getByReason().isEmpty());
        assertTrue(vo.getTrend().isEmpty());
    }

    @Test
    @DisplayName("步骤 JSON 损坏: 跳过该条, 不影响其它记录")
    void brokenStepsJsonIsSkipped() {
        given(
                log("broken", NOW.minusHours(1), "{not-json"),
                log("ok", NOW.minusHours(2), stepSearch("q", 20, 8, 0, 0, 0, 0)));

        WebHealthDtos.HealthOverview vo = service.overview(null, 30);

        assertEquals(1, vo.getWebCalls(), "损坏的日志应被静默跳过, 而不是让整个接口失败");
        assertNotNull(vo.getAvgKeepRatio());
    }

    @Test
    @DisplayName("拆分后的串联抓取步骤: 不计入联网调用数, 但抓取数字必须统计到")
    void enrichStepNotCountedAsWebCall() {
        given(
                log("l1", NOW.minusHours(1), stepSearchAndEnrich("q1", 20, 8, 3, 1, 9000, 0)),
                log("l2", NOW.minusHours(2), stepSearchAndEnrich("q2", 10, 10, 0, 3, 0, 0)));

        WebHealthDtos.HealthOverview vo = service.overview(null, 30);

        // 两次搜索 + 两条串联抓取步骤, 调用数只能是 2 —— 否则"联网调用次数"凭空翻倍
        assertEquals(2, vo.getWebCalls());
        assertEquals(2, vo.getSearchCalls());
        assertEquals(0, vo.getExtractCalls(), "串联抓取不是模型发起的独立调用");
        // 抓取成功率跨两种形态都算: 成功 3 / (3+1+0+3)
        assertEquals(3.0 / 7, vo.getExtractSuccessRate(), 0.001);
        // 空结果率只看顶层: 两次搜索都有结果 → 0。若不排除 enrich, l2 的"0 篇成功"会把它拉到 0.25
        assertEquals(0.0, vo.getEmptyRate(), 0.001);
        // 按天趋势同口径
        assertEquals(2, vo.getTrend().get(0).getWebCalls());
    }

    @Test
    @DisplayName("新旧数据混存: 老形态的抓取数字在搜索步骤上, 新形态在 enrich 步骤上, 不得重复计算")
    void mixedOldAndNewExtractStatsDoNotDoubleCount() {
        given(
                // 老形态: 搜索 + 抓取合并成一条(搜索步骤自带 extractOk/extractFailed)
                log("l1", NOW.minusHours(1), stepSearch("q1", 20, 8, 3, 1, 9000, 0)),
                // 新形态: 搜索一条 + enrich 抓取一条(搜索侧抓取字段恒为 0)
                log("l2", NOW.minusHours(2), stepSearchAndEnrich("q2", 15, 15, 2, 0, 6000, 0)));

        WebHealthDtos.HealthOverview vo = service.overview(null, 30);

        assertEquals(2, vo.getWebCalls());
        // 成功 3(老) + 2(新) / 总 (3+1) + (2+0) = 5/6
        assertEquals(5.0 / 6, vo.getExtractSuccessRate(), 0.001);
        // 正文均值: 老形态 9000 一条、新形态 6000 一条(搜索步骤的内容字数恒为 0, 不参与)
        assertEquals(7500L, vo.getAvgContentChars());
    }

    // ==================================================================
    //  构造
    // ==================================================================

    /** 当前窗口返回给定日志, 上一周期返回空(避免 prev 干扰断言) */
    private void given(LlmCallLogEntity... logs) {
        when(repository.findForHealthAggregation(any(), any(), any()))
                .thenReturn(List.of(logs))      // 当前窗口
                .thenReturn(List.of());         // 上一周期
    }

    private static LlmCallLogEntity log(String id, LocalDateTime at, String stepsJson) {
        LlmCallLogEntity e = new LlmCallLogEntity();
        e.setId(id);
        e.setUserId("u1");
        e.setCreatedAt(at);
        e.setStepsJson(stepsJson);
        return e;
    }

    private static String stepSearch(String query, int raw, int results,
                                     int extractOk, int extractFailed, int contentChars, int thin) {
        return "[{\"kind\":\"web\",\"seq\":1,\"tool\":\"web_search\",\"query\":\"" + query + "\","
                + "\"costMs\":1200,\"rawCount\":" + raw + ",\"results\":" + results + ","
                + "\"extractOk\":" + extractOk + ",\"extractFailed\":" + extractFailed + ","
                + "\"contentChars\":" + contentChars + ",\"thinPages\":" + thin + "}]";
    }

    private static String stepSearchEmpty(String query, int raw, String reason, String label) {
        return "[{\"kind\":\"web\",\"seq\":1,\"tool\":\"web_search\",\"query\":\"" + query + "\","
                + "\"costMs\":900,\"rawCount\":" + raw + ",\"results\":0,"
                + "\"emptyReason\":\"" + reason + "\",\"emptyReasonLabel\":\"" + label + "\"}]";
    }

    private static String stepExtract(String url, int ok, int failed, int contentChars, int thin) {
        return "[{\"kind\":\"web\",\"seq\":1,\"tool\":\"web_extract\",\"query\":\"" + url + "\","
                + "\"costMs\":2000,\"results\":" + (ok + failed) + ","
                + "\"extractOk\":" + ok + ",\"extractFailed\":" + failed + ","
                + "\"contentChars\":" + contentChars + ",\"thinPages\":" + thin + "}]";
    }

    /**
     * 拆分后的形态: 一条搜索 + 一条 phase=enrich 的串联抓取。
     * <p>搜索步骤不再背抓取数字(那是老形态), 抓取数字整体搬到 enrich 步骤上。
     */
    private static String stepSearchAndEnrich(String query, int raw, int results,
                                              int ok, int failed, int contentChars, int thin) {
        return "[{\"kind\":\"web\",\"seq\":1,\"tool\":\"web_search\",\"query\":\"" + query + "\","
                + "\"costMs\":3000,\"rawCount\":" + raw + ",\"results\":" + results + "},"
                + "{\"kind\":\"web\",\"seq\":2,\"tool\":\"web_extract\",\"phase\":\"enrich\","
                + "\"query\":\"" + query + "\",\"costMs\":2500,\"results\":" + ok + ","
                + "\"extractOk\":" + ok + ",\"extractFailed\":" + failed + ","
                + "\"contentChars\":" + contentChars + ",\"thinPages\":" + thin + "}]";
    }
}
