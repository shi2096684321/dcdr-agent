package com.dcdr.agent.eval;

import com.dcdr.agent.tool.websearch.SearchOutcome;
import com.dcdr.agent.tool.websearch.WebSearchProvider;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * 联网搜索排序评测的执行与归档服务。
 *
 * <h2>为什么它在 main 而不是 test</h2>
 * 评测最初写成 {@code WebSearchEvalIT}（JUnit + 命令行环境变量），结果是"只有写代码的人知道怎么跑"。
 * 但排完序好不好、换源值不值，是<b>产品问题</b>不是工程问题 —— 它需要有界面入口。
 * 逻辑放 main 之后, 测试类退化成它的一个调用方, 两处共用同一份实现, 不会漂移。
 *
 * <h2>为什么不用 WebProviderFactory</h2>
 * 那个工厂按配置<b>选一个</b>源, 因为线上同时只用得起一个。评测要的恰恰是<b>同时跑两个再对比</b>,
 * 目标不同, 所以这里直接注入全部 {@link WebSearchProvider} 实现。
 *
 * <h2>并发与费用</h2>
 * 全程真实调用外部接口(deepseek 按次计费), 所以<b>同一时刻只允许跑一个评测</b>,
 * 重复触发直接返回"正在运行中"。跑 20 条 × 2 源约 20 秒、1 毛钱。
 */
@Slf4j
@Service
public class WebSearchEvalService {

    /** 用例文件(随代码走, 不随部署变) */
    private static final Path CASE_FILE = Path.of("docs", "eval", "web-search-eval.json");

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm");

    private final ObjectMapper mapper;
    /** 报告归档目录。可配是为了两件事: 部署时路径可能不同; 测试要写临时目录而不是往仓库里塞文件 */
    private final Path archiveDir;
    private final Map<String, WebSearchProvider> providers = new LinkedHashMap<>();

    /** 单线程: 评测是重活且花钱, 排队串行比并发更合适 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "web-search-eval");
        t.setDaemon(true);
        return t;
    });

    /** 当前/最近一次运行状态; 单例共享, 用 AtomicReference 保证可见性 */
    private final AtomicReference<RunState> state = new AtomicReference<>(RunState.idle());

    public WebSearchEvalService(List<WebSearchProvider> searchProviders, ObjectMapper mapper,
                                @org.springframework.beans.factory.annotation.Value(
                                        "${agent.eval.web-search.reports-dir:docs/eval/reports}") String archiveDir) {
        this.mapper = mapper;
        this.archiveDir = Path.of(archiveDir);
        searchProviders.forEach(p -> this.providers.put(p.id(), p));
        log.info("联网评测服务就绪: 可用源={}", this.providers.keySet());
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    // ===================== 查询 =====================

    public RunState currentState() {
        return state.get();
    }

    /** 可用源与其是否就绪(如 deepseek 缺 Key 时 available=false) */
    public Map<String, Boolean> availableProviders() {
        Map<String, Boolean> out = new LinkedHashMap<>();
        providers.forEach((id, p) -> out.put(id, p.available()));
        return out;
    }

    /** 历史报告(按时间倒序), 只读每个 json 的头部字段, 不解析全文 */
    public List<ReportSummary> listReports() {
        if (!Files.isDirectory(archiveDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(archiveDir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                    .map(this::readSummary)
                    .filter(java.util.Objects::nonNull)
                    .toList();
        } catch (IOException e) {
            log.warn("读取评测报告目录失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 单份报告的完整内容(前端渲染表格与未命中明细用) */
    public EvalReport readReport(String fileName) throws IOException {
        // 防路径穿越: 只允许读归档目录下的 .json, 不接受任何分隔符
        if (fileName == null || fileName.contains("/") || fileName.contains("\\") || !fileName.endsWith(".json")) {
            throw new IllegalArgumentException("非法的报告文件名: " + fileName);
        }
        Path file = archiveDir.resolve(fileName).normalize();
        if (!Files.isRegularFile(file)) {
            throw new IOException("报告不存在: " + fileName);
        }
        return mapper.readValue(Files.readString(file, StandardCharsets.UTF_8), EvalReport.class);
    }

    private ReportSummary readSummary(Path file) {
        try {
            EvalReport r = mapper.readValue(Files.readString(file, StandardCharsets.UTF_8), EvalReport.class);
            Map<String, MetricsLite> lite = new LinkedHashMap<>();
            r.getProviders().forEach((id, m) ->
                    lite.put(id, new MetricsLite(m.getHitRate(), m.getMrr(), m.getAvgRank())));
            return new ReportSummary(file.getFileName().toString(), r.getTimestamp(),
                    r.getCases(), r.getTopK(), lite);
        } catch (Exception e) {
            log.warn("跳过损坏的评测报告 {}: {}", file.getFileName(), e.getMessage());
            return null;
        }
    }

    // ===================== 执行 =====================

    /**
     * 提交一次评测。
     *
     * @param providerId 单个源 id, 或 {@code both} 表示全部可用源
     * @return 提交后的状态; 若已有任务在跑, 返回当前状态且不重复提交
     */
    public RunState submit(String providerId) {
        RunState cur = state.get();
        if (cur.running()) {
            return cur;
        }
        List<WebSearchProvider> targets = resolveTargets(providerId);
        if (targets.isEmpty()) {
            state.set(RunState.errorState("没有可用的搜索源(deepseek 未配置 API Key?)"));
            return state.get();
        }
        EvalSet set;
        try {
            set = mapper.readValue(Files.readString(CASE_FILE, StandardCharsets.UTF_8), EvalSet.class);
        } catch (Exception e) {
            state.set(RunState.errorState("评测集读取失败: " + e.getMessage()));
            return state.get();
        }
        RunState running = RunState.running(targets.stream().map(WebSearchProvider::id).toList(),
                set.getCases().size() * targets.size());
        state.set(running);
        executor.submit(() -> execute(set, targets));
        return running;
    }

    private void execute(EvalSet set, List<WebSearchProvider> targets) {
        try {
            Map<String, List<CaseResult>> byProvider = new LinkedHashMap<>();
            int done = 0;
            int total = set.getCases().size() * targets.size();
            for (WebSearchProvider provider : targets) {
                List<CaseResult> results = new ArrayList<>(set.getCases().size());
                for (WebCase c : set.getCases()) {
                    results.add(score(c, search(provider, c, set.getTopK())));
                    // 进度按"已经跑完的调用次数"报, 而不是按源: 界面上的进度条才有意义
                    state.set(state.get().progress(++done, total));
                }
                byProvider.put(provider.id(), results);
            }
            String stamp = LocalDateTime.now().format(STAMP);
            String suffix = String.join("+", byProvider.keySet());
            Files.createDirectories(archiveDir);
            EvalReport report = buildReport(set, byProvider, stamp);
            Path json = archiveDir.resolve(stamp + "-" + suffix + ".json");
            Files.writeString(json, mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(report), StandardCharsets.UTF_8);
            state.set(state.get().finished(json.getFileName().toString()));
            log.info("联网评测完成: {}", json);
        } catch (Exception e) {
            log.error("联网评测失败", e);
            state.set(state.get().failed(e.getMessage()));
        }
    }

    private List<WebSearchProvider> resolveTargets(String providerId) {
        if (providerId == null || providerId.isBlank() || "both".equalsIgnoreCase(providerId)) {
            return providers.values().stream().filter(WebSearchProvider::available).toList();
        }
        WebSearchProvider p = providers.get(providerId.toLowerCase());
        return p != null && p.available() ? List.of(p) : List.of();
    }

    /** 单条用例检索; 异常吞成空结果并记录, 不让一条 query 拖垮整轮 */
    private SearchHit search(WebSearchProvider provider, WebCase c, int topK) {
        try {
            SearchOutcome outcome = provider.search(c.getQuery(), null, topK);
            List<String> urls = new ArrayList<>();
            for (Map<String, Object> s : outcome.sources()) {
                Object u = s.get("url");
                if (u != null) {
                    urls.add(u.toString());
                }
            }
            return new SearchHit(urls, null);
        } catch (Exception e) {
            return new SearchHit(List.of(), e.getMessage());
        }
    }

    /** 打分: 首个命中位置(1-based) + 首条 URL(未命中时尤其需要看它返回了什么) */
    private CaseResult score(WebCase c, SearchHit hit) {
        int rank = 0;
        String matched = null;
        String first = hit.urls().isEmpty() ? null : hit.urls().get(0);
        for (int i = 0; i < hit.urls().size(); i++) {
            String u = hit.urls().get(i).toLowerCase();
            boolean ok = c.getExpectedUrlContains().stream()
                    .anyMatch(p -> u.contains(p.toLowerCase()));
            if (ok) {
                rank = i + 1;
                matched = hit.urls().get(i);
                break;
            }
        }
        return new CaseResult(c.getId(), c.getType(), c.getQuery(), matched, first,
                c.getExpectedUrlContains(), rank, hit.error());
    }

    private EvalReport buildReport(EvalSet set, Map<String, List<CaseResult>> byProvider, String stamp) {
        EvalReport report = new EvalReport();
        report.setTimestamp(stamp);
        report.setCases(set.getCases().size());
        report.setTopK(set.getTopK());
        Map<String, ReportMetrics> metrics = new LinkedHashMap<>();
        byProvider.forEach((id, rs) -> {
            ReportMetrics m = new ReportMetrics();
            m.setHitRate(round(rs.isEmpty() ? 0 : (double) rs.stream().filter(CaseResult::isHit).count() / rs.size()));
            m.setMrr(round(rs.isEmpty() ? 0 : rs.stream().mapToDouble(CaseResult::getReciprocalRank).average().orElse(0)));
            m.setAvgRank(round(rs.stream().filter(CaseResult::isHit).mapToInt(CaseResult::getRank).average().orElse(0)));
            m.setCases(rs);
            metrics.put(id, m);
        });
        report.setProviders(metrics);
        return report;
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    private record SearchHit(List<String> urls, String error) {
    }

    // ===================== 数据模型 =====================

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EvalSet {
        private String title;
        private int topK = 10;
        private List<WebCase> cases = new ArrayList<>();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WebCase {
        private String id;
        private String type;
        private String query;
        private List<String> expectedUrlContains = new ArrayList<>();
        private String note;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EvalReport {
        private String timestamp;
        private int cases;
        private int topK;
        private Map<String, ReportMetrics> providers = new LinkedHashMap<>();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReportMetrics {
        private double hitRate;
        private double mrr;
        private double avgRank;
        private List<CaseResult> cases = new ArrayList<>();
    }

    /** 单条结果; rank 为 1-based, 未命中为 0 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CaseResult {
        private String id;
        private String type;
        private String query;
        private String matchedUrl;
        private String firstUrl;
        /** 这条用例的金标(= 可接受的 URL 片段); 前端据此说明"本该命中什么" */
        private List<String> expected = new ArrayList<>();
        private int rank;
        private String error;

        public CaseResult() {
        }

        public CaseResult(String id, String type, String query, String matchedUrl, String firstUrl,
                          List<String> expected, int rank, String error) {
            this.id = id;
            this.type = type;
            this.query = query;
            this.matchedUrl = matchedUrl;
            this.firstUrl = firstUrl;
            this.expected = expected;
            this.rank = rank;
            this.error = error;
        }

        public boolean isHit() {
            return rank > 0;
        }

        public double getReciprocalRank() {
            return rank > 0 ? 1.0 / rank : 0.0;
        }
    }

    /** 列表用的轻量摘要: 不带逐条结果 —— 列表接口一次返回几十份报告时, 逐条明细会让响应膨胀几十倍 */
    public record ReportSummary(String file, String timestamp, int cases, int topK,
                                Map<String, MetricsLite> providers) {
    }

    public record MetricsLite(double hitRate, double mrr, double avgRank) {
    }

    /** 运行状态: 前端轮询它决定按钮是否可点、进度条画到哪 */
    public record RunState(String status, List<String> targets, int done, int total,
                           LocalDateTime startedAt, String reportFile, String error) {

        static RunState idle() {
            return new RunState("IDLE", List.of(), 0, 0, null, null, null);
        }

        static RunState running(List<String> targets, int total) {
            return new RunState("RUNNING", targets, 0, total, LocalDateTime.now(), null, null);
        }

        static RunState errorState(String error) {
            return new RunState("FAILED", List.of(), 0, 0, LocalDateTime.now(), null, error);
        }

        public boolean running() {
            return "RUNNING".equals(status);
        }

        RunState progress(int done, int total) {
            return new RunState(status, targets, done, total, startedAt, null, error);
        }

        RunState finished(String file) {
            return new RunState("DONE", targets, total, total, startedAt, file, null);
        }

        RunState failed(String error) {
            return new RunState("FAILED", targets, done, total, startedAt, reportFile, error);
        }
    }
}
