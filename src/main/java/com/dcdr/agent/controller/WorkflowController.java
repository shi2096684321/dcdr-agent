package com.dcdr.agent.controller;

import com.dcdr.agent.agent.workflow.WorkflowEngine;
import com.dcdr.agent.auth.SecurityContext;
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
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * V2.3 工作流接口 (F-25 多 Agent 协作编排, 对应 V2.0 规划文档 §5.3 / §10.1 /api/workflows)。
 * <p>
 * 端点清单:
 * <ul>
 *   <li>GET    /api/workflows                工作流列表(自己 + 预设模板)</li>
 *   <li>GET    /api/workflows/templates      预设模板库</li>
 *   <li>GET    /api/workflows/{id}           工作流详情(含 steps)</li>
 *   <li>POST   /api/workflows                创建工作流(管理员可设 isTemplate=true 创建模板)</li>
 *   <li>PUT    /api/workflows/{id}           更新工作流(模板仅管理员可改)</li>
 *   <li>DELETE /api/workflows/{id}           删除工作流(模板仅管理员可删)</li>
 *   <li>POST   /api/workflows/{id}/clone     克隆模板为自己的副本</li>
 *   <li>POST   /api/workflows/execute        执行工作流(同步返回各步结果)</li>
 * </ul>
 * <p>
 * 权限: workflow:execute (创建/修改/删除/执行); 模板增删改仅管理员。
 * <p>
 * <b>线程模型</b>: execute 端点会同步调用多个 Agent (LLM 阻塞), 必须切到 boundedElastic
 * 线程池, 避免阻塞 reactor-http-nio (项目硬约束)。
 */
@Slf4j
@RestController
@RequestMapping("/api/workflows")
@RequiredArgsConstructor
@Tag(name = "工作流编排", description = "V2.3 多 Agent 工作流定义与执行 (F-25)")
public class WorkflowController {

    private final WorkflowEngine workflowEngine;

    @Operation(summary = "工作流列表", description = "返回当前用户可见的工作流: 自己创建的 + 全部预设模板")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = WorkflowDtos.WorkflowView.class)))
    @GetMapping
    public Result<List<WorkflowDtos.WorkflowView>> list(ServerWebExchange exchange) {
        String userId = SecurityContext.getUserId(exchange);
        return Result.success(workflowEngine.list(userId));
    }

    @Operation(summary = "预设模板库", description = "返回全部预设工作流模板 (isTemplate=true), 任何用户可见可克隆")
    @GetMapping("/templates")
    public Result<List<WorkflowDtos.WorkflowView>> templates() {
        return Result.success(workflowEngine.listTemplates());
    }

    @Operation(summary = "工作流详情", description = "返回工作流定义 (含 steps 数组)")
    @GetMapping("/{id}")
    public Result<WorkflowDtos.WorkflowView> get(@Parameter(description = "工作流 ID", required = true)
                                                 @PathVariable String id,
                                                 ServerWebExchange exchange) {
        return Result.success(workflowEngine.get(id, SecurityContext.getUserId(exchange)));
    }

    @Operation(summary = "创建工作流",
            description = "定义工作流: 名称/模式(sequential/parallel/conditional)/步骤数组。"
                    + "管理员可在请求中设置 isTemplate=true 创建预设模板。")
    @PostMapping
    public Result<WorkflowDtos.WorkflowView> create(@RequestBody WorkflowDtos.SaveWorkflowRequest req,
                                                    ServerWebExchange exchange) {
        String userId = SecurityContext.getUserId(exchange);
        boolean isAdmin = SecurityContext.isAdmin(exchange);
        return Result.success(workflowEngine.create(req, userId, isAdmin));
    }

    @Operation(summary = "更新工作流",
            description = "更新工作流定义。普通用户仅能改自己的工作流; 管理员可修改预设模板。")
    @PutMapping("/{id}")
    public Result<WorkflowDtos.WorkflowView> update(@Parameter(description = "工作流 ID", required = true)
                                                    @PathVariable String id,
                                                    @RequestBody WorkflowDtos.SaveWorkflowRequest req,
                                                    ServerWebExchange exchange) {
        String userId = SecurityContext.getUserId(exchange);
        boolean isAdmin = SecurityContext.isAdmin(exchange);
        return Result.success(workflowEngine.update(id, req, userId, isAdmin));
    }

    @Operation(summary = "删除工作流",
            description = "删除工作流。普通用户仅能删自己的工作流; 管理员可删除预设模板。")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "工作流 ID", required = true)
                               @PathVariable String id,
                               ServerWebExchange exchange) {
        String userId = SecurityContext.getUserId(exchange);
        boolean isAdmin = SecurityContext.isAdmin(exchange);
        workflowEngine.delete(id, userId, isAdmin);
        return Result.success();
    }

    @Operation(summary = "克隆工作流", description = "克隆预设模板或自己的工作流为副本 (预设模板不可直接修改, 需克隆)")
    @PostMapping("/{id}/clone")
    public Result<WorkflowDtos.WorkflowView> clone(@Parameter(description = "源工作流 ID", required = true)
                                                   @PathVariable String id,
                                                   ServerWebExchange exchange) {
        return Result.success(workflowEngine.clone(id, SecurityContext.getUserId(exchange)));
    }

    @Operation(summary = "执行工作流",
            description = "按定义的编排模式 (sequential/parallel/conditional) 执行各步骤 Agent, 返回各步执行详情与最终输出。"
                    + "同步阻塞调用 LLM, 在 boundedElastic 线程池执行, 避免阻塞 reactor-http-nio。")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = WorkflowDtos.ExecutionResult.class)))
    @PostMapping("/execute")
    public Mono<Result<WorkflowDtos.ExecutionResult>> execute(@RequestBody WorkflowDtos.ExecuteWorkflowRequest req,
                                                              ServerWebExchange exchange) {
        String userId = SecurityContext.getUserId(exchange);
        // LLM 调用为阻塞操作, 必须切到 boundedElastic 线程池
        return Mono.fromCallable(() -> workflowEngine.execute(req, userId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::success);
    }
}
