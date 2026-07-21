package com.example.agent.controller;

import com.example.agent.agent.creative.CreativeEngine;
import com.example.agent.common.Result;
import com.example.agent.dto.ImageRequest;
import com.example.agent.dto.ImageResponse;
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

    @Operation(summary = "生成图片", description = "根据提示词生成图片，支持指定模型、尺寸、风格等参数")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ImageResponse.class)))
    @PostMapping("/generate")
    public Result<ImageResponse> generate(@RequestBody ImageRequest request) {
        log.info("图片生成: model={}", request.getModel());
        return Result.success(creativeEngine.generateImage(request));
    }
}
