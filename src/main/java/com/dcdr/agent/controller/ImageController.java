package com.dcdr.agent.controller;

import com.dcdr.agent.agent.creative.CreativeEngine;
import com.dcdr.agent.common.Result;
import com.dcdr.agent.dto.GenerationDTO;
import com.dcdr.agent.dto.ImageRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/images")
@RequiredArgsConstructor
@Tag(name = "图片生成", description = "创作模式 - 图片生成接口")
public class ImageController {
    private final CreativeEngine creativeEngine;

    @Operation(summary = "提交图片生成任务",
            description = "提交后立即返回 pending 状态的生成记录, 前端凭记录 ID 轮询 /api/generations/{id} 获取最终结果")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = GenerationDTO.class)))
    @PostMapping("/generate")
    public Result<GenerationDTO> generate(@RequestBody ImageRequest request) {
        log.info("图片生成任务提交: model={}", request.getModel());
        return Result.success(creativeEngine.generateImage(request));
    }
}
