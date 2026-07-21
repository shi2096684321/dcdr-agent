package com.example.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 图片生成响应(创作模式)
 */
@Data
@Schema(description = "图片生成响应（创作模式）")
public class ImageResponse {

    @Schema(description = "生成记录 ID")
    private String id;
    @Schema(description = "项目 ID")
    private String projectId;
    @Schema(description = "图片 URL")
    private String imageUrl;
    @Schema(description = "缩略图 URL")
    private String thumbnailUrl;
    @Schema(description = "提示词")
    private String prompt;
    @Schema(description = "使用的模型")
    private String model;
    @Schema(description = "生成参数")
    private Map<String, Object> params;
    @Schema(description = "消耗积分")
    private int costCredits;
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
