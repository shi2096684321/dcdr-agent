package com.example.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 生成记录 DTO (创作模式)
 */
@Data
@Schema(description = "生成记录（创作模式）")
public class GenerationDTO {

    @Schema(description = "生成记录 ID")
    private String id;
    @Schema(description = "项目 ID")
    private String projectId;
    /** 类型: image / video */
    @Schema(description = "类型", allowableValues = {"image", "video"}, example = "image")
    private String type;
    @Schema(description = "提示词")
    private String prompt;
    @Schema(description = "参考图 URL")
    private String referImageUrl;
    @Schema(description = "结果 URL")
    private String resultUrl;
    @Schema(description = "缩略图 URL")
    private String thumbnailUrl;
    @Schema(description = "使用的模型")
    private String model;
    /** 生成参数JSON */
    @Schema(description = "生成参数（JSON 字符串）")
    private String paramsJson;
    @Schema(description = "消耗积分")
    private int costCredits;
    /** 状态: pending / completed / failed */
    @Schema(description = "状态", allowableValues = {"pending", "completed", "failed"}, example = "completed")
    private String status;
    @Schema(description = "错误信息")
    private String errorMessage;
    @Schema(description = "耗时（毫秒）")
    private long durationMs;
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
