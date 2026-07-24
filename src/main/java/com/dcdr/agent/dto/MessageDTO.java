package com.dcdr.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

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
    /** 会话内自增序号, 用于按 seq 截断历史(编辑/重试) */
    @Schema(description = "会话内序号（用于编辑/重试时截断历史）")
    private Integer seq;
    /** 是否为失败(错误)消息 */
    @Schema(description = "是否为失败消息（用于前端重试）")
    private Boolean error;
    /** 是否被用户手动中断(产生不完整的回复) */
    @Schema(description = "是否被用户手动中断（生成不完整）")
    private Boolean interrupted;
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
    /** 用户上传的图片URL列表(多模态) */
    @Schema(description = "用户上传的图片 URL 列表（多模态，user 消息）")
    private List<String> images;
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
