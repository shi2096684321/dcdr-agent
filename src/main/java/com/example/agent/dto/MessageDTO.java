package com.example.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息 DTO
 */
@Data
@Schema(description = "消息记录")
public class MessageDTO {

    @Schema(description = "消息 ID")
    private String id;
    @Schema(description = "所属会话 ID")
    private String sessionId;
    /** 角色: user / assistant / system */
    @Schema(description = "角色", allowableValues = {"user", "assistant", "system"}, example = "user")
    private String role;
    /** 消息类型: text / image / video */
    @Schema(description = "消息类型", allowableValues = {"text", "image", "video"}, example = "text")
    private String type;
    /** 文本内容 */
    @Schema(description = "文本内容")
    private String content;
    /** 推理内容(思维链, assistant消息) */
    @Schema(description = "推理内容（思维链，assistant 消息）")
    private String reasoningContent;
    /** 图片URL(type=image时) */
    @Schema(description = "图片 URL（type=image 时）")
    private String imageUrl;
    /** 使用的模型 */
    @Schema(description = "使用的模型（assistant 消息）")
    private String model;
    /** 使用的工具(JSON) */
    @Schema(description = "使用的工具（JSON 字符串）")
    private String toolsUsed;
    /** Token消耗 */
    @Schema(description = "Token 消耗")
    private Integer tokenUsage;
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
