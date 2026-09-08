package com.dcdr.agent.controller;

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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

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

    private long num(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }
}
