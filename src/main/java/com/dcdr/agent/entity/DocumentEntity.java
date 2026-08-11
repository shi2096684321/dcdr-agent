package com.dcdr.agent.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档实体 (V2.0 新增, 支撑 V2.1 RAG 入库)
 */
@Data
@Entity
@Table(name = "documents", indexes = {
        @Index(name = "idx_doc_kb", columnList = "kbId"),
        @Index(name = "idx_doc_user", columnList = "userId")
})
public class DocumentEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 64)
    private String kbId;

    @Column(length = 64)
    private String userId;

    @Column(nullable = false)
    private String filename;

    @Column(length = 32)
    private String fileType;

    private Long fileSize;

    @Column(nullable = false)
    private int chunkCount = 0;

    /** 状态: pending / indexing / completed / failed */
    @Column(nullable = false, length = 16)
    private String status = "pending";

    @Column(columnDefinition = "TEXT")
    private String errorMsg;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
