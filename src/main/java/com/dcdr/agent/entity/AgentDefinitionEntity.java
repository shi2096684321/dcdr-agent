package com.dcdr.agent.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 智能体定义实体 (V2.0 新增, V2.2 自定义 Agent)
 */
@Data
@Entity
@Table(name = "agent_definitions", indexes = {
        @Index(name = "idx_agent_user", columnList = "userId")
})
public class AgentDefinitionEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(length = 64)
    private String userId;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String systemPrompt;

    @Column(length = 128)
    private String model;

    /** 启用的工具列表, JSON 字符串 */
    @Column(columnDefinition = "TEXT")
    private String tools;

    /** 关联的知识库 ID 列表, JSON 字符串 */
    @Column(columnDefinition = "TEXT")
    private String kbIds;

    @Column(nullable = false)
    private boolean isPublic = false;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
