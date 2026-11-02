package com.dcdr.agent.controller;

import com.dcdr.agent.agent.governance.WebHealthService;
import com.dcdr.agent.auth.SecurityContext;
import com.dcdr.agent.common.Result;
import com.dcdr.agent.dto.WebHealthDtos;
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
 * 联网健康度查询接口 (联网搜索 / 抓取的可观测性)。
 *
 * <p>回答"线上联网效果有没有在漂移": 空结果率 / 清洗存活率 / 抓取成功率 / 薄页占比,
 * 以及能直接定位原因的空结果成因分布与异常样本。
 * 与固定评测({@code WebExtractFixtureTest}、搜索金标)互补 ——
 * 后者看"代码改动", 本接口看"真实流量"。
 *
 * <p><b>权限: 仅管理员</b>。理由与检索健康度一致: 样本里含<b>用户原始提问</b>,
 * 属运维可观测数据。前端同样以 {@code isAdmin} 隐藏入口, 前后端双重把关。
 */
@Slf4j
@RestController
@RequestMapping("/api/web-health")
@RequiredArgsConstructor
@Tag(name = "联网健康度", description = "联网搜索/抓取质量可观测性: 空结果率 / 清洗存活率 / 抓取成功率 / 异常样本 (仅管理员)")
public class WebHealthController {

    private final WebHealthService webHealthService;
    private final com.dcdr.agent.agent.governance.WebUsageService webUsageService;

    @Operation(summary = "联网健康度总览",
            description = "时间窗口内的联网执行质量: 调用次数、**空结果率**(最该盯的指标)、"
                    + "清洗存活率、抓取成功率、薄页占比、正文平均长度与耗时 P95, "
                    + "以及按天趋势、空结果成因分布(区分上游无返回与清洗规则过严)、"
                    + "存活率异常样本、薄页样本和上一等长周期的对比值。管理员专属。")
    @GetMapping("/overview")
    public Result<WebHealthDtos.HealthOverview> overview(
            @Parameter(description = "统计窗口天数 (1~180)")
            @RequestParam(defaultValue = "30") int days,
            ServerWebExchange exchange) {
        if (!SecurityContext.isAdmin(exchange)) {
            return Result.error(403, "仅管理员可查看联网健康度");
        }
        // 管理员视角 = 全局(userId 传 null), 不区分单个用户
        return Result.success(webHealthService.overview(null, days));
    }

    @Operation(summary = "联网用量与成本",
            description = "时间窗口内的搜索/抓取**次数**与**按源估算的费用**: 按 provider 拆分"
                    + "(DeepSeek 按次计费、自建 SearXNG 零单价), 并给出按天趋势。"
                    + "未配置 `agent-tools.web.pricing` 时 estimatedCost 为 null(界面显示「未配置单价」), "
                    + "不会返回一个猜出来的数字。管理员专属。")
    @GetMapping("/usage")
    public Result<com.dcdr.agent.agent.governance.WebUsageService.UsageSummary> usage(
            @Parameter(description = "统计窗口天数 (1~180)")
            @RequestParam(defaultValue = "30") int days,
            ServerWebExchange exchange) {
        if (!SecurityContext.isAdmin(exchange)) {
            return Result.error(403, "仅管理员可查看联网用量");
        }
        return Result.success(webUsageService.summary(days));
    }
}
