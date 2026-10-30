package com.dcdr.agent.controller;

import com.dcdr.agent.agent.schedule.ScheduledTaskService;
import com.dcdr.agent.auth.ownership.OwnershipGuard;
import com.dcdr.agent.common.Result;
import com.dcdr.agent.dto.ScheduledTaskDtos;
import com.dcdr.agent.notify.NotifyResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * V2.3 定时任务接口 (F-26 定时触发 Agent, 对应 V2.0 规划文档 §5.x / §10.1 /api/scheduled-tasks)。
 * <p>
 * 端点清单:
 * <ul>
 *   <li>GET    /api/scheduled-tasks                 任务列表(当前用户)</li>
 *   <li>GET    /api/scheduled-tasks/{id}            任务详情</li>
 *   <li>POST   /api/scheduled-tasks                 创建任务(指定 Agent + cron + 输入)</li>
 *   <li>PUT    /api/scheduled-tasks/{id}            更新任务</li>
 *   <li>DELETE /api/scheduled-tasks/{id}            删除任务(取消调度)</li>
 *   <li>POST   /api/scheduled-tasks/{id}/trigger    手动触发一次执行(不依赖 cron)</li>
 * </ul>
 * <p>
 * 权限: schedule:manage; 仅创建者可操作自己的任务。
 * <p>
 * V3.1 F-41: 涉及具体任务的端点用 {@code @PreAuthorize} 声明式校验归属
 * (当前用户由 SpEL 变量 {@code principal} 提供, <b>无需</b>声明 {@code ServerWebExchange} 参数),
 * 越权与不存在统一返回 404。
 * <p>
 * <b>线程模型</b>: trigger 端点同步调用 Agent (LLM 阻塞), 必须切到 boundedElastic 线程池。
 */
@Slf4j
@RestController
@RequestMapping("/api/scheduled-tasks")
@RequiredArgsConstructor
@Tag(name = "定时任务", description = "V2.3 定时触发 Agent 执行任务 (F-26)")
public class ScheduledTaskController {

    private final ScheduledTaskService scheduledTaskService;
    /** V3.1 F-41: 响应式读取当前登录用户 + 归属校验 */
    private final OwnershipGuard ownershipGuard;

    @Operation(summary = "定时任务列表", description = "返回当前用户创建的定时任务(含最近运行状态)")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ScheduledTaskDtos.ScheduledTaskView.class)))
    @GetMapping
    public Mono<Result<List<ScheduledTaskDtos.ScheduledTaskView>>> list() {
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(scheduledTaskService.list(ctx.userId(), ctx.admin())));
    }

    @Operation(summary = "定时任务详情")
    @ApiResponse(responseCode = "404", description = "定时任务不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireRead(#id, 'SCHEDULED_TASK', principal)")
    @GetMapping("/{id}")
    public Mono<Result<ScheduledTaskDtos.ScheduledTaskView>> get(
            @Parameter(description = "任务 ID", required = true) @PathVariable String id) {
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(scheduledTaskService.get(id, ctx.userId(), ctx.admin())));
    }

    @Operation(summary = "创建定时任务",
            description = "关联一个 Agent, 指定 Spring 6 字段 cron 表达式 (秒 分 时 日 月 周) 与触发输入消息")
    @PostMapping
    public Mono<Result<ScheduledTaskDtos.ScheduledTaskView>> create(
            @RequestBody ScheduledTaskDtos.SaveScheduledTaskRequest req) {
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(scheduledTaskService.create(req, ctx.userId())));
    }

    @Operation(summary = "更新定时任务", description = "更新任务配置; 更新 cron 会重新调度")
    @ApiResponse(responseCode = "404", description = "定时任务不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireWrite(#id, 'SCHEDULED_TASK', principal)")
    @PutMapping("/{id}")
    public Mono<Result<ScheduledTaskDtos.ScheduledTaskView>> update(
            @Parameter(description = "任务 ID", required = true) @PathVariable String id,
            @RequestBody ScheduledTaskDtos.SaveScheduledTaskRequest req) {
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(scheduledTaskService.update(id, req, ctx.userId(), ctx.admin())));
    }

    @Operation(summary = "删除定时任务", description = "删除任务并取消其调度")
    @ApiResponse(responseCode = "404", description = "定时任务不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireWrite(#id, 'SCHEDULED_TASK', principal)")
    @DeleteMapping("/{id}")
    public Mono<Result<Void>> delete(
            @Parameter(description = "任务 ID", required = true) @PathVariable String id) {
        return ownershipGuard.currentContext().map(ctx -> {
            scheduledTaskService.delete(id, ctx.userId(), ctx.admin());
            return Result.success();
        });
    }

    @Operation(summary = "手动触发任务", description = "立即执行一次任务 (不影响 cron 调度); 同步阻塞调用 LLM")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ScheduledTaskDtos.TriggerResult.class)))
    @ApiResponse(responseCode = "404", description = "定时任务不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireWrite(#id, 'SCHEDULED_TASK', principal)")
    @PostMapping("/{id}/trigger")
    public Mono<Result<ScheduledTaskDtos.TriggerResult>> trigger(
            @Parameter(description = "任务 ID", required = true) @PathVariable String id,
            @RequestBody(required = false) ScheduledTaskDtos.TriggerRequest req) {
        // LLM 调用为阻塞操作, 必须切到 boundedElastic 线程池
        return ownershipGuard.currentContext()
                .flatMap(ctx -> Mono.fromCallable(() -> scheduledTaskService.trigger(id, req, ctx.userId(), ctx.admin()))
                        .subscribeOn(Schedulers.boundedElastic())
                        .map(Result::success));
    }

    @Operation(summary = "预览消息模板 (V3.3)",
            description = "用示例正文渲染该渠道的成功/失败两份消息，返回原始 markdown。"
                    + "不发送、不触发执行，用于在配置模板或切换渠道前确认效果。")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ScheduledTaskDtos.NotifyPreview.class)))
    @ApiResponse(responseCode = "404", description = "定时任务不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireRead(#id, 'SCHEDULED_TASK', principal)")
    @GetMapping("/{id}/notify-preview")
    public Mono<Result<ScheduledTaskDtos.NotifyPreview>> notifyPreview(
            @Parameter(description = "任务 ID", required = true) @PathVariable String id,
            @Parameter(description = "渠道 code，如 dingtalk_bot；留空用全局默认") @RequestParam(required = false) String channel) {
        // 纯渲染，无网络与数据库阻塞调用，无需切 boundedElastic
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(
                        scheduledTaskService.previewNotify(id, channel, ctx.userId(), ctx.admin())));
    }

    @Operation(summary = "执行历史列表 (V3.3)",
            description = "返回该任务最近若干次执行记录，按时间倒序，只含摘要（正文前 120 字）。")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ScheduledTaskDtos.RunSummary.class)))
    @ApiResponse(responseCode = "404", description = "定时任务不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireRead(#id, 'SCHEDULED_TASK', principal)")
    @GetMapping("/{id}/runs")
    public Mono<Result<List<ScheduledTaskDtos.RunSummary>>> runs(
            @Parameter(description = "任务 ID", required = true) @PathVariable String id,
            @Parameter(description = "返回条数，默认 20，上限 100") @RequestParam(required = false) Integer limit) {
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(scheduledTaskService.listRuns(id, limit, ctx.userId(), ctx.admin())));
    }

    @Operation(summary = "执行历史详情 (V3.3)", description = "含完整输出正文")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ScheduledTaskDtos.RunDetail.class)))
    @ApiResponse(responseCode = "404", description = "任务或执行记录不存在")
    @PreAuthorize("@ownershipGuard.requireRead(#id, 'SCHEDULED_TASK', principal)")
    @GetMapping("/{id}/runs/{runId}")
    public Mono<Result<ScheduledTaskDtos.RunDetail>> run(
            @Parameter(description = "任务 ID", required = true) @PathVariable String id,
            @Parameter(description = "执行记录 ID", required = true) @PathVariable String runId) {
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(scheduledTaskService.getRun(id, runId, ctx.userId(), ctx.admin())));
    }

    @Operation(summary = "测试推送 (V3.3)",
            description = "立即向该任务配置的通知渠道发一条测试消息, 用于验证 Webhook 是否打通。"
                    + "不触发 Agent 执行, 也不写入任务的推送状态。")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = NotifyResult.class)))
    @ApiResponse(responseCode = "404", description = "定时任务不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireWrite(#id, 'SCHEDULED_TASK', principal)")
    @PostMapping("/{id}/notify-test")
    public Mono<Result<NotifyResult>> notifyTest(
            @Parameter(description = "任务 ID", required = true) @PathVariable String id) {
        // 推送是阻塞 HTTP 调用, 与 trigger 一样必须切到 boundedElastic
        return ownershipGuard.currentContext()
                .flatMap(ctx -> Mono.fromCallable(() -> scheduledTaskService.testNotify(id, ctx.userId(), ctx.admin()))
                        .subscribeOn(Schedulers.boundedElastic())
                        .map(Result::success));
    }
}
