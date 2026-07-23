package com.dcdr.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 创作项目 DTO (创作模式)
 */
@Data
@Schema(description = "创作项目信息（创作模式）")
public class ProjectDTO {

    @Schema(description = "项目 ID")
    private String id;
    @Schema(description = "项目名称")
    private String name;
    /** 子模式: image / video */
    @Schema(description = "子模式", allowableValues = {"image", "video"}, example = "image")
    private String mode;
    @Schema(description = "默认模型")
    private String defaultModel;
    @Schema(description = "生成记录数")
    private int generationCount;
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
