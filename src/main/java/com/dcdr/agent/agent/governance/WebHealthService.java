package com.dcdr.agent.agent.governance;

import com.dcdr.agent.dto.WebHealthDtos;
import com.dcdr.agent.entity.LlmCallLogEntity;
import com.dcdr.agent.repository.LlmCallLogRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 联网健康度聚合 (联网搜索 / 抓取的可观测性)。
 *
 * <p><b>它回答的问题</b>: "线上联网效果有没有在漂移" —— 与固定评测是两回事:
 * <ul>
 *   <li>{@code WebExtractFixtureTest}(存档页面) 与搜索金标评测回答的是
 *       "<b>代码改动</b>是否让抽取/召回变差", 用的是冻结语料;</li>
 *   <li>本服务看的是<b>真实流量</b>, 回答的是"随着站点改版、引擎限流、查询分布变化,
 *       效果是否在漂移"。</li>
 * </ul>
 *
 * <p><b>为什么第一指标是空结果率, 而不是像检索那样看相似度</b>:
 * 检索退化表现为"命中了但不太相关"; 联网退化的第一表现是<b>一条都没拿到</b> ——
 * 而后者在对话界面里完全看不出来, 模型会拿空上下文继续作答。
 *
 * <p><b>数据来源</b>: {@code llm_call_logs.steps_json} 里 {@code kind=web} 的步骤,
 * 由 {@code LlmCallTrace.recordWeb} 写入(字段定义见 {@code WebStat})。
 *
 * <p><b>为什么在 Java 侧解析而不写 nativeQuery</b>: 与 {@link RetrievalHealthService} 同因 ——
 * {@code steps_json} 虽是 JSON 文本, 但 PG 的 {@code jsonb_array_elements} 属方言特性,
 * 用了会绑死数据库并在 H2 下报错。故只取行、在 Java 侧用 Jackson 展开聚合,
 * 代价是内存扫描, 因此有 {@link #SCAN_LIMIT} 上限并在响应里回报是否被截断。
 *
 * <p><b>只读</b>: 本服务不写入任何业务数据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebHealthService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 扫描上限: 与检索健康度同口径, 超限时取最近的部分并在响应里标记截断 */
    private static final int SCAN_LIMIT = 5000;

    /** 样本条数上限(样本仅用于人工排查, 不需要全量) */
    private static final int SAMPLE_LIMIT = 50;

    /** 处理天数上限, 防止一次拉取过大的窗口 */
    private static final int MAX_DAYS = 180;

    /**
     * "清洗存活率异常"的门槛: 上游给了至少 10 条, 却只留下 2 条以内。
     * <p>低于这个量的样本噪声太大(上游只给 3 条、留下 1 条属正常波动), 不值得人工看。
     */
    private static final int WEAK_RAW_MIN = 10;
    private static final int WEAK_KEEP_MAX = 2;

    private final LlmCallLogRepository llmCallLogRepository;

    public WebHealthDtos.HealthOverview overview(String userId, int days) {
        int window = Math.min(Math.max(days, 1), MAX_DAYS);
        LocalDateTime to = LocalDateTime.now();
        LocalDateTime from = to.minusDays(window);

        Window current = collect(userId, from, to);
        // 上一等长周期, 仅用于给出"在变好还是变差"的方向判断
        Window previous = collect(userId, from.minusDays(window), from);

        return assemble(current, previous, from, to, window);
    }

    // ==================================================================
    //  取数: 行 → 联网步骤明细
    // ==================================================================

    private Window collect(String userId, LocalDateTime from, LocalDateTime to) {
        List<LlmCallLogEntity> logs = llmCallLogRepository.findForHealthAggregation(
                userId, from, PageRequest.of(0, SCAN_LIMIT + 1));

        boolean truncated = logs.size() > SCAN_LIMIT;
        if (truncated) {
            logs = logs.subList(logs.size() - SCAN_LIMIT, logs.size());
        }

        List<WebStep> steps = new ArrayList<>();
        for (LlmCallLogEntity log : logs) {
            if (log.getCreatedAt() == null || log.getCreatedAt().isAfter(to)) {
                continue;
            }
            for (Map<String, Object> step : parseSteps(log.getStepsJson())) {
                if (!"web".equals(step.get("kind"))) {
                    continue;
                }
                steps.add(new WebStep(
                        log.getId(),
                        log.getCreatedAt(),
                        log.getUserId(),
                        str(step.get("tool")),
                        str(step.get("query")),
                        // rawCount 缺失表示该工具不适用(web_extract 没有"上游返回几条"的概念),
                        // 记为 -1 而不是 0 —— 否则会被算进空结果率
                        step.containsKey("rawCount") ? intOf(step.get("rawCount")) : -1,
                        intOf(step.get("results")),
                        intOf(step.get("extractOk")),
                        intOf(step.get("extractFailed")),
                        intOf(step.get("contentChars")),
                        intOf(step.get("thinPages")),
                        str(step.get("emptyReason")),
                        str(step.get("emptyReasonLabel")),
                        longOf(step.get("costMs"))));
            }
        }
        return new Window(steps, logs.size(), truncated);
    }

    private static List<Map<String, Object>> parseSteps(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            // 单条日志解析失败不应影响整体统计
            log.debug("调用链步骤解析失败, 该条跳过: {}", e.getMessage());
            return List.of();
        }
    }

    // ==================================================================
    //  聚合
    // ==================================================================

    private WebHealthDtos.HealthOverview assemble(Window current, Window previous,
                                                  LocalDateTime from, LocalDateTime to, int window) {
        List<WebStep> steps = current.steps();

        WebHealthDtos.HealthOverview vo = new WebHealthDtos.HealthOverview();
        vo.setFrom(from);
        vo.setTo(to);
        vo.setDays(window);
        vo.setScannedLogs(current.scannedLogs());
        vo.setTruncated(current.truncated());
        vo.setScanLimit(SCAN_LIMIT);

        vo.setWebCalls(steps.size());
        vo.setSearchCalls(steps.stream().filter(s -> "web_search".equals(s.tool())).count());
        vo.setExtractCalls(steps.stream().filter(s -> "web_extract".equals(s.tool())).count());
        vo.setEmptyRate(emptyRate(steps));
        vo.setAvgKeepRatio(avgKeepRatio(steps));
        vo.setExtractSuccessRate(extractSuccessRate(steps));
        vo.setThinRate(thinRate(steps));
        vo.setAvgContentChars(avgContentChars(steps));
        vo.setP95CostMs(p95CostMs(steps));

        vo.setTrend(buildTrend(steps));
        vo.setByReason(buildByReason(steps));
        vo.setWeakSamples(buildWeakSamples(steps));
        vo.setThinSamples(buildThinSamples(steps));
        vo.setPrev(previous.steps().isEmpty() ? null : buildPrev(previous.steps()));
        return vo;
    }

    /** 空结果占比: results=0 即"这次什么都没拿到"(搜索没搜到 与 抓取全失败 都算) */
    private static double emptyRate(List<WebStep> steps) {
        if (steps.isEmpty()) {
            return 0;
        }
        long empty = steps.stream().filter(s -> s.results() == 0).count();
        return round3((double) empty / steps.size());
    }

    /**
     * 清洗存活率: 保留条数 ÷ 上游原始条数。
     * <p>只统计 {@code rawCount > 0} 的步骤 —— {@code web_extract} 没有上游概念(-1),
     * 而 {@code rawCount=0} 是"上游一条没给", 算进来会把它当成 0 存活率, 让整体失真。
     */
    private static Double avgKeepRatio(List<WebStep> steps) {
        double sum = 0;
        int n = 0;
        for (WebStep s : steps) {
            if (s.rawCount() > 0) {
                sum += Math.min(1.0, (double) s.results() / s.rawCount());
                n++;
            }
        }
        return n == 0 ? null : round3(sum / n);
    }

    /** 抓取成功率: 成功篇数 ÷ (成功 + 失败); 仅统计触发了抓取的步骤 */
    private static Double extractSuccessRate(List<WebStep> steps) {
        long ok = steps.stream().mapToLong(WebStep::extractOk).sum();
        long failed = steps.stream().mapToLong(WebStep::extractFailed).sum();
        long total = ok + failed;
        return total == 0 ? null : round3((double) ok / total);
    }

    /**
     * 薄页占比: 正文过短的篇数 ÷ 抓取成功篇数。
     * <p>这是"抓取成功率虚高"的证据 —— 抓回登录页也算成功, 但模型拿到的只有一句"请先登录"。
     */
    private static Double thinRate(List<WebStep> steps) {
        long ok = steps.stream().mapToLong(WebStep::extractOk).sum();
        long thin = steps.stream().mapToLong(WebStep::thinPages).sum();
        return ok == 0 ? null : round3((double) thin / ok);
    }

    /** 平均正文长度: 只统计真正抓到正文的调用(contentChars=0 是没抓, 计入会拉低失真) */
    private static Long avgContentChars(List<WebStep> steps) {
        long sum = 0;
        int n = 0;
        for (WebStep s : steps) {
            if (s.contentChars() > 0) {
                sum += s.contentChars();
                n++;
            }
        }
        return n == 0 ? null : sum / n;
    }

    private static long p95CostMs(List<WebStep> steps) {
        if (steps.isEmpty()) {
            return 0;
        }
        List<Long> costs = new ArrayList<>(steps.stream().map(WebStep::costMs).toList());
        costs.sort(Comparator.naturalOrder());
        int idx = (int) Math.ceil(costs.size() * 0.95) - 1;
        return costs.get(Math.max(0, Math.min(idx, costs.size() - 1)));
    }

    private static List<WebHealthDtos.DailyPoint> buildTrend(List<WebStep> steps) {
        Map<LocalDate, List<WebStep>> byDay = new TreeMap<>();
        for (WebStep s : steps) {
            byDay.computeIfAbsent(s.createdAt().toLocalDate(), k -> new ArrayList<>()).add(s);
        }

        List<WebHealthDtos.DailyPoint> points = new ArrayList<>(byDay.size());
        for (Map.Entry<LocalDate, List<WebStep>> e : byDay.entrySet()) {
            List<WebStep> daySteps = e.getValue();
            WebHealthDtos.DailyPoint p = new WebHealthDtos.DailyPoint();
            p.setDate(e.getKey().format(DateTimeFormatter.ISO_LOCAL_DATE));
            p.setWebCalls(daySteps.size());
            p.setEmptyCalls(daySteps.stream().filter(s -> s.results() == 0).count());
            p.setEmptyRate(emptyRate(daySteps));
            p.setAvgKeepRatio(avgKeepRatio(daySteps));
            p.setExtractSuccessRate(extractSuccessRate(daySteps));
            points.add(p);
        }
        return points;
    }

    /**
     * 空结果成因分布 —— 整份视图里最有用的一节。
     * <p>两种成因的处置方向完全相反: {@code no_results} 要查上游引擎, {@code all_filtered}
     * 要查自己的清洗规则。它们对调用方的表现一模一样, 只能靠这个分布区分。
     */
    private static List<WebHealthDtos.ReasonBucket> buildByReason(List<WebStep> steps) {
        Map<String, List<WebStep>> byReason = new LinkedHashMap<>();
        for (WebStep s : steps) {
            if (s.emptyReason() != null && !s.emptyReason().isBlank()) {
                byReason.computeIfAbsent(s.emptyReason(), k -> new ArrayList<>()).add(s);
            }
        }
        List<WebHealthDtos.ReasonBucket> buckets = new ArrayList<>(byReason.size());
        for (Map.Entry<String, List<WebStep>> e : byReason.entrySet()) {
            List<WebStep> list = e.getValue();
            WebHealthDtos.ReasonBucket b = new WebHealthDtos.ReasonBucket();
            b.setReason(e.getKey());
            b.setLabel(list.get(0).emptyReasonLabel());
            b.setCalls(list.size());
            b.setShare(steps.isEmpty() ? 0 : round3((double) list.size() / steps.size()));
            java.util.OptionalDouble avgRaw = list.stream()
                    .filter(s -> s.rawCount() >= 0)
                    .mapToInt(WebStep::rawCount)
                    .average();
            b.setAvgRawCount(avgRaw.isPresent() ? Math.round(avgRaw.getAsDouble()) : null);
            buckets.add(b);
        }
        buckets.sort(Comparator.comparingLong(WebHealthDtos.ReasonBucket::getCalls).reversed());
        return buckets;
    }

    /** 清洗存活率异常: 上游给了 >=10 条却几乎全被丢弃, 最可能是清洗规则误伤 */
    private static List<WebHealthDtos.WeakSample> buildWeakSamples(List<WebStep> steps) {
        return steps.stream()
                .filter(s -> s.rawCount() >= WEAK_RAW_MIN && s.results() <= WEAK_KEEP_MAX)
                .sorted(Comparator.comparing(WebStep::createdAt).reversed())
                .limit(SAMPLE_LIMIT)
                .map(s -> {
                    WebHealthDtos.WeakSample w = new WebHealthDtos.WeakSample();
                    w.setLogId(s.logId());
                    w.setCreatedAt(s.createdAt());
                    w.setUserId(s.userId());
                    w.setQuery(s.query());
                    w.setRawCount(s.rawCount());
                    w.setResults(s.results());
                    w.setKeepRatio(round3((double) s.results() / s.rawCount()));
                    return w;
                })
                .toList();
    }

    /** 薄页样本: 抓取成功篇数 >0 且**全部**是薄页 —— 典型的登录页/验证码页/空壳页 */
    private static List<WebHealthDtos.ThinSample> buildThinSamples(List<WebStep> steps) {
        return steps.stream()
                .filter(s -> s.extractOk() > 0 && s.thinPages() >= s.extractOk())
                .sorted(Comparator.comparing(WebStep::createdAt).reversed())
                .limit(SAMPLE_LIMIT)
                .map(s -> {
                    WebHealthDtos.ThinSample t = new WebHealthDtos.ThinSample();
                    t.setLogId(s.logId());
                    t.setCreatedAt(s.createdAt());
                    t.setQuery(s.query());
                    t.setExtractOk(s.extractOk());
                    t.setExtractFailed(s.extractFailed());
                    t.setContentChars(s.contentChars());
                    t.setThinPages(s.thinPages());
                    return t;
                })
                .toList();
    }

    private static WebHealthDtos.PrevPeriod buildPrev(List<WebStep> steps) {
        WebHealthDtos.PrevPeriod prev = new WebHealthDtos.PrevPeriod();
        prev.setWebCalls(steps.size());
        prev.setEmptyRate(emptyRate(steps));
        prev.setAvgKeepRatio(avgKeepRatio(steps));
        prev.setExtractSuccessRate(extractSuccessRate(steps));
        return prev;
    }

    // ==================================================================
    //  helper
    // ==================================================================

    private static double round3(double v) {
        return Math.round(v * 1000d) / 1000d;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static int intOf(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static long longOf(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    /** 一条联网步骤的扁平视图(便于聚合时反复使用) */
    private record WebStep(String logId,
                           LocalDateTime createdAt,
                           String userId,
                           String tool,
                           String query,
                           int rawCount,
                           int results,
                           int extractOk,
                           int extractFailed,
                           int contentChars,
                           int thinPages,
                           String emptyReason,
                           String emptyReasonLabel,
                           long costMs) {
    }

    /** 一个时间窗口的取数结果 */
    private record Window(List<WebStep> steps, int scannedLogs, boolean truncated) {
    }
}
