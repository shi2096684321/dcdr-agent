package com.dcdr.agent.controller;

import com.dcdr.agent.agent.memory.longterm.LongTermMemoryService;
import com.dcdr.agent.auth.ownership.OwnershipGuard;
import com.dcdr.agent.common.Result;
import com.dcdr.agent.dto.MemoryDtos.MemorySummary;
import com.dcdr.agent.dto.MemoryDtos.MemoryView;
import com.dcdr.agent.dto.MemoryDtos.SaveRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 长期记忆接口 (V3.1)。
 * <p>
 * 记忆是个人隐私数据, 且必须对用户<b>可见、可编辑、可删除</b> —— 因此本类完整暴露读改删能力,
 * 而不是只做后台静默注入。全部端点按当前登录用户隔离;
 * 涉及具体条目的写操作用 {@code @PreAuthorize} + {@code OwnershipGuard.requireWrite} 声明式校验归属,
 * 越权与不存在统一返回 404 (防资源枚举)。
 * <p>
 * 注: {@code @EnableReactiveMethodSecurity} 要求被保护方法返回 {@code Mono}, 故本类端点统一
 * 返回 {@code Mono<Result<T>>} (对外 JSON 契约不变), 与 {@code SessionController} 口径一致。
 */
@RestController
@RequestMapping("/api/memories")
@RequiredArgsConstructor
@Tag(name = "长期记忆", description = "用户长期记忆的查看、新增、编辑、删除（跨会话生效）")
public class MemoryController {

    private final LongTermMemoryService memoryService;
    /** V3.1 F-41: 响应式读取当前登录用户 + 声明式归属校验 */
    private final OwnershipGuard ownershipGuard;

    @Operation(summary = "获取记忆列表",
            description = "返回当前用户的长期记忆; 默认仅活跃条目, includeInactive=true 时一并返回归档/已删除条目")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = MemoryView.class)))
    @GetMapping
    public Mono<Result<List<MemoryView>>> list(
            @Parameter(description = "是否包含归档/已删除条目 (默认 false)")
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(memoryService.list(ctx.userId(), includeInactive)));
    }

    @Operation(summary = "获取记忆概览", description = "返回活跃记忆总数、用户级/Agent 级分布及当前召回策略说明")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = MemorySummary.class)))
    @GetMapping("/summary")
    public Mono<Result<MemorySummary>> summary() {
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(memoryService.summary(ctx.userId())));
    }

    @Operation(summary = "新增记忆",
            description = "手动创建一条长期记忆。scope=USER 为跨 Agent 通用记忆; scope=AGENT 须同时指定 agentId")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = MemoryView.class)))
    @ApiResponse(responseCode = "400", description = "内容为空/超长、类型或作用域非法")
    @PostMapping
    public Mono<Result<MemoryView>> create(@RequestBody SaveRequest req) {
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(memoryService.create(ctx.userId(), req)));
    }

    @Operation(summary = "更新记忆", description = "仅更新请求中显式提供的字段; 调整作用域需显式传 scope")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = MemoryView.class)))
    @ApiResponse(responseCode = "404", description = "记忆不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireWrite(#id, 'MEMORY', principal)")
    @PutMapping("/{id}")
    public Mono<Result<MemoryView>> update(
            @Parameter(description = "记忆 ID", required = true) @PathVariable String id,
            @RequestBody SaveRequest req) {
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(memoryService.update(ctx.userId(), id, req)));
    }

    @Operation(summary = "删除记忆", description = "软删除该条记忆, 删除后不再参与后续对话召回")
    @ApiResponse(responseCode = "200", description = "删除成功")
    @ApiResponse(responseCode = "404", description = "记忆不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireWrite(#id, 'MEMORY', principal)")
    @DeleteMapping("/{id}")
    public Mono<Result<Void>> delete(
            @Parameter(description = "记忆 ID", required = true) @PathVariable String id) {
        return ownershipGuard.currentContext()
                .map(ctx -> {
                    memoryService.delete(ctx.userId(), id);
                    return Result.<Void>success();
                });
    }

    @Operation(summary = "清空全部记忆", description = "软删除当前用户的全部活跃记忆, 返回被清空的条数")
    @ApiResponse(responseCode = "200", description = "清空成功, data 为被清空的条数")
    @DeleteMapping
    public Mono<Result<Integer>> clear() {
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(memoryService.clearAll(ctx.userId())));
    }
}
