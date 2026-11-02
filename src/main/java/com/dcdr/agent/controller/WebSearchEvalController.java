package com.dcdr.agent.controller;

import com.dcdr.agent.auth.SecurityContext;
import com.dcdr.agent.common.Result;
import com.dcdr.agent.eval.WebSearchEvalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 联网搜索排序评测接口。
 *
 * <p><b>为什么要有接口, 而不只是一个 JUnit 类</b>: 评测最初是 {@code WebSearchEvalIT},
 * 跑法是一串环境变量 + Maven 命令 —— 只有写它的人知道怎么跑。但"排序质量好不好"
 * 是产品问题, 需要有个地方点一下就能看。接口 + 页面是这个"地方"。
 *
 * <p><b>权限</b>: 全部管理员专属。跑一次会真实调用外部搜索(deepseek 按次计费),
 * 不该开放给普通用户。与 {@code WebHealthController} 同一口径。
 *
 * <p><b>为什么要包 {@code Mono.fromCallable}</b>: 读归档目录是阻塞文件 IO,
 * 直接写在 WebFlux 处理器里会占住 reactor-http-nio 线程。文件小、耗时短,
 * 但"短"不构成理由 —— 这类调用一旦多了就是线程饥饿。
 */
@Slf4j
@RestController
@RequestMapping("/api/eval/web-search")
@RequiredArgsConstructor
@Tag(name = "联网评测", description = "联网搜索排序质量评测: 触发运行 / 历史报告 / 单份详情")
public class WebSearchEvalController {

    private final WebSearchEvalService evalService;

    @Operation(summary = "概览", description = "可用搜索源(含是否就绪) + 当前/最近一次运行状态。管理员专属。")
    @GetMapping("/overview")
    public Result<Map<String, Object>> overview(ServerWebExchange exchange) {
        if (!SecurityContext.isAdmin(exchange)) {
            return Result.error(403, "仅管理员可查看联网评测");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("providers", evalService.availableProviders());
        body.put("state", evalService.currentState());
        return Result.success(body);
    }

    @Operation(summary = "历史报告列表",
            description = "返回归档目录下全部报告(按时间倒序), 只含关键指标, 不含逐条明细。")
    @GetMapping("/reports")
    public Mono<Result<List<WebSearchEvalService.ReportSummary>>> reports(ServerWebExchange exchange) {
        if (!SecurityContext.isAdmin(exchange)) {
            return Mono.just(Result.error(403, "仅管理员可查看联网评测"));
        }
        return Mono.fromCallable(evalService::listReports)
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::success);
    }

    @Operation(summary = "单份报告详情",
            description = "返回该次评测的完整逐条结果(命中位次、命中 URL、首条 URL、异常), 供页面渲染未命中明细。")
    @GetMapping("/reports/{file}")
    public Mono<Result<WebSearchEvalService.EvalReport>> report(
            @Parameter(description = "报告文件名, 如 2026-11-02-1713-searxng+deepseek.json",
                    required = true) @PathVariable String file,
            ServerWebExchange exchange) {
        if (!SecurityContext.isAdmin(exchange)) {
            return Mono.just(Result.error(403, "仅管理员可查看联网评测"));
        }
        return Mono.fromCallable(() -> evalService.readReport(file))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::success)
                .onErrorResume(e -> {
                    // 越权/不存在/文件名非法统一 404: 不靠错误类型区分, 避免被用作目录探测
                    log.warn("读取评测报告失败: file={}, cause={}", file, e.getMessage());
                    return Mono.just(Result.error(404, "报告不存在"));
                });
    }

    @Operation(summary = "触发一次评测",
            description = "异步执行, 立即返回运行状态; 前端轮询 /overview 的 state 看进度。"
                    + "同一时刻只允许一个任务, 重复提交会返回当前状态而不重复执行(避免重复计费)。")
    @PostMapping("/run")
    public Result<WebSearchEvalService.RunState> run(
            @Parameter(description = "搜索源: both(默认) / searxng / deepseek")
            @RequestParam(defaultValue = "both") String provider,
            ServerWebExchange exchange) {
        if (!SecurityContext.isAdmin(exchange)) {
            return Result.error(403, "仅管理员可运行联网评测");
        }
        // submit 内部把重活丢到单线程池, 本身不阻塞, 可以直接在请求线程返回
        return Result.success(evalService.submit(provider));
    }
}
