package com.example.agent.entity;

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

    @Column(length = 1024)
    private String imageUrl;

    /** 使用的模型(assistant 消息) */
    @Column(length = 64)
    private String model;

    /** 使用的工具(JSON 字符串) */
    @Column(length = 2000)
    private String toolsUsed;

    private Integer tokenUsage;

    @Column(nullable = false)
    private LocalDateTime createdAt;
}
