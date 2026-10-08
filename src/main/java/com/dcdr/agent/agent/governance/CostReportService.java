package com.dcdr.agent.agent.governance;

import com.dcdr.agent.common.CsvWriter;
import com.dcdr.agent.dto.MonitorDtos;
import com.dcdr.agent.entity.UserEntity;
import com.dcdr.agent.repository.LlmUsageLogRepository;
import com.dcdr.agent.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * V3.1 F-44.2 成本分析报表 (F-34 收尾项)。
 * <p>
 * 数据源是 {@code llm_usage_logs}(F-39 落地时用的就是这张表, 规划文档里写的 {@code cost_records} 并不存在),
 * 按 <b>用户 / 模型 / 时间</b> 三个维度聚合 token 与金额。
 * <p>
 * 两个设计要点:
 * <ul>
 *   <li><b>周/月趋势在 Java 侧 rollup</b>: 只向数据库要「天粒度」结果再由内存按周/月合桶。
 *       JPQL 没有 {@code date_trunc}, 改用 nativeQuery 会绑死 PostgreSQL 并在 H2(开发/测试)下报错
 *       —— 项目已有同类踩坑记录(见 {@code LlmUsageLogRepository} 注释)。天粒度上限 366 行,
 *       内存 rollup 的成本可以忽略。</li>
 *   <li><b>金额与 token 并列, 但语义分开</b>: 未配置模型定价时 {@code cost_amount} 为 null,
 *       报表返回 {@code costDataAvailable=false}, 由前端把金额列降级为 "—", 不把
 *       "没有定价" 显示成 "零成本"。</li>
 * </ul>
 * 报表的写入口径见 {@link CostTracker}; 本类只读。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CostReportService {

    /** 单次查询区间上限(天): 防止一次导出把全库拉进内存 */
    private static final int MAX_RANGE_DAYS = 366;
    /** 未传区间时的默认窗口 */
    private static final int DEFAULT_RANGE_DAYS = 30;
    private static final int DEFAULT_TOP_N = 10;
    private static final int MAX_TOP_N = 50;

    private static final String UNKNOWN = "(未知)";
    private static final String ANONYMOUS = "(匿名)";
    private static final String DELETED_USER = "(已删除用户)";

    private final LlmUsageLogRepository usageRepository;
    private final UserRepository userRepository;

    /**
     * 成本报表: 按 用户 / 模型 / 时间 维度聚合。
     *
     * @param userId      仅统计该用户; {@code null} 表示全局(调用方必须已校验管理员权限)
     * @param from        起始时间(含); 为空取近 {@value #DEFAULT_RANGE_DAYS} 天
     * @param to          结束时间(不含); 为空取当前
     * @param granularity 趋势粒度 day / week / month
     * @param topN        Top 用户条数 (上限 {@value #MAX_TOP_N})
     */
    public MonitorDtos.CostReportView report(String userId, LocalDateTime from, LocalDateTime to,
                                             String granularity, Integer topN) {
        LocalDateTime[] range = normalizeRange(from, to);
        String gran = normalizeGranularity(granularity);

        MonitorDtos.CostReportView view = new MonitorDtos.CostReportView();
        view.setGranularity(gran);
        view.setFrom(range[0].toLocalDate().toString());
        view.setTo(range[1].minusNanos(1).toLocalDate().toString());

        List<MonitorDtos.TrendPoint> trend =
                rollup(usageRepository.aggregateDailyBetween(userId, range[0], range[1]), gran);
        view.setTrend(trend);
        fillTotals(view, trend);
        view.setByModel(models(userId, range));
        view.setBySource(sources(userId, range));
        if (userId == null) {
            // 用户维度天然是跨用户的: 仅在管理员全局视图下返回, 否则即为越权信息泄露
            view.setByUser(topUsers(range, topN));
        }
        view.setCostDataAvailable(usageRepository.countWithCostBetween(userId, range[0], range[1]) > 0);
        return view;
    }

    /**
     * 导出 CSV。维度与报表表格一一对应: trend / model / user / source。
     * <p>BOM 与公式注入防护见 {@link CsvWriter} —— Excel 打开中文不乱码、用户名以 {@code =} 开头不被当公式。
     */
    public byte[] exportCsv(MonitorDtos.CostReportView view, String dimension) {
        String dim = dimension == null || dimension.isBlank()
                ? "trend" : dimension.trim().toLowerCase(Locale.ROOT);
        boolean withCost = view.isCostDataAvailable();
        String costHeader = withCost ? "金额(元)" : "金额(未配置定价)";
        CsvWriter csv = new CsvWriter();
        switch (dim) {
            case "model" -> {
                csv.header("模型", "调用次数", "输入Token", "输出Token", "合计Token", costHeader, "Token占比");
                for (MonitorDtos.ModelUsageCost m : nvl(view.getByModel())) {
                    csv.row(m.getModel(), m.getCalls(), m.getInputTokens(), m.getOutputTokens(),
                            m.getTotalTokens(), money(m.getCost(), withCost), pct(m.getTokenRatio()));
                }
            }
            case "user" -> {
                csv.header("用户", "用户ID", "调用次数", "合计Token", costHeader);
                for (MonitorDtos.UserUsageCost u : nvl(view.getByUser())) {
                    csv.row(u.getUsername(), u.getUserId(), u.getCalls(), u.getTotalTokens(),
                            money(u.getCost(), withCost));
                }
            }
            case "source" -> {
                csv.header("来源", "调用次数", "合计Token", costHeader);
                for (MonitorDtos.SourceUsage s : nvl(view.getBySource())) {
                    csv.row(s.getSource(), s.getCalls(), s.getTotalTokens(), money(s.getCost(), withCost));
                }
            }
            default -> {
                csv.header("时间", "调用次数", "输入Token", "输出Token", "合计Token", costHeader);
                for (MonitorDtos.TrendPoint p : nvl(view.getTrend())) {
                    csv.row(p.getPeriod(), p.getCalls(), p.getInputTokens(), p.getOutputTokens(),
                            p.getTotalTokens(), money(p.getCost(), withCost));
                }
            }
        }
        return csv.toBytes();
    }

    // ===== 聚合辅助 =====

    /** 按粒度把「天粒度」结果合桶 (TreeMap 保证时间有序) */
    private List<MonitorDtos.TrendPoint> rollup(List<Object[]> dailyRows, String granularity) {
        Map<String, MonitorDtos.TrendPoint> buckets = new TreeMap<>();
        for (Object[] row : dailyRows) {
            String period = periodKey(String.valueOf(row[0]), granularity);
            MonitorDtos.TrendPoint point = buckets.computeIfAbsent(period, CostReportService::newPoint);
            point.setCalls(point.getCalls() + asLong(row[1]));
            point.setInputTokens(point.getInputTokens() + asLong(row[2]));
            point.setOutputTokens(point.getOutputTokens() + asLong(row[3]));
            point.setTotalTokens(point.getTotalTokens() + asLong(row[4]));
            point.setCost(nz(point.getCost()).add(asDecimal(row[5])));
        }
        return new ArrayList<>(buckets.values());
    }

    private void fillTotals(MonitorDtos.CostReportView view, List<MonitorDtos.TrendPoint> trend) {
        long input = 0, output = 0, total = 0, calls = 0;
        BigDecimal cost = BigDecimal.ZERO;
        for (MonitorDtos.TrendPoint p : trend) {
            input += p.getInputTokens();
            output += p.getOutputTokens();
            total += p.getTotalTokens();
            calls += p.getCalls();
            cost = cost.add(nz(p.getCost()));
        }
        view.setInputTokens(input);
        view.setOutputTokens(output);
        view.setTotalTokens(total);
        view.setCallCount(calls);
        view.setTotalCost(cost);
    }

    private List<MonitorDtos.ModelUsageCost> models(String userId, LocalDateTime[] range) {
        List<Object[]> rows = usageRepository.aggregateModelsBetween(userId, range[0], range[1]);
        List<MonitorDtos.ModelUsageCost> list = new ArrayList<>(rows.size());
        long totalTokens = 0;
        BigDecimal totalCost = BigDecimal.ZERO;
        for (Object[] row : rows) {
            MonitorDtos.ModelUsageCost m = new MonitorDtos.ModelUsageCost();
            m.setModel(row[0] == null ? UNKNOWN : String.valueOf(row[0]));
            m.setCalls(asLong(row[1]));
            m.setInputTokens(asLong(row[2]));
            m.setOutputTokens(asLong(row[3]));
            m.setTotalTokens(asLong(row[4]));
            m.setCost(asDecimal(row[5]));
            totalTokens += m.getTotalTokens();
            totalCost = totalCost.add(nz(m.getCost()));
            list.add(m);
        }
        for (MonitorDtos.ModelUsageCost m : list) {
            m.setTokenRatio(ratio(m.getTotalTokens(), totalTokens));
            m.setCostRatio(costRatio(m.getCost(), totalCost));
        }
        return list;
    }

    private List<MonitorDtos.SourceUsage> sources(String userId, LocalDateTime[] range) {
        List<Object[]> rows = usageRepository.aggregateSourcesBetween(userId, range[0], range[1]);
        List<MonitorDtos.SourceUsage> list = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            MonitorDtos.SourceUsage s = new MonitorDtos.SourceUsage();
            s.setSource(row[0] == null ? UNKNOWN : String.valueOf(row[0]));
            s.setCalls(asLong(row[1]));
            s.setTotalTokens(asLong(row[2]));
            s.setCost(asDecimal(row[3]));
            list.add(s);
        }
        return list;
    }

    /** Top 用户 (按 token 降序, 已在 SQL 中排序, 此处只截断 + 回填用户名) */
    private List<MonitorDtos.UserUsageCost> topUsers(LocalDateTime[] range, Integer topN) {
        int limit = (topN == null || topN <= 0) ? DEFAULT_TOP_N : Math.min(topN, MAX_TOP_N);
        List<Object[]> rows = usageRepository.aggregateTopUsersBetween(range[0], range[1]);
        List<MonitorDtos.UserUsageCost> list = new ArrayList<>();
        for (Object[] row : rows) {
            if (list.size() >= limit) break;
            MonitorDtos.UserUsageCost u = new MonitorDtos.UserUsageCost();
            u.setUserId(row[0] == null ? null : String.valueOf(row[0]));
            u.setUsername(row[0] == null ? ANONYMOUS : null);
            u.setCalls(asLong(row[1]));
            u.setTotalTokens(asLong(row[2]));
            u.setCost(asDecimal(row[3]));
            list.add(u);
        }
        fillUsernames(list);
        return list;
    }

    /** 批量回填用户名 (一次 findAllById, 避免逐行查库) */
    private void fillUsernames(List<MonitorDtos.UserUsageCost> users) {
        List<String> ids = users.stream()
                .map(MonitorDtos.UserUsageCost::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (ids.isEmpty()) return;
        Map<String, String> nameById = new LinkedHashMap<>();
        for (UserEntity user : userRepository.findAllById(ids)) {
            nameById.put(user.getId(), user.getUsername());
        }
        for (MonitorDtos.UserUsageCost u : users) {
            if (u.getUserId() == null) continue;
            u.setUsername(nameById.getOrDefault(u.getUserId(), DELETED_USER));
        }
    }

    // ===== 参数归一化 =====

    /** 归一化区间: 缺省近 30 天、保证 from < to、上限 366 天 */
    private static LocalDateTime[] normalizeRange(LocalDateTime from, LocalDateTime to) {
        LocalDateTime end = to != null ? to : LocalDateTime.now();
        LocalDateTime start = from != null ? from : end.minusDays(DEFAULT_RANGE_DAYS);
        if (start.isAfter(end)) {
            LocalDateTime tmp = start;
            start = end;
            end = tmp;
        }
        if (start.plusDays(MAX_RANGE_DAYS).isBefore(end)) {
            log.warn("成本报表区间超过 {} 天上限, 已自动收窄: {} ~ {}", MAX_RANGE_DAYS, start, end);
            start = end.minusDays(MAX_RANGE_DAYS);
        }
        return new LocalDateTime[]{start, end};
    }

    private static String normalizeGranularity(String granularity) {
        String g = granularity == null ? "" : granularity.trim().toLowerCase(Locale.ROOT);
        return switch (g) {
            case "week", "month" -> g;
            default -> "day";
        };
    }

    /** yyyy-MM-dd → 粒度标识: 2026-09-28 / 2026-W39 / 2026-09 */
    private static String periodKey(String day, String granularity) {
        if ("day".equals(granularity) || day == null || day.length() < 10) return day;
        LocalDate date;
        try {
            date = LocalDate.parse(day);
        } catch (Exception e) {
            return day;
        }
        if ("week".equals(granularity)) {
            return date.get(IsoFields.WEEK_BASED_YEAR) + "-W"
                    + String.format(Locale.ROOT, "%02d", date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
        }
        return day.substring(0, 7);
    }

    private static MonitorDtos.TrendPoint newPoint(String period) {
        MonitorDtos.TrendPoint point = new MonitorDtos.TrendPoint();
        point.setPeriod(period);
        point.setCost(BigDecimal.ZERO);
        return point;
    }

    // ===== 数值/文本辅助 =====

    private static long asLong(Object v) {
        return v == null ? 0L : ((Number) v).longValue();
    }

    private static BigDecimal asDecimal(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal bd) return bd;
        return new BigDecimal(String.valueOf(v));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static double ratio(long part, long total) {
        return total <= 0 ? 0d : Math.round(part * 10000d / total) / 10000d;
    }

    private static Double costRatio(BigDecimal part, BigDecimal total) {
        if (total == null || total.signum() == 0) return null;
        return nz(part).divide(total, 4, RoundingMode.HALF_UP).doubleValue();
    }

    private static String money(BigDecimal cost, boolean available) {
        return available ? nz(cost).setScale(4, RoundingMode.HALF_UP).toPlainString() : "";
    }

    private static String pct(double ratio) {
        return String.format(Locale.ROOT, "%.2f%%", ratio * 100);
    }

    private static <T> List<T> nvl(List<T> list) {
        return list == null ? List.of() : list;
    }
}
