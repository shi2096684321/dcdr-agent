package com.dcdr.agent.dto;

import com.dcdr.agent.common.constant.ModelType;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 模型信息 DTO - 返回给前端的模型描述
 */
@Data
@Schema(description = "模型信息")
public class ModelInfo {

    /** 模型唯一标识 */
    @Schema(description = "模型唯一标识", example = "openai-chat")
    private String name;
    /** 模型类型 */
    @Schema(description = "模型类型", allowableValues = {"CHAT", "IMAGE", "VIDEO", "AUDIO"}, example = "CHAT")
    private ModelType type;
    /** 提供商 */
    @Schema(description = "提供商", example = "openai")
    private String provider;
    /** 模型名 */
    @Schema(description = "模型名", example = "gpt-4o")
    private String model;
    /** 显示标签 */
    @Schema(description = "显示标签", example = "GPT-4o")
    private String label;
    /** 描述 */
    @Schema(description = "描述")
    private String desc;
    /** 能力标签 */
    @Schema(description = "能力标签", example = "[\"vision\",\"function_calling\"]")
    private List<String> capabilities;
    /** 是否启用 */
    @Schema(description = "是否启用")
    private boolean enabled;

    /** 对应的UI模式: chat / creative */
    @Schema(description = "对应的 UI 模式", allowableValues = {"chat", "creative"}, example = "chat")
    private String uiMode;
    /** 是否为默认对话模型(后端配置 model.default-chat) */
    @Schema(description = "是否为默认对话模型", example = "false")
    @JsonProperty("isDefault")
    private boolean isDefault;
}
