package com.dcdr.agent.controller;

import com.dcdr.agent.agent.creative.CreativeEngine;
import com.dcdr.agent.agent.session.ProjectManager;
import com.dcdr.agent.common.Result;
import com.dcdr.agent.dto.GenerationDTO;
import com.dcdr.agent.dto.ImageRequest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * 图片生成接口 (创作模式)。
 * <p>
 * V2.3 新增 F-31 流式图片生成: {@code GET /api/images/stream/{id}} 通过 SSE 推送生成进度,
 * 前端无需轮询, 实时展示 pending → processing → completed/failed 全过程。
 */
@Slf4j
@RestController
@RequestMapping("/api/images")
@RequiredArgsConstructor
@Tag(name = "图片生成", description = "创作模式 - 图片生成接口 (V2.3 新增流式进度 SSE)")
public class ImageController {
    private final CreativeEngine creativeEngine;
    private final ProjectManager projectManager;

    /** 进度轮询间隔 (ms) */
    private static final long POLL_INTERVAL_MS = 500;
    /** 流式超时上限 (ms), 图片生成通常 5~30s, 预留 5min 兜底 */
    private static final long STREAM_TIMEOUT_MS = 5 * 60 * 1000;
    /** 用于进度估算的预期生成耗时 (ms), 超过后进度停留在 95% 直到真正完成 */
    private static final long EXPECTED_DURATION_MS = 15_000;

    @Operation(summary = "提交图片生成任务",
            description = "提交后立即返回 pending 状态的生成记录(n>1 时返回 n 条相互独立记录)。"
                    + "V2.3: 前端可改用 GET /api/images/stream/{id} (SSE) 实时接收进度, 无需轮询。")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = GenerationDTO.class)))
    @PostMapping("/generate")
    public Result<List<GenerationDTO>> generate(@RequestBody ImageRequest request) {
        log.info("图片生成任务提交: model={}", request.getModel());
        return Result.success(creativeEngine.generateImage(request));
    }

    /**
     * V2.3 F-31 流式图片生成进度 (SSE)。
     * <p>
     * 前端在调用 POST /api/images/generate 拿到记录 ID 后, 连接本端点即可实时接收进度,
     * 无需轮询 GET /api/generations/{id}。服务端每 {@value POLL_INTERVAL_MS}ms 推送一次进度事件,
     * 直到任务进入终态 (completed / failed) 或超时 ({@value STREAM_TIMEOUT_MS}ms)。
     * <p>
     * 事件类型 (event 字段):
     * <ul>
     *   <li>{@code progress}: 生成中, data 含 status / progress(0~100) / elapsedMs / record</li>
     *   <li>{@code completed}: 生成成功, data 含完整 GenerationDTO (含 resultUrl)</li>
     *   <li>{@code failed}: 生成失败, data 含 errorMessage</li>
     * </ul>
     */
    @Operation(
            summary = "流式图片生成进度 (SSE, F-31)",
            description = "通过 Server-Sent Events 实时推送图片生成进度, 替代轮询。"
                    + "事件 event: progress / completed / failed; data 为 JSON 对象。",
            responses = {
                    @ApiResponse(responseCode = "200", description = "SSE 流, 逐条推送进度事件",
                            content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE))
            }
    )
    @GetMapping(value = "/stream/{id}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> streamProgress(@PathVariable String id) {
        log.info("F-31 流式图片生成进度: id={}", id);
        long startTime = System.currentTimeMillis();
        return Flux.interval(java.time.Duration.ofMillis(POLL_INTERVAL_MS), reactor.core.scheduler.Schedulers.boundedElastic())
                .mapNotNull(tick -> {
                    long elapsed = System.currentTimeMillis() - startTime;
                    GenerationDTO record = projectManager.getGeneration(id);
                    if (record == null) {
                        // 记录不存在, 推送错误并结束
                        Map<String, Object> err = new HashMap<>();
                        err.put("id", id);
                        err.put("errorMessage", "生成记录不存在: " + id);
                        return ServerSentEvent.<Object>builder().event("failed").data(err).build();
                    }
                    String status = record.getStatus();
                    // 推送进度事件
                    if ("completed".equals(status)) {
                        Map<String, Object> data = buildProgressData(record, elapsed, 100);
                        return ServerSentEvent.<Object>builder().event("completed").data(data).build();
                    }
                    if ("failed".equals(status)) {
                        Map<String, Object> data = buildProgressData(record, elapsed, null);
                        return ServerSentEvent.<Object>builder().event("failed").data(data).build();
                    }
                    // pending / processing: 推送进度估算
                    int progress = estimateProgress(status, elapsed);
                    Map<String, Object> data = buildProgressData(record, elapsed, progress);
                    return ServerSentEvent.<Object>builder().event("progress").data(data).build();
                })
                // 终态事件 (completed/failed) 后结束流
                .takeUntil(sse -> "completed".equals(sse.event()) || "failed".equals(sse.event()))
                // 超时兜底: 超过 STREAM_TIMEOUT_MS 强制结束
                .timeout(java.time.Duration.ofMillis(STREAM_TIMEOUT_MS))
                .onErrorResume(e -> {
                    log.warn("F-31 流式进度超时或异常: id={}, err={}", id, e.getMessage());
                    Map<String, Object> err = new HashMap<>();
                    err.put("id", id);
                    err.put("errorMessage", "流式进度超时");
                    return Flux.just(ServerSentEvent.<Object>builder().event("failed").data(err).build());
                });
    }

    /** 构建进度事件 data (含完整 record 供前端展示) */
    private Map<String, Object> buildProgressData(GenerationDTO record, long elapsedMs, Integer progress) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", record.getId());
        data.put("status", record.getStatus());
        data.put("elapsedMs", elapsedMs);
        if (progress != null) data.put("progress", progress);
        if (record.getResultUrl() != null) data.put("resultUrl", record.getResultUrl());
        if (record.getThumbnailUrl() != null) data.put("thumbnailUrl", record.getThumbnailUrl());
        if (record.getErrorMessage() != null) data.put("errorMessage", record.getErrorMessage());
        data.put("record", record);
        return data;
    }

    /**
     * 基于已耗时和状态估算进度百分比 (0~95, 终态由调用方置 100)。
     * pending 阶段 0~10%, processing 阶段 10~95%, 到达预期耗时后停在 95% 等真正完成。
     */
    private int estimateProgress(String status, long elapsedMs) {
        if (elapsedMs >= EXPECTED_DURATION_MS) return 95;
        double ratio = (double) elapsedMs / EXPECTED_DURATION_MS;
        if ("pending".equals(status)) {
            return (int) Math.round(ratio * 10);
        }
        // processing
        return (int) Math.round(10 + ratio * 85);
    }
}
