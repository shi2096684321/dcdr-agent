package com.dcdr.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话 DTO (对话模式)
 */
@Data
@Schema(description = "会话信息（对话模式）")
public class SessionDTO {

    @Schema(description = "会话 ID")
    private String id;
    @Schema(description = "标题")
    private String title;
    @Schema(description = "标题是否为自动默认标题（首个用户问题到达后自动更新）")
    private boolean titleAuto;
    @Schema(description = "默认模型")
    private String model;
    @Schema(description = "会话角色设定(用户人设/偏好, 由平台包装后转述给模型, 非系统提示词)")
    private String persona;
    @Schema(description = "本会话使用的自定义 Agent ID (V3.1; 为空表示通用助手)。用于 Agent 级长期记忆归属")
    private String agentId;
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    /** 消息列表(查询历史时返回) */
    @Schema(description = "消息列表（查询历史时返回）")
    private List<MessageDTO> messages = new ArrayList<>();
}
