package com.dcdr.agent.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话实体 (对话模式)
 */
@Data
@Entity
@Table(name = "sessions")
public class SessionEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false)
    private String title;

    /** 标题是否为自动默认标题（首个用户问题到达后会被替换为问题内容并置为 false） */
    @Column(nullable = false, columnDefinition = "boolean default true")
    private Boolean titleAuto = true;

    private String model;

    @Column(columnDefinition = "TEXT")
    private String systemPrompt;

    /** 所属用户 ID (V2.0 新增, V2.2 用户认证隔离使用; 暂为可空以兼容存量数据) */
    @Column(length = 64)
    private String userId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
