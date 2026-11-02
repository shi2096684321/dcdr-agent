package com.dcdr.agent.agent.governance;

import com.dcdr.agent.config.ToolsProperties;
import com.dcdr.agent.entity.LlmCallLogEntity;
import com.dcdr.agent.repository.LlmCallLogRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 联网用量与成本统计。
 *
 * <h2>它回答的问题</h2>
 * "这个月联网花了多少钱、花在搜索还是抓取上、哪个源烧得快" —— 与"联网健康度"是两件事:
 * 健康度看<b>效果</b>(空结果率、抓取成功率), 这里只看<b>量</b>。两者都从
 * {@code llm_call_logs.steps_json} 的 {@code kind=web} 步骤来, 不需要额外的埋点表。
 *
 * <h2>为什么必须按 provider 分开记</h2>
 * DeepSeek 按次计费、自建 SearXNG 只有服务器成本。混成一个总数算钱是错的, 而"错的钱"
 * 比"没有钱"更危险 —— 它会让人以为联网很贵直接关掉, 实际可能只是把免费的那部分也算进去了。
 *
 * <h2>为什么默认不估钱</h2>
 * 单价随合同/账单变化, 代码里猜一个默认值, 上线后没人会去核对。所以
 * {@code agent-tools.web.pricing} 默认为空, 此时只统计次数并在响应里标 {@code pricingConfigured=false},
 * 界面显示"未配置单价"而不是一个假数字。
 *
 * <h2>已知限制</h2>
 * 数据靠扫日志, 有 {@link #SCAN_LIMIT} 上限; 超限时取最近的部分并在响应里标 {@code truncated}。
 * 真实流量大到这个量级时, 应改为"每次调用累加一张日汇总表", 而不是继续加大扫描上限。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebUsageService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 与 WebHealthService 同口径: 超过这个条数只看最近的部分, 并明确回报截断 */
    private static final int SCAN_LIMIT = 5000;
    private static final int MAX_DAYS = 180;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final LlmCallLogRepository llmCallLogRepository;
    private final ToolsProperties toolsProperties;

    public UsageSummary summary(int days) {
        int window = Math.min(Math.max(days, 1), MAX_DAYS);
        LocalDateTime to = LocalDateTime.now();
        LocalDateTime from = to.minusDays(window);

        List<LlmCallLogEntity> logs = llmCallLogRepository.findForHealthAggregation(
                null, from, PageRequest.of(0, SCAN_LIMIT + 1));
        boolean truncated = logs.size() > SCAN_LIMIT;
        if (truncated) {
            logs = logs.subList(logs.size() - SCAN_LIMIT, logs.size());
        }

        // 按 provider 汇总; 按天汇总单独记, 因为趋势图要的是"每天多少", 与源的维度正交
        // provider → [搜索次数, 抓取次数, 计费搜索, 计费抓取]
        Map<String, long[]> byProvider = new LinkedHashMap<>();
        Map<String, long[]> byDay = new LinkedHashMap<>();        // yyyy-MM-dd → [search, extract]
        long searchTotal = 0;
        long extractTotal = 0;
        long billableSearch = 0;
        long billableExtract = 0;

        for (LlmCallLogEntity log : logs) {
            String day = log.getCreatedAt() == null ? null : log.getCreatedAt().format(DAY);
            for (Map<String, Object> step : parseSteps(log.getStepsJson())) {
                if (!"web".equals(step.get("kind"))) {
                    continue;
                }
                String tool = str(step.get("tool"));
                boolean isSearch = "web_search".equals(tool);
                // 历史数据没有 provider 字段(该字段是后加的); 记为 none 且不计费,
                // 而不是去猜"当时配的应该是哪个源" —— 猜错的钱比缺失的钱更难发现
                String provider = str(step.get("provider"));
                if (provider.isBlank()) {
                    provider = "none";
                }
                // DeepSeek 按「每 1000 次**成功请求**」计费: 请求异常(error)与根本没发出(disabled)
                // 都不该算钱。而 no_results / all_filtered 是"请求成功了但结果不合格", 照常计费 ——
                // 后者恰恰是最贵的失败: 付了钱、模型啥也没拿到。
                String reason = str(step.get("emptyReason"));
                boolean billable = !"error".equals(reason) && !"disabled".equals(reason);
                if (isSearch) {
                    searchTotal++;
                    if (billable) {
                        billableSearch++;
                    }
                } else {
                    extractTotal++;
                    if (billable) {
                        billableExtract++;
                    }
                }
                int idx = isSearch ? 0 : 1;
                long[] agg = byProvider.computeIfAbsent(provider, k -> new long[4]);
                agg[idx]++;
                if (billable) {
                    agg[idx + 2]++;
                }
                if (day != null) {
                    byDay.computeIfAbsent(day, k -> new long[2])[idx]++;
                }
            }
        }

        boolean pricingConfigured = !toolsProperties.getWeb().getPricing().isEmpty();
        List<ProviderUsage> providers = new ArrayList<>();
        BigDecimal totalCost = BigDecimal.ZERO;
        for (Map.Entry<String, long[]> e : byProvider.entrySet()) {
            long[] v = e.getValue();
            ProviderUsage u = buildProviderUsage(e.getKey(), v[0], v[1], v[2], v[3]);
            providers.add(u);
            if (u.cost() != null) {
                totalCost = totalCost.add(u.cost());
            }
        }

        List<DayUsage> daily = new ArrayList<>();
        byDay.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> daily.add(new DayUsage(e.getKey(), e.getValue()[0], e.getValue()[1])));

        return new UsageSummary(window, truncated, searchTotal, extractTotal,
                billableSearch, billableExtract,
                pricingConfigured ? totalCost : null, pricingConfigured, providers, daily);
    }

    private ProviderUsage buildProviderUsage(String provider, long search, long extract,
                                             long billableSearch, long billableExtract) {
        BigDecimal searchPrice = price(provider, "web_search");
        BigDecimal extractPrice = price(provider, "web_extract");
        BigDecimal cost = null;
        if (searchPrice != null || extractPrice != null) {
            cost = BigDecimal.ZERO;
            if (searchPrice != null) {
                // 单价按"每 1000 次"报价, 这里换算成元/次后再乘
                cost = cost.add(searchPrice.multiply(BigDecimal.valueOf(billableSearch)));
            }
            if (extractPrice != null) {
                cost = cost.add(extractPrice.multiply(BigDecimal.valueOf(billableExtract)));
            }
        }
        return new ProviderUsage(provider, search, extract, billableSearch, billableExtract,
                searchPrice, extractPrice, cost);
    }

    /**
     * 查单价: 先查 {@code provider:tool}, 再退化到 {@code provider}(按源统一价)。
     * <p>组合源(如兜底链 {@code deepseek+local})按<b>主源</b>计价: 兜底成功时也可能真的调了主源,
     * 主源是有单价的那个, 按它算只会高估不会低估 —— 成本估算宁可保守。
     */
    private BigDecimal price(String provider, String tool) {
        Map<String, BigDecimal> pricing = toolsProperties.getWeb().getPricing();
        if (pricing == null || pricing.isEmpty() || "none".equals(provider)) {
            return null;
        }
        String billing = provider.contains("+") ? provider.split("\\+")[0] : provider;
        BigDecimal direct = pricing.get(billing + ":" + tool);
        return direct != null ? direct : pricing.get(billing);
    }

    private static List<Map<String, Object>> parseSteps(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    // ===================== 输出 =====================

    /**
     * @param estimatedCost 为 null 表示未配置单价(界面应显示"未配置单价"而不是 0 元)
     */
    public record UsageSummary(int days, boolean truncated,
                               long searchCalls, long extractCalls,
                               long billableSearchCalls, long billableExtractCalls,
                               BigDecimal estimatedCost, boolean pricingConfigured,
                               List<ProviderUsage> providers, List<DayUsage> daily) {
    }

    /**
     * @param billableSearch  实际计费的搜索次数(排除请求异常 / 未启用)
     * @param searchPrice     元/次(由"每 1000 次"的报价换算而来)
     * @param cost            null 表示未配置单价
     */
    public record ProviderUsage(String provider, long searchCalls, long extractCalls,
                                long billableSearch, long billableExtract,
                                BigDecimal searchPrice, BigDecimal extractPrice, BigDecimal cost) {
    }

    public record DayUsage(String date, long searchCalls, long extractCalls) {
    }
}
