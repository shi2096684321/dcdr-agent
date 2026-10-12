package com.dcdr.agent.agent.governance;

import com.dcdr.agent.dto.RetrievalHealthDtos;
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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 检索健康度聚合 (RAG 可观测性)。
 *
 * <p><b>它回答的问题</b>: "线上文档持续增长后, 检索效果有没有漂移"。
 * 这与固定评测集是两回事 ——
 * {@code RagRetrievalEvalIT}(语料内联、冻结) 与 {@code RagRetrievalEvalLiveIT}(人工标注)
 * 都在回答"<b>代码改动</b>是否让效果变差"; 本服务看的是<b>真实流量</b>, 回答的是
 * "随着文档变多、用户问法变化, 效果是否在漂移"。
 *
 * <p><b>数据来源</b>: {@code llm_call_logs.steps_json} 里 {@code kind=rag} 的步骤,
 * 由 {@code LlmCallTrace.recordRag} 写入 (query / kbCount / hits / costMs / topScore / docIds)。
 *
 * <p><b>为什么在 Java 侧解析而不写 nativeQuery</b>: {@code steps_json} 是 JSON 文本,
 * 但 PG 的 {@code jsonb_array_elements} 属方言特性 —— 用了会绑死数据库并在 H2 下报错
 * (项目已有同类踩坑)。故只取行、在 Java 侧用 Jackson 展开聚合。
 * 代价是内存扫描, 因此有 {@link #SCAN_LIMIT} 上限并在响应里回报是否被截断。
 *
 * <p><b>只读</b>: 本服务不写入任何业务数据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalHealthService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 扫描上限: 该接口要把行读出来在内存里解析 JSON, 无上限时大数据量会拖垮接口。
     * 超限时按时间序取<b>最近</b>的部分, 并在响应里置 {@code truncated=true} ——
     * 让使用者知道指标只覆盖了部分日志, 而不是误以为覆盖完整。
     */
    private static final int SCAN_LIMIT = 5000;

    /** 零命中样本返回条数上限(样本仅用于人工排查, 不需要全量) */
    private static final int ZERO_HIT_SAMPLE_LIMIT = 50;

    /** 处理天数上限, 防止一次拉取过大的窗口 */
    private static final int MAX_DAYS = 180;

    private final LlmCallLogRepository llmCallLogRepository;

    public RetrievalHealthDtos.HealthOverview overview(String userId, int days) {
        int window = Math.min(Math.max(days, 1), MAX_DAYS);
        LocalDateTime to = LocalDateTime.now();
        LocalDateTime from = to.minusDays(window);

        Window current = collect(userId, from, to);
        // 上一等长周期, 仅用于给出"在变好还是变差"的方向判断
        Window previous = collect(userId, from.minusDays(window), from);

        return assemble(current, previous, from, to, window);
    }

    // ==================================================================
    //  取数: 行 → RAG 步骤明细
    // ==================================================================

    /**
     * 读取窗口内的日志并抽出所有 RAG 步骤。
     *
     * @return 步骤列表(时间升序) + 是否被扫描上限截断
     */
    private Window collect(String userId, LocalDateTime from, LocalDateTime to) {
        List<LlmCallLogEntity> logs = llmCallLogRepository.findForHealthAggregation(
                userId, from, PageRequest.of(0, SCAN_LIMIT + 1));

        boolean truncated = logs.size() > SCAN_LIMIT;
        if (truncated) {
            // 取最近的 SCAN_LIMIT 条: 查询按时间升序, 故从头截断
            logs = logs.subList(logs.size() - SCAN_LIMIT, logs.size());
        }

        List<RagStep> steps = new ArrayList<>();
        for (LlmCallLogEntity log : logs) {
            if (log.getCreatedAt() == null || log.getCreatedAt().isAfter(to)) {
                continue;
            }
            for (Map<String, Object> step : parseSteps(log.getStepsJson())) {
                if (!"rag".equals(step.get("kind"))) {
                    continue;
                }
                steps.add(new RagStep(
                        log.getId(),
                        log.getCreatedAt(),
                        log.getUserId(),
                        str(step.get("query")),
                        intOf(step.get("kbCount")),
                        intOf(step.get("hits")),
                        doubleOf(step.get("topScore")),
                        longOf(step.get("costMs"))));
            }
        }
        return new Window(steps, logs.size(), truncated);
    }

    /**
     * 解析步骤 JSON。
     *
     * <p>与 {@code LlmCallLogController.parseSteps} 同样基于 Jackson, 但目的不同:
     * 那里为了让传输体积可控而<b>剔除</b>重字段, 这里只为聚合读取少量数值字段,
     * 故不做剔除也不返回给前端(避免把用户问题以外的原文暴露出去)。
     */
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

    private RetrievalHealthDtos.HealthOverview assemble(Window current,
                                                        Window previous,
                                                        LocalDateTime from,
                                                        LocalDateTime to,
                                                        int window) {
        List<RagStep> steps = current.steps();

        RetrievalHealthDtos.HealthOverview vo = new RetrievalHealthDtos.HealthOverview();
        vo.setFrom(from);
        vo.setTo(to);
        vo.setDays(window);
        vo.setScannedLogs(current.scannedLogs());
        vo.setTruncated(current.truncated());
        vo.setScanLimit(SCAN_LIMIT);

        vo.setRagCalls(steps.size());
        vo.setAvgHits(avgHits(steps));
        vo.setZeroHitRate(zeroHitRate(steps));
        vo.setAvgTopScore(avgTopScore(steps));
        vo.setP95CostMs(p95CostMs(steps));

        vo.setTrend(buildTrend(steps));
        vo.setByKbCount(buildByKbCount(steps));
        vo.setZeroHits(buildZeroHitSamples(steps));
        vo.setPrev(previous.steps().isEmpty() ? null : buildPrev(previous.steps()));
        return vo;
    }

    private static double avgHits(List<RagStep> steps) {
        return round3(steps.stream().mapToInt(RagStep::hits).average().orElse(0));
    }

    private static double zeroHitRate(List<RagStep> steps) {
        if (steps.isEmpty()) {
            return 0;
        }
        long zero = steps.stream().filter(s -> s.hits() == 0).count();
        return round3((double) zero / steps.size());
    }

    /** 平均最高相似度: 只对"有命中"的检索取均值 —— 零命中时 topScore 为 0, 计入会把整体拉低失真 */
    private static Double avgTopScore(List<RagStep> steps) {
        List<Double> scores = new ArrayList<>();
        for (RagStep s : steps) {
            if (s.hits() > 0 && s.topScore() > 0) {
                scores.add(s.topScore());
            }
        }
        if (scores.isEmpty()) {
            return null;
        }
        return round3(scores.stream().mapToDouble(Double::doubleValue).average().orElse(0));
    }

    private static long p95CostMs(List<RagStep> steps) {
        if (steps.isEmpty()) {
            return 0;
        }
        List<Long> costs = new ArrayList<>(steps.stream().map(RagStep::costMs).toList());
        costs.sort(Comparator.naturalOrder());
        int idx = (int) Math.ceil(costs.size() * 0.95) - 1;
        return costs.get(Math.max(0, Math.min(idx, costs.size() - 1)));
    }

    private static List<RetrievalHealthDtos.DailyPoint> buildTrend(List<RagStep> steps) {
        Map<LocalDate, List<RagStep>> byDay = new TreeMap<>();
        for (RagStep s : steps) {
            byDay.computeIfAbsent(s.createdAt().toLocalDate(), k -> new ArrayList<>()).add(s);
        }

        List<RetrievalHealthDtos.DailyPoint> points = new ArrayList<>(byDay.size());
        for (Map.Entry<LocalDate, List<RagStep>> e : byDay.entrySet()) {
            List<RagStep> daySteps = e.getValue();
            RetrievalHealthDtos.DailyPoint p = new RetrievalHealthDtos.DailyPoint();
            p.setDate(e.getKey().format(DateTimeFormatter.ISO_LOCAL_DATE));
            p.setRagCalls(daySteps.size());
            p.setAvgHits(avgHits(daySteps));
            p.setZeroHitCalls(daySteps.stream().filter(s -> s.hits() == 0).count());
            p.setZeroHitRate(zeroHitRate(daySteps));
            p.setAvgTopScore(avgTopScore(daySteps));
            points.add(p);
        }
        return points;
    }

    private static List<RetrievalHealthDtos.KbBucket> buildByKbCount(List<RagStep> steps) {
        Map<Integer, List<RagStep>> byKb = new TreeMap<>();
        for (RagStep s : steps) {
            byKb.computeIfAbsent(s.kbCount(), k -> new ArrayList<>()).add(s);
        }

        List<RetrievalHealthDtos.KbBucket> buckets = new ArrayList<>(byKb.size());
        for (Map.Entry<Integer, List<RagStep>> e : byKb.entrySet()) {
            RetrievalHealthDtos.KbBucket b = new RetrievalHealthDtos.KbBucket();
            b.setKbCount(e.getKey());
            b.setRagCalls(e.getValue().size());
            b.setZeroHitRate(zeroHitRate(e.getValue()));
            b.setAvgTopScore(avgTopScore(e.getValue()));
            buckets.add(b);
        }
        return buckets;
    }

    /** 零命中样本: 取最近 N 条, 供人工判断"应补文档"还是"该排查检索" */
    private static List<RetrievalHealthDtos.ZeroHitSample> buildZeroHitSamples(List<RagStep> steps) {
        return steps.stream()
                .filter(s -> s.hits() == 0)
                .sorted(Comparator.comparing(RagStep::createdAt).reversed())
                .limit(ZERO_HIT_SAMPLE_LIMIT)
                .map(s -> {
                    RetrievalHealthDtos.ZeroHitSample sample = new RetrievalHealthDtos.ZeroHitSample();
                    sample.setLogId(s.logId());
                    sample.setCreatedAt(s.createdAt());
                    sample.setUserId(s.userId());
                    sample.setQuestion(s.question());
                    sample.setKbCount(s.kbCount());
                    sample.setTopScore(s.topScore());
                    sample.setCostMs(s.costMs());
                    return sample;
                })
                .toList();
    }

    private static RetrievalHealthDtos.PrevPeriod buildPrev(List<RagStep> steps) {
        RetrievalHealthDtos.PrevPeriod prev = new RetrievalHealthDtos.PrevPeriod();
        prev.setRagCalls(steps.size());
        prev.setZeroHitRate(zeroHitRate(steps));
        prev.setAvgTopScore(avgTopScore(steps));
        return prev;
    }

    // ==================================================================
    //  helper
    // ==================================================================

    private static double round3(double v) {
        return Math.round(v * 1000d) / 1000d;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static int intOf(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static long longOf(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static double doubleOf(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0d;
    }

    /** 一条 RAG 步骤的扁平视图(便于聚合时反复使用) */
    private record RagStep(String logId,
                           LocalDateTime createdAt,
                           String userId,
                           String question,
                           int kbCount,
                           int hits,
                           double topScore,
                           long costMs) {
    }

    /** 一个时间窗口的取数结果 */
    private record Window(List<RagStep> steps, int scannedLogs, boolean truncated) {
    }
}
