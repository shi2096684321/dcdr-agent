package com.dcdr.agent.agent.governance;

import com.dcdr.agent.config.ToolsProperties;
import com.dcdr.agent.entity.LlmCallLogEntity;
import com.dcdr.agent.repository.LlmCallLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 联网用量统计的口径测试。
 *
 * <p><b>为什么这些数字必须钉住</b>: 这是用来算钱的。次数算错只是难看, <b>金额算错会误导决策</b>
 * —— 把免费的 SearXNG 调用按 DeepSeek 单价计价, 会得出"联网很贵, 关掉吧"的结论;
 * 而把历史无 provider 数据按某个源计价, 同样会凭空造出一笔不存在的支出。
 *
 * <p>本类覆盖四件事: 按源分组、成本乘法、未配置单价时不返回假数字、组合源按主源计价。
 */
class WebUsageServiceTest {

    private LlmCallLogRepository repository;
    private ToolsProperties properties;
    private WebUsageService service;

    @BeforeEach
    void setUp() {
        repository = mock(LlmCallLogRepository.class);
        properties = new ToolsProperties();
        service = new WebUsageService(repository, properties);
    }

    @Test
    @DisplayName("按源分组统计搜索/抓取次数")
    void groupsByProvider() {
        given(
                // 一次对话里: 2 次 deepseek 搜索 + 1 次 deepseek 抓取
                log("""
                        [{"kind":"web","tool":"web_search","provider":"deepseek"},
                         {"kind":"web","tool":"web_search","provider":"deepseek"},
                         {"kind":"web","tool":"web_extract","provider":"deepseek"}]
                        """),
                // 另一次: 1 次 searxng 搜索
                log("""
                        [{"kind":"web","tool":"web_search","provider":"searxng"}]
                        """));

        WebUsageService.UsageSummary s = service.summary(30);

        assertEquals(3, s.searchCalls(), "搜索总数应为 3");
        assertEquals(1, s.extractCalls(), "抓取总数应为 1");
        assertEquals(2, s.providers().size(), "应按源分成两组");

        WebUsageService.ProviderUsage deepseek = byProvider(s, "deepseek");
        assertEquals(2, deepseek.searchCalls());
        assertEquals(1, deepseek.extractCalls());
        WebUsageService.ProviderUsage searxng = byProvider(s, "searxng");
        assertEquals(1, searxng.searchCalls());
        assertEquals(0, searxng.extractCalls());
    }

    @Test
    @DisplayName("成本 = 次数 × 单价, 且按源分别定价(免费源不能按收费源计价)")
    void computesCostPerProvider() {
        properties.getWeb().getPricing().put("deepseek:web_search", new BigDecimal("0.005"));
        properties.getWeb().getPricing().put("deepseek:web_extract", new BigDecimal("0.003"));
        properties.getWeb().getPricing().put("searxng:web_search", BigDecimal.ZERO);
        given(
                log("""
                        [{"kind":"web","tool":"web_search","provider":"deepseek"},
                         {"kind":"web","tool":"web_search","provider":"deepseek"},
                         {"kind":"web","tool":"web_extract","provider":"deepseek"},
                         {"kind":"web","tool":"web_search","provider":"searxng"}]
                        """));

        WebUsageService.UsageSummary s = service.summary(30);

        // 2 × 0.005 + 1 × 0.003 = 0.013
        assertEquals(0, new BigDecimal("0.013").compareTo(byProvider(s, "deepseek").cost()));
        assertEquals(0, BigDecimal.ZERO.compareTo(byProvider(s, "searxng").cost()),
                "自建源单价 0, 不应产生费用");
        assertEquals(0, new BigDecimal("0.013").compareTo(s.estimatedCost()));
        assertTrue(s.pricingConfigured());
    }

    @Test
    @DisplayName("未配置单价: 只给次数, 金额为 null —— 不返回猜出来的 0 元")
    void noPricingYieldsNullCost() {
        given(log("""
                [{"kind":"web","tool":"web_search","provider":"deepseek"}]
                """));

        WebUsageService.UsageSummary s = service.summary(30);

        assertFalse(s.pricingConfigured());
        assertNull(s.estimatedCost(), "未配置单价时必须为 null, 界面显示「未配置单价」而不是 0 元");
        assertNull(byProvider(s, "deepseek").cost());
        assertEquals(1, s.searchCalls(), "次数仍要统计 —— 没有单价不等于没有用量");
    }

    @Test
    @DisplayName("历史数据没有 provider 字段: 归入 none 且不计费, 不猜它是哪个源")
    void legacyRowsWithoutProviderAreNotBilled() {
        properties.getWeb().getPricing().put("deepseek:web_search", new BigDecimal("0.005"));
        given(log("""
                [{"kind":"web","tool":"web_search","query":"旧数据"}]
                """));

        WebUsageService.UsageSummary s = service.summary(30);

        WebUsageService.ProviderUsage none = byProvider(s, "none");
        assertEquals(1, none.searchCalls(), "仍要计入总次数, 否则历史用量凭空消失");
        assertNull(none.cost(), "但绝不能按某个源计价 —— 那会造出一笔不存在的支出");
        assertEquals(0, BigDecimal.ZERO.compareTo(s.estimatedCost()));
    }

    @Test
    @DisplayName("组合源(兜底链 deepseek+local)按主源计价, 宁可高估不可低估")
    void compositeProviderBillsPrimary() {
        properties.getWeb().getPricing().put("deepseek:web_extract", new BigDecimal("0.003"));
        given(log("""
                [{"kind":"web","tool":"web_extract","provider":"deepseek+local"}]
                """));

        WebUsageService.UsageSummary s = service.summary(30);

        WebUsageService.ProviderUsage p = byProvider(s, "deepseek+local");
        assertEquals(0, new BigDecimal("0.003").compareTo(p.cost()));
    }

    @Test
    @DisplayName("单价 key 用 Spring 绑定后的形状(冒号被剔除)同样能命中")
    void pricingKeySurvivesSpringNormalization() {
        // 真实环境里 yml 写 "deepseek:web_search", Spring 绑进 Map 后是 "deepseekweb_search"。
        // 这里直接照绑定结果构造 —— 上一版测试全用带冒号的 key, 恰好绕过了真实形状,
        // 于是线上"配了单价却显示未配置"一直没被测出来。
        properties.getWeb().getPricing().put("deepseekweb_search", new BigDecimal("0.005"));
        properties.getWeb().getPricing().put("deepseekweb_extract", new BigDecimal("0.005"));
        given(log("""
                [{"kind":"web","tool":"web_search","provider":"deepseek"},
                 {"kind":"web","tool":"web_extract","provider":"deepseek+local"}]
                """));

        WebUsageService.UsageSummary s = service.summary(30);

        assertEquals(0, new BigDecimal("0.005").compareTo(byProvider(s, "deepseek").searchPrice()),
                "去冒号的 key 也要能查到单价");
        assertEquals(0, new BigDecimal("0.005").compareTo(byProvider(s, "deepseek+local").extractPrice()),
                "组合源按主源查, 同样要走归一化");
        assertEquals(0, new BigDecimal("0.010").compareTo(s.estimatedCost()));
        assertTrue(s.pricingMatched());
    }

    @Test
    @DisplayName("配了单价但 key 与来源名对不上: 标记未匹配, 不给 ¥0 的假结论")
    void unmatchedPricingIsFlagged() {
        properties.getWeb().getPricing().put("qwen:web_search", new BigDecimal("0.02"));
        given(log("""
                [{"kind":"web","tool":"web_search","provider":"deepseek"}]
                """));

        WebUsageService.UsageSummary s = service.summary(30);

        assertTrue(s.pricingConfigured(), "表里有值就算配了");
        assertFalse(s.pricingMatched(), "没有任何来源命中, 界面要提示 key 对不上");
        assertNull(byProvider(s, "deepseek").searchPrice());
    }

    @Test
    @DisplayName("按「成功请求」计费: 请求异常与未启用不算钱, 但「请求成功 0 条」照常算")
    void failedRequestsAreNotBilled() {
        properties.getWeb().getPricing().put("deepseek:web_search", new BigDecimal("0.005"));
        properties.getWeb().getPricing().put("deepseek:web_extract", new BigDecimal("0.005"));
        given(log("""
                [{"kind":"web","tool":"web_search","provider":"deepseek"},
                 {"kind":"web","tool":"web_search","provider":"deepseek","emptyReason":"no_results"},
                 {"kind":"web","tool":"web_search","provider":"deepseek","emptyReason":"disabled"},
                 {"kind":"web","tool":"web_extract","provider":"deepseek","emptyReason":"error"}]
                """));

        WebUsageService.UsageSummary s = service.summary(30);
        WebUsageService.ProviderUsage p = byProvider(s, "deepseek");

        assertEquals(3, p.searchCalls(), "搜索总次数要如实统计");
        assertEquals(2, p.billableSearch(), "只有 2 次该计费: 正常那次 + no_results 那次");
        assertEquals(1, p.extractCalls());
        assertEquals(0, p.billableExtract(), "请求异常(error)不计费");
        // 2 x 0.005 = 0.01; error 的抓取不进成本
        assertEquals(0, new BigDecimal("0.010").compareTo(p.cost()));
    }

    @Test
    @DisplayName("非 web 步骤(rag / llm)不得计入联网用量")
    void ignoresNonWebSteps() {
        given(log("""
                [{"kind":"llm","tool":"chat"},
                 {"kind":"rag","query":"kb"},
                 {"kind":"web","tool":"web_search","provider":"searxng"}]
                """));

        WebUsageService.UsageSummary s = service.summary(30);

        assertEquals(1, s.searchCalls(), "只该数那条 kind=web");
        assertEquals(0, s.extractCalls());
    }

    @Test
    @DisplayName("坏 JSON 静默跳过, 不因一条脏数据整轮失败")
    void brokenJsonIsSkipped() {
        given(log("not-a-json"), log("""
                [{"kind":"web","tool":"web_search","provider":"searxng"}]
                """));

        WebUsageService.UsageSummary s = service.summary(30);

        assertEquals(1, s.searchCalls());
    }

    // ===== 辅助 =====

    private static WebUsageService.ProviderUsage byProvider(WebUsageService.UsageSummary s, String id) {
        return s.providers().stream().filter(p -> id.equals(p.provider())).findFirst()
                .orElseThrow(() -> new AssertionError("没有这个源: " + id + "，实际: "
                        + s.providers().stream().map(WebUsageService.ProviderUsage::provider).toList()));
    }

    private void given(LlmCallLogEntity... logs) {
        when(repository.findForHealthAggregation(any(), any(), any())).thenReturn(List.of(logs));
    }

    private static LlmCallLogEntity log(String stepsJson) {
        LlmCallLogEntity e = new LlmCallLogEntity();
        e.setId(java.util.UUID.randomUUID().toString());
        e.setUserId("u-1");
        e.setStepsJson(stepsJson);
        e.setCreatedAt(LocalDateTime.now());
        return e;
    }
}
