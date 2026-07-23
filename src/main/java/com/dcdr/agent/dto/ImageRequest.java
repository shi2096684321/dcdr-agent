package com.dcdr.agent.dto;

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
    /** 反向提示词(可选): 描述不希望出现的内容 */
    @Schema(description = "反向提示词（可选）")
    private String negativePrompt;
    /** 生成数量, 默认 1 (qwen-image-2.0 系列支持 1-6) */
    @Schema(description = "生成数量", example = "1", defaultValue = "1")
    private Integer n = 1;
    /** 是否开启提示词智能改写, 默认 true */
    @Schema(description = "提示词智能改写开关", defaultValue = "true")
    private Boolean promptExtend = true;
    /** 是否添加水印, 默认 false */
    @Schema(description = "水印开关", defaultValue = "false")
    private Boolean watermark = false;
    /** 随机种子, 取值范围 [0, 2147483647] */
    @Schema(description = "随机种子", example = "12345")
    private Integer seed;
    /** 风险保障开关 */
    @Schema(description = "安全模式开关", defaultValue = "true")
    private boolean safeMode = true;
}
