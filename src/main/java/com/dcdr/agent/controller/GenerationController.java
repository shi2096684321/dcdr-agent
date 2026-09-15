package com.dcdr.agent.controller;

import com.dcdr.agent.agent.session.ProjectManager;
import com.dcdr.agent.common.Result;
import com.dcdr.agent.dto.GenerationDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 生成记录查询接口 - 供前端轮询异步生成任务的状态与结果。
 * <p>
 * V3.1 F-41: 归属校验用 {@code @PreAuthorize} + {@code OwnershipGuard}, 当前用户由 SpEL 变量
 * {@code principal} 提供, 方法无需声明 {@code ServerWebExchange} 等上下文参数;
 * 越权与不存在统一返回 404。
 */
@RestController
@RequestMapping("/api/generations")
@RequiredArgsConstructor
@Tag(name = "生成记录", description = "查询生成任务状态与结果")
public class GenerationController {

    private final ProjectManager projectManager;

    @Operation(summary = "查询生成记录", description = "根据生成记录 ID 查询状态(pending/completed/failed)与结果 URL")
    @PreAuthorize("@ownershipGuard.requireRead(#id, 'GENERATION', principal)")
    @GetMapping("/{id}")
    public Mono<Result<GenerationDTO>> get(@PathVariable String id) {
        return Mono.just(Result.success(projectManager.getGeneration(id)));
    }
}
