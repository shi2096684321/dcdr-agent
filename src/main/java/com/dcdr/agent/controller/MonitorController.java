package com.dcdr.agent.controller;

import com.dcdr.agent.agent.governance.CostTracker;
import com.dcdr.agent.agent.governance.WorkflowMonitorService;
import com.dcdr.agent.auth.SecurityContext;
import com.dcdr.agent.common.Result;
import com.dcdr.agent.dto.MonitorDtos;
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

/**
 * V3.0-beta 治理与可观测性接口 (F-34 执行监控 / 成本统计, 文档 §8)。
 * <p>
 * 端点清单:
 * <ul>
 *   <li>GET /api/monitor/workflows   工作流执行监控总览 (次数/成功率/平均耗时/按工作流聚合)</li>
 *   <li>GET /api/monitor/cost         LLM token 成本统计 (按天/按模型聚合)</li>
 *   <li>GET /api/monitor/quota        当前用户配额使用情况 (F-39)</li>
 * </ul>
 * <p>
 * 普通用户仅可见自己的数据; 管理员传 {@code scope=global} 可查看全局统计。
 */
@Slf4j
@RestController
@RequestMapping("/api/monitor")
@RequiredArgsConstructor
@Tag(name = "监控与成本", description = "V3.0-beta 执行监控 / 成本统计 / 配额查询 (F-34 / F-39)")
public class MonitorController {

    private final WorkflowMonitorService monitorService;
    private final CostTracker costTracker;

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
}
