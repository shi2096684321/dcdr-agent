package com.dcdr.agent.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息实体 - 对话历史记录
 */
@Data
@Entity
@Table(name = "messages", indexes = {
        @Index(name = "idx_messages_session", columnList = "sessionId"),
        @Index(name = "idx_messages_seq", columnList = "sessionId,seq")
})
public class MessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属会话 ID */
    @Column(nullable = false, length = 64)
    private String sessionId;

    /** 会话内自增序号，用于保持消息顺序 */
    @Column(nullable = false)
    private int seq;

    /** 角色: user / assistant / system */
    @Column(nullable = false, length = 16)
    private String role;

    /** 消息类型: text / image / video */
    @Column(nullable = false, length = 16)
    private String type;

    @Column(length = 8000)
    private String content;


    @Column(length = 8000)
    private String reasoningContent;

    /** 用户上传的图片URL列表, 以 JSON 数组字符串存储(多模态) */
    @Column(length = 8000)
    private String images;

    /** 做图片识别的视觉模型名(降级场景) */
    @Column(length = 64)
    private String visionModel;

    /** 图片识别状态: none / recognized / failed */
    @Column(length = 16)
    private String imageStatus;

    /** 图片识别结果原文(结构化存储) */
    @Column(columnDefinition = "TEXT")
    private String imageCaption;

    /** 图片识别消耗的 token(JSON 字符串) */
    @Column(length = 128)
    private String imageTokenUsage;

    /** 使用的模型(assistant 消息) */
    @Column(length = 64)
    private String model;

    /** 使用的工具(JSON 字符串) */
    @Column(length = 2000)
    private String toolsUsed;

    private Integer tokenUsage;

    /** 是否为失败(错误)消息, 用于前端展示重试入口 */
    @Column
    private Boolean error;
    /** 是否被用户手动中断(产生不完整的回复) */
    @Column
    private Boolean interrupted;

    @Column(nullable = false)
    private LocalDateTime createdAt;
}
