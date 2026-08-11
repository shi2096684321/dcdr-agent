package com.dcdr.agent.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息实体 - 对话历史记录
 */
@Data
@Entity
@Schema(description = "消息实体 - 对话历史记录")
@Table(name = "messages", indexes = {
        @Index(name = "idx_messages_session", columnList = "sessionId"),
        @Index(name = "idx_messages_seq", columnList = "sessionId,seq")
})
public class MessageEntity {

    @Schema(description = "主键 ID (自增)")
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Schema(description = "所属会话 ID")
    @Column(nullable = false, length = 64)
    private String sessionId;

    @Schema(description = "会话内自增序号，用于保持消息顺序")
    @Column(nullable = false)
    private int seq;

    @Schema(description = "角色: user / assistant / system")
    @Column(nullable = false, length = 16)
    private String role;

    @Schema(description = "消息类型: text / image / video")
    @Column(nullable = false, length = 16)
    private String type;

    @Schema(description = "消息内容")
    @Column(columnDefinition = "TEXT")
    private String content;

    @Schema(description = "思维链/推理过程内容 (深度思考模型返回)")
    @Column(columnDefinition = "TEXT")
    private String reasoningContent;

    @Schema(description = "用户上传的图片URL列表, 以 JSON 数组字符串存储(多模态)")
    @Column(columnDefinition = "TEXT")
    private String images;

    @Schema(description = "做图片识别的视觉模型名(降级场景)")
    @Column(length = 64)
    private String visionModel;

    @Schema(description = "图片识别状态: none / recognized / failed")
    @Column(length = 16)
    private String imageStatus;

    @Schema(description = "图片识别结果原文(结构化存储)")
    @Column(columnDefinition = "TEXT")
    private String imageCaption;

    @Schema(description = "图片识别消耗的 token(JSON 字符串)")
    @Column(length = 128)
    private String imageTokenUsage;

    @Schema(description = "使用的模型(assistant 消息)")
    @Column(length = 64)
    private String model;

    @Schema(description = "使用的工具(JSON 字符串)")
    @Column(length = 2000)
    private String toolsUsed;

    @Schema(description = "工具调用明细(JSON 数组: id/name/arguments), 用于持久化 agentic 工具往返")
    @Column(columnDefinition = "TEXT")
    private String toolCallsJson;

    @Schema(description = "tool 角色消息对应的 tool_call id")
    @Column(length = 64)
    private String toolCallId;

    @Schema(description = "tool 角色消息对应的工具名称")
    @Column(length = 64)
    private String toolName;

    @Schema(description = "引用来源(联网搜索等结果, JSON 字符串)")
    @Column(columnDefinition = "TEXT")
    private String sources;

    @Schema(description = "本次消息消耗的 token 数量")
    private Integer tokenUsage;

    @Schema(description = "是否为失败(错误)消息, 用于前端展示重试入口")
    @Column
    private Boolean error;

    @Schema(description = "是否被用户手动中断(产生不完整的回复)")
    @Column
    private Boolean interrupted;

    @Schema(description = "创建时间")
    @Column(nullable = false)
    private LocalDateTime createdAt;
}
