package com.dcdr.agent.controller;

import com.dcdr.agent.agent.governance.CostReportService;
import com.dcdr.agent.agent.governance.CostTracker;
import com.dcdr.agent.agent.governance.WorkflowMonitorService;
import com.dcdr.agent.auth.SecurityContext;
import com.dcdr.agent.common.Result;
import com.dcdr.agent.config.LlmMetrics;
import com.dcdr.agent.dto.MonitorDtos;
import com.dcdr.agent.model.circuitbreaker.LlmCircuitBreaker;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * V3.0-beta 治理与可观测性接口 (F-34 执行监控 / 成本统计, 文档 §8)。
 * <p>
 * 端点清单:
 * <ul>
 *   <li>GET /api/monitor/workflows   工作流执行监控总览 (次数/成功率/平均耗时/按工作流聚合)</li>
 *   <li>GET /api/monitor/cost         LLM token 成本统计 (按天/按模型聚合)</li>
 *   <li>GET /api/monitor/quota        当前用户配额使用情况 (F-39)</li>
 *   <li>GET /api/monitor/llm          LLM 服务调用/失败/耗时 + 熔断快照 (F-40.2/F-40.4, 内置面板, 免 Prometheus)</li>
 * </ul>
 * <p>
 * 普通用户仅可见自己的数据; 管理员传 {@code scope=global} 可查看全局统计。
 * LLM 调用统计与熔断快照为全局基础设施状态, 登录即可见(仅统计口径, 无敏感业务数据)。
 */
@Slf4j
@RestController
@RequestMapping("/api/monitor")
@RequiredArgsConstructor
@Tag(name = "监控与成本", description = "V3.0-beta 执行监控 / 成本统计 / 配额查询 / LLM 服务健康 (F-34 / F-39 / F-40)")
public class MonitorController {

    private final WorkflowMonitorService monitorService;
    private final CostTracker costTracker;
    /** V3.1 F-44.2: 成本分析报表 (按 用户/模型/时间 维度聚合, 支持 CSV 导出) */
    private final CostReportService costReportService;
    private final LlmMetrics llmMetrics;
    private final LlmCircuitBreaker circuitBreaker;

    @Operation(summary = "工作流执行监控总览",
            description = "时间窗口内的执行次数/成功率/平均耗时/总 token, 以及按工作流聚合的统计。"
                    + "管理员可传 scope=global 查看全局。")
    @GetMapping("/workflows")
    public Result<MonitorDtos.WorkflowMonitorOverview> workflowOverview(
            @Parameter(description = "统计窗口天数") @RequestParam(defaultValue = "30") int days,
            @Parameter(description = "global=全局(仅管理员), 默认仅自己") @RequestParam(required = false) String scope,
            ServerWebExchange exchange) {
        String userId = SecurityContext.getUserId(exchange);
        boolean global = "global".equalsIgnoreCase(scope) && SecurityContext.isAdmin(exchange);
        return Result.success(monitorService.overview(global ? null : userId, days));
    }

    @Operation(summary = "LLM token 成本统计",
            description = "时间窗口内按天/按模型聚合的 token 用量与调用次数。"
                    + "管理员可传 scope=global 查看全局。")
    @GetMapping("/cost")
    public Result<MonitorDtos.CostOverview> costStats(
            @Parameter(description = "统计窗口天数") @RequestParam(defaultValue = "30") int days,
            @Parameter(description = "global=全局(仅管理员), 默认仅自己") @RequestParam(required = false) String scope,
            ServerWebExchange exchange) {
        String userId = SecurityContext.getUserId(exchange);
        boolean global = "global".equalsIgnoreCase(scope) && SecurityContext.isAdmin(exchange);
        return Result.success(costTracker.costStats(global ? null : userId, days));
    }

    @Operation(summary = "我的配额使用情况",
            description = "F-39: 当前用户日/月 token 已用量与限额 (配额关闭时仅展示已用量)。")
    @GetMapping("/quota")
    public Result<MonitorDtos.QuotaUsage> quota(ServerWebExchange exchange) {
        return Result.success(costTracker.getQuotaUsage(SecurityContext.getUserId(exchange)));
    }

    @Operation(summary = "LLM 服务调用与熔断概览",
            description = "F-40.2/F-40.4: 各 provider 的调用数/失败数/失败率/平均耗时, 以及熔断器状态快照。"
                    + "数据来自进程内 MeterRegistry 与熔断器, 不依赖外部监控组件。")
    @GetMapping("/llm")
    public Result<MonitorDtos.LlmMonitorOverview> llmOverview() {
        List<Map<String, Object>> providers = llmMetrics.snapshot();
        MonitorDtos.LlmMonitorOverview overview = new MonitorDtos.LlmMonitorOverview();
        overview.setProviders(providers);
        overview.setCircuitBreaker(new java.util.ArrayList<>(circuitBreaker.snapshot()));
        long calls = providers.stream().mapToLong(m -> num(m.get("calls"))).sum();
        long errors = providers.stream().mapToLong(m -> num(m.get("errors"))).sum();
        overview.setTotalCalls(calls);
        overview.setTotalErrors(errors);
        long tot = calls + errors;
        overview.setOverallErrorRate(tot == 0 ? 0d : Math.round(errors * 1000d / tot) / 1000d);
        return Result.success(overview);
    }

    // ===== V3.1 F-44.2 成本分析报表 =====

    @Operation(summary = "成本分析报表",
            description = "按 用户 / 模型 / 时间 维度聚合 token 与金额: 日/周/月消耗趋势、Top 用户、模型占比。"
                    + "普通用户仅统计自己; 管理员传 scope=global 可看全局并返回 Top 用户。"
                    + "未配置模型定价(agent.v3.cost.pricing)时 costDataAvailable=false, 金额列应降级展示。")
    @GetMapping("/cost/report")
    public Result<MonitorDtos.CostReportView> costReport(
            @Parameter(description = "起始时间 (yyyy-MM-ddTHH:mm:ss, 含); 缺省近 30 天")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @Parameter(description = "结束时间 (不含); 缺省当前")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @Parameter(description = "趋势粒度: day / week / month") @RequestParam(defaultValue = "day") String granularity,
            @Parameter(description = "Top 用户条数 (仅全局视图生效)") @RequestParam(defaultValue = "10") int topN,
            @Parameter(description = "global=全局(仅管理员), 默认仅自己") @RequestParam(required = false) String scope,
            ServerWebExchange exchange) {
        String userId = SecurityContext.getUserId(exchange);
        boolean global = "global".equalsIgnoreCase(scope) && SecurityContext.isAdmin(exchange);
        return Result.success(costReportService.report(global ? null : userId, from, to, granularity, topN));
    }

    @Operation(summary = "成本报表导出 (CSV)",
            description = "导出指定维度的 CSV。文件为 UTF-8 带 BOM(Excel 打开中文不乱码), "
                    + "并对以 = + - @ 开头的单元格做公式注入转义。维度: trend / model / user / source。")
    @GetMapping("/cost/export")
    public ResponseEntity<byte[]> exportCost(
            @Parameter(description = "起始时间 (含); 缺省近 30 天")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @Parameter(description = "结束时间 (不含); 缺省当前")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @Parameter(description = "趋势粒度: day / week / month") @RequestParam(defaultValue = "day") String granularity,
            @Parameter(description = "导出维度: trend / model / user / source") @RequestParam(defaultValue = "trend") String dimension,
            @Parameter(description = "Top 用户条数 (仅 user 维度生效)") @RequestParam(defaultValue = "10") int topN,
            @Parameter(description = "global=全局(仅管理员), 默认仅自己") @RequestParam(required = false) String scope,
            ServerWebExchange exchange) {
        String userId = SecurityContext.getUserId(exchange);
        boolean global = "global".equalsIgnoreCase(scope) && SecurityContext.isAdmin(exchange);
        MonitorDtos.CostReportView report =
                costReportService.report(global ? null : userId, from, to, granularity, topN);
        byte[] body = costReportService.exportCsv(report, dimension);
        String dim = dimension == null || dimension.isBlank() ? "trend" : dimension.trim().toLowerCase();
        String filename = "cost-" + dim + "-" + LocalDate.now() + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    private long num(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }
}
