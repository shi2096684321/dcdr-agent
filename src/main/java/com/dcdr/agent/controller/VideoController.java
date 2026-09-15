package com.dcdr.agent.controller;

import com.dcdr.agent.agent.creative.CreativeEngine;
import com.dcdr.agent.agent.session.ProjectManager;
import com.dcdr.agent.auth.ownership.OwnershipGuard;
import com.dcdr.agent.common.Result;
import com.dcdr.agent.dto.GenerationDTO;
import com.dcdr.agent.dto.VideoRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/**
 * 视频生成接口 (V1.1)
 * <p>
 * - POST /api/videos/generate     提交文生视频任务(异步, 立即返回 pending 记录)
 * - GET  /api/videos/{id}/status  轮询任务状态与结果
 * <p>
 * 视频生成耗时较长(30s~数分钟), 采用异步任务 + 轮询模式:
 * 提交时立即创建 pending 记录返回, 真正的模型调用在后台线程池执行,
 * 完成后回写状态。前端凭记录 ID 轮询状态。
 * <p>
 * V3.1 F-41: 状态查询用 {@code @PreAuthorize} 声明式校验归属; 提交时需要 userId 写入归属,
 * 由 {@link OwnershipGuard#currentContext()} 响应式提供 —— 均无需 {@code ServerWebExchange} 参数。
 */
@Slf4j
@RestController
@RequestMapping("/api/videos")
@RequiredArgsConstructor
@Tag(name = "视频生成", description = "创作模式 - 文生视频接口(异步任务)")
public class VideoController {

    private final CreativeEngine creativeEngine;
    private final ProjectManager projectManager;
    /** V3.1 F-41: 响应式读取当前登录用户 */
    private final OwnershipGuard ownershipGuard;

    @Operation(summary = "提交视频生成任务",
            description = "异步提交, 立即返回 pending 状态的生成记录, 前端凭记录 ID 轮询 /api/videos/{id}/status 获取最终结果")
    @PostMapping("/generate")
    public Mono<Result<GenerationDTO>> generate(@RequestBody VideoRequest request) {
        log.info("视频生成任务提交: model={}", request.getModel());
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(creativeEngine.submitVideo(request, ctx.userId())));
    }

    @Operation(summary = "查询视频任务状态",
            description = "轮询任务状态(pending/processing/completed/failed)与结果 videoUrl")
    @PreAuthorize("@ownershipGuard.requireRead(#id, 'GENERATION', principal)")
    @GetMapping("/{id}/status")
    public Mono<Result<GenerationDTO>> status(@PathVariable String id) {
        return Mono.just(Result.success(projectManager.getGeneration(id)));
    }
}
