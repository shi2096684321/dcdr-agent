package com.dcdr.agent.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库实体 (V2.0 新增, 支撑 V2.1 RAG)
 */
@Data
@Entity
@Table(name = "knowledge_bases", indexes = {
        @Index(name = "idx_kb_user", columnList = "userId")
})
public class KnowledgeBaseEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(length = 64)
    private String userId;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, length = 128)
    private String embeddingModel = "text-embedding-v3";

    @Column(nullable = false)
    private int chunkSize = 1000;

    @Column(nullable = false)
    private int chunkOverlap = 200;

    @Column(nullable = false)
    private int docCount = 0;

    /** 状态: ready / indexing / error */
    @Column(nullable = false, length = 16)
    private String status = "ready";

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
