package com.dcdr.agent.controller;

import com.dcdr.agent.agent.workflow.WorkflowEngine;
import com.dcdr.agent.auth.ownership.OwnershipGuard;
import com.dcdr.agent.common.Result;
import com.dcdr.agent.dto.WorkflowDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * V2.3 工作流接口 (F-25 多 Agent 协作编排, 对应 V2.0 规划文档 §5.3 / §10.1 /api/workflows)。
 * <p>
 * 端点清单:
 * <ul>
 *   <li>GET    /api/workflows                     工作流列表(自己 + 预设模板)</li>
 *   <li>GET    /api/workflows/templates           预设模板库</li>
 *   <li>GET    /api/workflows/{id}                工作流详情(含 steps)</li>
 *   <li>POST   /api/workflows                     创建工作流</li>
 *   <li>PUT    /api/workflows/{id}                更新工作流(模板仅管理员可改)</li>
 *   <li>DELETE /api/workflows/{id}                删除工作流(模板仅管理员可删)</li>
 *   <li>POST   /api/workflows/{id}/clone          克隆模板为自己的副本</li>
 *   <li>POST   /api/workflows/execute             执行工作流(同步返回各步结果)</li>
 *   <li>GET    /api/workflows/executions          执行记录分页</li>
 *   <li>GET    /api/workflows/executions/{id}     执行详情</li>
 *   <li>POST   /api/workflows/executions/{id}/resume|approve|cancel   断点续跑/审批/取消</li>
 *   <li>GET    /api/workflows/{id}/versions       版本历史</li>
 *   <li>POST   /api/workflows/{id}/versions/{version}/rollback        版本回滚</li>
 * </ul>
 * <p>
 * 权限: workflow:execute (创建/修改/删除/执行); 模板增删改仅管理员。
 * <p>
 * V3.1 F-41: 涉及具体工作流/执行记录的端点用 {@code @PreAuthorize} 声明式校验归属
 * (当前用户由 SpEL 变量 {@code principal} 提供, <b>无需</b>声明 {@code ServerWebExchange} 参数),
 * 越权与不存在统一返回 404; 需要 userId/isAdmin 的业务调用由
 * {@link OwnershipGuard#currentContext()} 响应式提供。
 * <p>
 * <b>线程模型</b>: 执行类端点会同步调用 LLM, 必须切到 boundedElastic 线程池,
 * 避免阻塞 reactor-http-nio (项目硬约束)。
 */
@Slf4j
@RestController
@RequestMapping("/api/workflows")
@RequiredArgsConstructor
@Tag(name = "工作流编排", description = "V2.3 多 Agent 工作流定义与执行 (F-25)")
public class WorkflowController {

    private final WorkflowEngine workflowEngine;
    /** V3.1 F-41: 响应式读取当前登录用户 + 归属校验 */
    private final OwnershipGuard ownershipGuard;

    @Operation(summary = "工作流列表", description = "返回当前用户可见的工作流: 自己创建的 + 全部预设模板")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = WorkflowDtos.WorkflowView.class)))
    @GetMapping
    public Mono<Result<List<WorkflowDtos.WorkflowView>>> list() {
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(workflowEngine.list(ctx.userId(), ctx.admin())));
    }

    @Operation(summary = "预设模板库", description = "返回全部预设工作流模板 (isTemplate=true), 任何用户可见可克隆")
    @GetMapping("/templates")
    public Mono<Result<List<WorkflowDtos.WorkflowView>>> templates() {
        return Mono.just(Result.success(workflowEngine.listTemplates()));
    }

    @Operation(summary = "工作流详情", description = "返回工作流定义 (含 steps 数组)")
    @ApiResponse(responseCode = "404", description = "工作流不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireRead(#id, 'WORKFLOW', principal)")
    @GetMapping("/{id}")
    public Mono<Result<WorkflowDtos.WorkflowView>> get(
            @Parameter(description = "工作流 ID", required = true) @PathVariable String id) {
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(workflowEngine.get(id, ctx.userId(), ctx.admin())));
    }

    @Operation(summary = "创建工作流",
            description = "定义工作流: 名称/模式(sequential/parallel/conditional)/步骤数组。"
                    + "管理员可在请求中设置 isTemplate=true 创建预设模板。")
    @PostMapping
    public Mono<Result<WorkflowDtos.WorkflowView>> create(@RequestBody WorkflowDtos.SaveWorkflowRequest req) {
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(workflowEngine.create(req, ctx.userId(), ctx.admin())));
    }

    @Operation(summary = "更新工作流",
            description = "更新工作流定义。普通用户仅能改自己的工作流; 管理员可修改预设模板。")
    @ApiResponse(responseCode = "404", description = "工作流不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireWrite(#id, 'WORKFLOW', principal)")
    @PutMapping("/{id}")
    public Mono<Result<WorkflowDtos.WorkflowView>> update(
            @Parameter(description = "工作流 ID", required = true) @PathVariable String id,
            @RequestBody WorkflowDtos.SaveWorkflowRequest req) {
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(workflowEngine.update(id, req, ctx.userId(), ctx.admin())));
    }

    @Operation(summary = "删除工作流",
            description = "删除工作流。普通用户仅能删自己的工作流; 管理员可删除预设模板。")
    @ApiResponse(responseCode = "404", description = "工作流不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireWrite(#id, 'WORKFLOW', principal)")
    @DeleteMapping("/{id}")
    public Mono<Result<Void>> delete(
            @Parameter(description = "工作流 ID", required = true) @PathVariable String id) {
        return ownershipGuard.currentContext().map(ctx -> {
            workflowEngine.delete(id, ctx.userId(), ctx.admin());
            return Result.success();
        });
    }

    @Operation(summary = "克隆工作流", description = "克隆预设模板或自己的工作流为副本 (预设模板不可直接修改, 需克隆)")
    @ApiResponse(responseCode = "404", description = "工作流不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireRead(#id, 'WORKFLOW', principal)")
    @PostMapping("/{id}/clone")
    public Mono<Result<WorkflowDtos.WorkflowView>> clone(
            @Parameter(description = "源工作流 ID", required = true) @PathVariable String id) {
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(workflowEngine.clone(id, ctx.userId(), ctx.admin())));
    }

    @Operation(summary = "执行工作流",
            description = "按定义的编排模式 (sequential/parallel/conditional) 执行各步骤 Agent, 返回各步执行详情与最终输出。"
                    + "同步阻塞调用 LLM, 在 boundedElastic 线程池执行, 避免阻塞 reactor-http-nio。")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = WorkflowDtos.ExecutionResult.class)))
    @PostMapping("/execute")
    public Mono<Result<WorkflowDtos.ExecutionResult>> execute(@RequestBody WorkflowDtos.ExecuteWorkflowRequest req) {
        // LLM 调用为阻塞操作, 必须切到 boundedElastic 线程池
        return ownershipGuard.currentContext()
                .flatMap(ctx -> Mono.fromCallable(() -> workflowEngine.execute(req, ctx.userId()))
                        .subscribeOn(Schedulers.boundedElastic())
                        .map(Result::success));
    }

    // ===== V3.0-alpha F-33 执行容错与人工介入 =====

    @Operation(summary = "执行记录列表 (分页)",
            description = "V3.0 F-33: 分页返回当前用户的工作流执行记录, 支持关键词搜索与筛选, 用于断点续跑与人工审批面板。"
                    + "keyword 模糊匹配输入内容/错误信息; status/workflowId/triggerType 为等值筛选; 全部可选。")
    @GetMapping("/executions")
    public Mono<Result<WorkflowDtos.ExecutionPageView>> listExecutions(
            @Parameter(description = "页码 (从 0 开始)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "每页条数 (1~100)") @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "状态筛选: running / paused / success / failed / cancelled") @RequestParam(required = false) String status,
            @Parameter(description = "工作流 ID 筛选") @RequestParam(required = false) String workflowId,
            @Parameter(description = "触发方式筛选: manual / api / webhook / event / schedule / chat") @RequestParam(required = false) String triggerType,
            @Parameter(description = "关键词, 模糊匹配输入内容/错误信息") @RequestParam(required = false) String keyword) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        return ownershipGuard.currentContext().map(ctx -> Result.success(
                workflowEngine.listExecutionsPaged(ctx.userId(), ctx.admin(),
                        status, workflowId, triggerType, keyword, pageable)));
    }

    @Operation(summary = "执行详情",
            description = "V3.0 F-33: 返回单次执行记录详情 (含各步状态/输出/错误), 用于断点续跑与审批。")
    @ApiResponse(responseCode = "404", description = "执行记录不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireRead(#id, 'WORKFLOW_EXECUTION', principal)")
    @GetMapping("/executions/{id}")
    public Mono<Result<WorkflowDtos.ExecutionView>> getExecution(
            @Parameter(description = "执行 ID", required = true) @PathVariable String id) {
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(workflowEngine.getExecution(id, ctx.userId(), ctx.admin())));
    }

    @Operation(summary = "断点续跑",
            description = "V3.0 F-33: 从 failed 状态的执行断点继续执行 (跳过已成功步骤, 重跑失败步骤)。"
                    + "阻塞 LLM 调用, 在 boundedElastic 线程池执行。")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = WorkflowDtos.ExecutionResult.class)))
    @ApiResponse(responseCode = "404", description = "执行记录不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireWrite(#id, 'WORKFLOW_EXECUTION', principal)")
    @PostMapping("/executions/{id}/resume")
    public Mono<Result<WorkflowDtos.ExecutionResult>> resume(
            @Parameter(description = "执行 ID", required = true) @PathVariable String id) {
        return ownershipGuard.currentContext()
                .flatMap(ctx -> Mono.fromCallable(() -> workflowEngine.resume(id, ctx.userId(), ctx.admin()))
                        .subscribeOn(Schedulers.boundedElastic())
                        .map(Result::success));
    }

    @Operation(summary = "人工审批通过",
            description = "V3.0 F-33: 对 paused 在审批节点的执行予以通过, 从下一步继续执行。"
                    + "请求体可携带审批意见 comment。阻塞 LLM 调用, 在 boundedElastic 线程池执行。")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = WorkflowDtos.ExecutionResult.class)))
    @ApiResponse(responseCode = "404", description = "执行记录不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireWrite(#id, 'WORKFLOW_EXECUTION', principal)")
    @PostMapping("/executions/{id}/approve")
    public Mono<Result<WorkflowDtos.ExecutionResult>> approve(
            @Parameter(description = "执行 ID", required = true) @PathVariable String id,
            @RequestBody(required = false) WorkflowDtos.ResumeRequest req) {
        String comment = req == null ? null : req.getComment();
        return ownershipGuard.currentContext()
                .flatMap(ctx -> Mono.fromCallable(() -> workflowEngine.approve(id, ctx.userId(), comment, ctx.admin()))
                        .subscribeOn(Schedulers.boundedElastic())
                        .map(Result::success));
    }

    @Operation(summary = "取消执行",
            description = "V3.0 F-33: 取消 running/paused 状态的执行, 标记为 cancelled。")
    @ApiResponse(responseCode = "404", description = "执行记录不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireWrite(#id, 'WORKFLOW_EXECUTION', principal)")
    @PostMapping("/executions/{id}/cancel")
    public Mono<Result<Void>> cancel(
            @Parameter(description = "执行 ID", required = true) @PathVariable String id) {
        return ownershipGuard.currentContext().map(ctx -> {
            workflowEngine.cancel(id, ctx.userId(), ctx.admin());
            return Result.success();
        });
    }

    // ===== V3.0-beta F-34 版本管理 =====

    @Operation(summary = "版本历史",
            description = "V3.0-beta F-34: 返回工作流的版本快照列表 (创建/每次更新/回滚均生成版本), 按版本号倒序。")
    @ApiResponse(responseCode = "404", description = "工作流不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireRead(#id, 'WORKFLOW', principal)")
    @GetMapping("/{id}/versions")
    public Mono<Result<List<WorkflowDtos.VersionView>>> listVersions(
            @Parameter(description = "工作流 ID", required = true) @PathVariable String id) {
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(workflowEngine.listVersions(id, ctx.userId(), ctx.admin())));
    }

    @Operation(summary = "回滚到指定版本",
            description = "V3.0-beta F-34: 将工作流定义回滚到指定历史版本; 回滚动作本身会生成新的版本快照, "
                    + "因此历史不会被覆盖, 可继续回滚。普通用户仅可回滚自己的工作流, 模板仅管理员。")
    @ApiResponse(responseCode = "404", description = "工作流不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireWrite(#id, 'WORKFLOW', principal)")
    @PostMapping("/{id}/versions/{version}/rollback")
    public Mono<Result<WorkflowDtos.WorkflowView>> rollback(
            @Parameter(description = "工作流 ID", required = true) @PathVariable String id,
            @Parameter(description = "目标版本号", required = true) @PathVariable int version) {
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(workflowEngine.rollback(id, version, ctx.userId(), ctx.admin())));
    }
}
