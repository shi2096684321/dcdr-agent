package com.dcdr.agent.controller;

import com.dcdr.agent.agent.definition.AgentDefinitionService;
import com.dcdr.agent.auth.ownership.OwnershipGuard;
import com.dcdr.agent.common.Result;
import com.dcdr.agent.dto.AgentDtos;
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

import java.util.List;

/**
 * V2.3 自定义 Agent 接口 (F-24, 对应 V2.0 规划文档 §10.1 /api/agents)。
 * <p>
 * 端点清单:
 * <ul>
 *   <li>GET    /api/agents                Agent 列表(自己 + 公开 + 模板)</li>
 *   <li>GET    /api/agents/templates      预设模板库</li>
 *   <li>GET    /api/agents/{id}           Agent 详情</li>
 *   <li>POST   /api/agents                创建 Agent</li>
 *   <li>PUT    /api/agents/{id}           更新 Agent</li>
 *   <li>DELETE /api/agents/{id}           删除 Agent</li>
 *   <li>POST   /api/agents/{id}/clone     克隆模板/公开 Agent 为自己的副本</li>
 * </ul>
 * <p>
 * 权限: agent:create (user/admin 均可创建自己的 Agent); 修改/删除仅限创建者本人。
 * <p>
 * V3.1 F-41: 涉及具体 Agent 的端点用 {@code @PreAuthorize} 声明式校验归属(当前用户由 SpEL 变量
 * {@code principal} 提供, 无需任何上下文参数), 越权与不存在统一返回 404;
 * 需要 userId/isAdmin 的业务调用由 {@link OwnershipGuard#currentContext()} 响应式提供。
 */
@Slf4j
@RestController
@RequestMapping("/api/agents")
@RequiredArgsConstructor
@Tag(name = "自定义 Agent", description = "V2.3 自定义 Agent 角色管理 (F-24)")
public class AgentController {

    private final AgentDefinitionService agentService;
    /** V3.1 F-41: 响应式读取当前登录用户 + 归属校验 */
    private final OwnershipGuard ownershipGuard;

    @Operation(summary = "Agent 列表", description = "返回当前用户可见的 Agent: 自己创建的 + 公开的 + 预设模板")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = AgentDtos.AgentView.class)))
    @GetMapping
    public Mono<Result<List<AgentDtos.AgentView>>> list() {
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(agentService.list(ctx.userId(), ctx.admin())));
    }

    @Operation(summary = "预设模板库", description = "返回全部预设模板 (isTemplate=true), 任何用户可见可克隆")
    @GetMapping("/templates")
    public Mono<Result<List<AgentDtos.AgentView>>> templates() {
        return Mono.just(Result.success(agentService.listTemplates()));
    }

    @Operation(summary = "Agent 详情")
    @ApiResponse(responseCode = "404", description = "Agent 不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireRead(#id, 'AGENT', principal)")
    @GetMapping("/{id}")
    public Mono<Result<AgentDtos.AgentView>> get(
            @Parameter(description = "Agent ID", required = true) @PathVariable String id) {
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(agentService.get(id, ctx.userId(), ctx.admin())));
    }

    @Operation(summary = "创建 Agent",
            description = "创建自定义 Agent (配置 Prompt/模型/工具/知识库/温度)。"
                    + "管理员可在请求中设置 isTemplate=true 创建预设模板。")
    @PostMapping
    public Mono<Result<AgentDtos.AgentView>> create(@RequestBody AgentDtos.SaveAgentRequest req) {
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(agentService.create(req, ctx.userId(), ctx.admin())));
    }

    @Operation(summary = "更新 Agent",
            description = "更新 Agent 配置。普通用户仅能改自己的 Agent; 管理员可修改预设模板。")
    @ApiResponse(responseCode = "404", description = "Agent 不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireWrite(#id, 'AGENT', principal)")
    @PutMapping("/{id}")
    public Mono<Result<AgentDtos.AgentView>> update(
            @Parameter(description = "Agent ID", required = true) @PathVariable String id,
            @RequestBody AgentDtos.SaveAgentRequest req) {
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(agentService.update(id, req, ctx.userId(), ctx.admin())));
    }

    @Operation(summary = "删除 Agent",
            description = "删除 Agent。普通用户仅能删自己的 Agent; 管理员可删除预设模板。")
    @ApiResponse(responseCode = "404", description = "Agent 不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireWrite(#id, 'AGENT', principal)")
    @DeleteMapping("/{id}")
    public Mono<Result<Void>> delete(
            @Parameter(description = "Agent ID", required = true) @PathVariable String id) {
        return ownershipGuard.currentContext()
                .map(ctx -> {
                    agentService.delete(id, ctx.userId(), ctx.admin());
                    return Result.success();
                });
    }

    @Operation(summary = "克隆 Agent", description = "克隆模板或他人公开 Agent 为自己的副本 (预设模板不可直接修改, 需克隆)")
    @ApiResponse(responseCode = "404", description = "Agent 不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireRead(#id, 'AGENT', principal)")
    @PostMapping("/{id}/clone")
    public Mono<Result<AgentDtos.AgentView>> clone(
            @Parameter(description = "源 Agent ID", required = true) @PathVariable String id) {
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(agentService.clone(id, ctx.userId(), ctx.admin())));
    }
}
