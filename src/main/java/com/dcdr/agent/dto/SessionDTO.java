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
    @Schema(description = "系统提示词")
    private String systemPrompt;
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    /** 消息列表(查询历史时返回) */
    @Schema(description = "消息列表（查询历史时返回）")
    private List<MessageDTO> messages = new ArrayList<>();
}
