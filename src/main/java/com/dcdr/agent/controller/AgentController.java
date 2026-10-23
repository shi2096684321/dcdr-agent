package com.dcdr.agent.controller;

import com.dcdr.agent.agent.definition.AgentDefinitionService;
import com.dcdr.agent.agent.definition.AgentDraftGenerator;
import com.dcdr.agent.auth.ownership.OwnershipGuard;
import com.dcdr.agent.common.Result;
import com.dcdr.agent.dto.AgentDtos;
import com.dcdr.agent.dto.VersionDiffDtos;
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
 * V2.3 自定义 Agent 接口 (F-24, 对应 V2.0 规划文档 §10.1 /api/agents)。
 * <p>
 * 端点清单:
 * <ul>
 *   <li>GET    /api/agents                Agent 列表(自己 + 公开 + 模板)</li>
 *   <li>GET    /api/agents/templates      预设模板库</li>
 *   <li>GET    /api/agents/{id}           Agent 详情</li>
 *   <li>POST   /api/agents                创建 Agent</li>
 *   <li>POST   /api/agents/generate       AI 生成 Agent 草稿(一句话需求, 不落库)</li>
 *   <li>PUT    /api/agents/{id}           更新 Agent</li>
 *   <li>DELETE /api/agents/{id}           删除 Agent</li>
 *   <li>POST   /api/agents/{id}/clone     克隆模板/公开 Agent 为自己的副本</li>
 * </ul>
 * <p>
 * 权限: agent:create (user/admin 均可创建自己的 Agent); 普通用户修改/删除仅限创建者本人,
 * <b>管理员可修改/删除任意用户的 Agent 及预设模板</b>(归属判定见 {@code OwnershipGuard#canAccess},
 * admin 恒放行)。列表口径: 普通用户为自己 + 公开 + 模板, 管理员为全部(含他人私有)。
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
    /** V3.2: 一句话需求 → 专家配置草稿(LLM 生成, 不落库) */
    private final AgentDraftGenerator draftGenerator;
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

    @Operation(summary = "AI 生成专家草稿",
            description = "V3.2: 用户只给一句话需求, 由模型补齐名称/头衔/分类/标签/推荐问法/角色设定/工具/温度, "
                    + "返回<b>未落库</b>的草稿。前端回填创建表单, 用户确认后再走 POST /api/agents —— "
                    + "配额、工具白名单、版本快照等既有约束因此无需重写。")
    @ApiResponse(responseCode = "502", description = "模型不可用, 或输出里没有可用的角色设定")
    @PostMapping("/generate")
    public Mono<Result<AgentDtos.AgentDraft>> generate(@RequestBody AgentDtos.GenerateAgentRequest req) {
        return ownershipGuard.currentContext().flatMap(ctx -> Mono
                .fromCallable(() -> Result.success(draftGenerator.generate(
                        req.getBrief(), req.getModel(), ctx.userId(), ctx.admin())))
                // 同步阻塞调用 LLM, 不能跑在 reactor 事件循环线程上(与技能市场同口径)
                .subscribeOn(Schedulers.boundedElastic()));
    }

    @Operation(summary = "更新 Agent",
            description = "更新 Agent 配置。普通用户仅能改自己的 Agent; 管理员可修改任意用户的 Agent 与预设模板。")
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

    // ===== V3.1 F-44.1 版本管理 =====

    @Operation(summary = "版本历史",
            description = "V3.1 F-44.1: 返回 Agent 的版本快照列表 (创建/每次更新/回滚均生成版本), 按版本号倒序。")
    @ApiResponse(responseCode = "404", description = "Agent 不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireRead(#id, 'AGENT', principal)")
    @GetMapping("/{id}/versions")
    public Mono<Result<List<AgentDtos.VersionView>>> listVersions(
            @Parameter(description = "Agent ID", required = true) @PathVariable String id) {
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(agentService.listVersions(id, ctx.userId(), ctx.admin())));
    }

    @Operation(summary = "回滚到指定版本",
            description = "V3.1 F-44.1: 将 Agent 定义回滚到指定历史版本; 回滚动作本身会生成新的版本快照, "
                    + "因此历史不被覆盖, 可继续回滚。普通用户仅可回滚自己的 Agent, 模板仅管理员。")
    @ApiResponse(responseCode = "404", description = "Agent 或版本不存在, 或无权访问")
    @PreAuthorize("@ownershipGuard.requireWrite(#id, 'AGENT', principal)")
    @PostMapping("/{id}/versions/{version}/rollback")
    public Mono<Result<AgentDtos.AgentView>> rollback(
            @Parameter(description = "Agent ID", required = true) @PathVariable String id,
            @Parameter(description = "目标版本号", required = true) @PathVariable int version) {
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(agentService.rollback(id, version, ctx.userId(), ctx.admin())));
    }

    @Operation(summary = "版本对比",
            description = "V3.1 F-44.1: 结构化对比两个历史版本 (字段级差异; 列表类字段忽略顺序差异)。")
    @ApiResponse(responseCode = "404", description = "Agent 或版本不存在, 或无权访问")
    @PreAuthorize("@ownershipGuard.requireRead(#id, 'AGENT', principal)")
    @GetMapping("/{id}/versions/{left}/diff/{right}")
    public Mono<Result<VersionDiffDtos.View>> diff(
            @Parameter(description = "Agent ID", required = true) @PathVariable String id,
            @Parameter(description = "左侧(较旧)版本号", required = true) @PathVariable int left,
            @Parameter(description = "右侧(较新)版本号", required = true) @PathVariable int right) {
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(agentService.diff(id, left, right, ctx.userId(), ctx.admin())));
    }
}
