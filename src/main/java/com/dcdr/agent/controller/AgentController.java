package com.dcdr.agent.controller;

import com.dcdr.agent.agent.definition.AgentDefinitionService;
import com.dcdr.agent.auth.SecurityContext;
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
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;

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
 */
@Slf4j
@RestController
@RequestMapping("/api/agents")
@RequiredArgsConstructor
@Tag(name = "自定义 Agent", description = "V2.3 自定义 Agent 角色管理 (F-24)")
public class AgentController {

    private final AgentDefinitionService agentService;

    @Operation(summary = "Agent 列表", description = "返回当前用户可见的 Agent: 自己创建的 + 公开的 + 预设模板")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = AgentDtos.AgentView.class)))
    @GetMapping
    public Result<List<AgentDtos.AgentView>> list(ServerWebExchange exchange) {
        String userId = SecurityContext.getUserId(exchange);
        return Result.success(agentService.list(userId));
    }

    @Operation(summary = "预设模板库", description = "返回全部预设模板 (isTemplate=true), 任何用户可见可克隆")
    @GetMapping("/templates")
    public Result<List<AgentDtos.AgentView>> templates() {
        return Result.success(agentService.listTemplates());
    }

    @Operation(summary = "Agent 详情")
    @GetMapping("/{id}")
    public Result<AgentDtos.AgentView> get(@Parameter(description = "Agent ID", required = true) @PathVariable String id,
                                           ServerWebExchange exchange) {
        return Result.success(agentService.get(id, SecurityContext.getUserId(exchange)));
    }

    @Operation(summary = "创建 Agent", description = "创建自定义 Agent (配置 Prompt/模型/工具/知识库/温度)")
    @PostMapping
    public Result<AgentDtos.AgentView> create(@RequestBody AgentDtos.SaveAgentRequest req,
                                              ServerWebExchange exchange) {
        return Result.success(agentService.create(req, SecurityContext.getUserId(exchange)));
    }

    @Operation(summary = "更新 Agent", description = "更新自己的 Agent 配置 (模板和他人 Agent 不可改)")
    @PutMapping("/{id}")
    public Result<AgentDtos.AgentView> update(@Parameter(description = "Agent ID", required = true) @PathVariable String id,
                                              @RequestBody AgentDtos.SaveAgentRequest req,
                                              ServerWebExchange exchange) {
        return Result.success(agentService.update(id, req, SecurityContext.getUserId(exchange)));
    }

    @Operation(summary = "删除 Agent", description = "删除自己的 Agent (模板不可删)")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "Agent ID", required = true) @PathVariable String id,
                               ServerWebExchange exchange) {
        agentService.delete(id, SecurityContext.getUserId(exchange));
        return Result.success();
    }

    @Operation(summary = "克隆 Agent", description = "克隆模板或他人公开 Agent 为自己的副本 (预设模板不可直接修改, 需克隆)")
    @PostMapping("/{id}/clone")
    public Result<AgentDtos.AgentView> clone(@Parameter(description = "源 Agent ID", required = true) @PathVariable String id,
                                             ServerWebExchange exchange) {
        return Result.success(agentService.clone(id, SecurityContext.getUserId(exchange)));
    }
}
