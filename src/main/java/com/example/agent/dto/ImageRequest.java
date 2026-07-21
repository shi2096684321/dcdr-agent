package com.example.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 图片生成请求(创作模式)
 */
@Data
@Schema(description = "图片生成请求（创作模式）")
public class ImageRequest {

    /** 项目ID(可选) */
    @Schema(description = "项目 ID（可选）")
    private String projectId;
    /** 模型名称 */
    @Schema(description = "模型名称", example = "openai-image", requiredMode = Schema.RequiredMode.REQUIRED)
    private String model;
    /** 提示词 */
    @Schema(description = "提示词", example = "一只可爱的橘猫", requiredMode = Schema.RequiredMode.REQUIRED)
    private String prompt;
    /** 参考图(base64或URL, 可选) */
    @Schema(description = "参考图（base64 或 URL，可选）")
    private String referImage;
    /** 引擎类型: auto/stable/flux/midjourney */
    @Schema(description = "引擎类型", allowableValues = {"auto", "stable", "flux", "midjourney"}, defaultValue = "auto")
    private String engineType = "auto";
    /** 尺寸, 如 1024x1024 */
    @Schema(description = "尺寸", example = "1024x1024", defaultValue = "1024x1024")
    private String size = "1024x1024";
    /** 比例: auto/1:1/3:4/4:3/9:16/16:9 */
    @Schema(description = "比例", allowableValues = {"auto", "1:1", "3:4", "4:3", "9:16", "16:9"}, defaultValue = "auto")
    private String aspectRatio = "auto";
    /** 风格: vivid/natural/anime/oil/watercolor */
    @Schema(description = "风格", allowableValues = {"vivid", "natural", "anime", "oil", "watercolor"}, defaultValue = "vivid")
    private String style = "vivid";
    /** 风险保障开关 */
    @Schema(description = "安全模式开关", defaultValue = "true")
    private boolean safeMode = true;
}
