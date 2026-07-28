package com.dcdr.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 对话内文生图请求(按钮触发)
 */
@Data
@Schema(description = "对话内文生图请求（按钮触发）")
public class ChatImageRequest {

    @Schema(description = "会话 ID, 留空则自动新建会话")
    private String sessionId;

    @Schema(description = "文生图提示词", requiredMode = Schema.RequiredMode.REQUIRED)
    private String prompt;

    @Schema(description = "尺寸, 如 1024x1024 / 1024x1792 / 1792x1024")
    private String size;

    @Schema(description = "风格: vivid(生动)/natural(自然)/anime(动漫)/oil(油画)/watercolor(水彩)")
    private String style;

    @Schema(description = "生成数量, 默认 1", defaultValue = "1")
    private Integer n = 1;
}
