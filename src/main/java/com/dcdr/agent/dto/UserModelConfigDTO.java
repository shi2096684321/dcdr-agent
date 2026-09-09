package com.dcdr.agent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户自定义模型配置 DTO (V3.1 新增)。
 * <p>
 * 安全约定: {@code apiKey} 仅写入时使用, 任何响应中一律返回 null, 用 {@code hasApiKey}
 * 表示是否已配置, 避免明文外泄。
 */
@Data
@Schema(description = "用户自定义模型配置")
public class UserModelConfigDTO {

    @Schema(description = "配置 ID (仅响应)")
    private String id;

    @Schema(description = "展示名称", example = "我的 DeepSeek")
    private String name;

    @Schema(description = "厂商/类型标识", example = "openai-compatible")
    private String provider;

    @Schema(description = "OpenAI 兼容端点 base-url", example = "https://api.deepseek.com")
    private String baseUrl;

    @Schema(description = "模型名", example = "deepseek-chat")
    private String model;

    @Schema(description = "API Key (仅写入时有效, 响应恒为 null)", example = "sk-xxx")
    private String apiKey;

    @Schema(description = "是否已配置 apiKey (仅响应)")
    @JsonProperty("hasApiKey")
    private boolean hasApiKey;

    @Schema(description = "采样温度", example = "0.7")
    private Double temperature;

    @Schema(description = "最大输出 token", example = "2000")
    private Integer maxTokens;

    @Schema(description = "是否启用", defaultValue = "true")
    private boolean enabled = true;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "最后更新时间")
    private LocalDateTime updatedAt;
}