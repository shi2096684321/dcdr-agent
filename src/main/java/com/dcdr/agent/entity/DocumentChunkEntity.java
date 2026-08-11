package com.dcdr.agent.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;

/**
 * 文档分块 + 向量实体 (V2.0 新增, RAG 向量检索核心)
 * embedding 对应数据库 VECTOR(1536), 经 VectorType 与 Java float[] 互转。
 */
@Data
@Entity
@Table(name = "document_chunks", indexes = {
        @Index(name = "idx_chunk_doc", columnList = "docId"),
        @Index(name = "idx_chunk_kb", columnList = "kbId")
})
public class DocumentChunkEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 64)
    private String docId;

    @Column(length = 64)
    private String kbId;

    @Column(length = 64)
    private String userId;

    @Column(nullable = false)
    private int chunkIndex;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    /** 向量嵌入, 对应数据库 VECTOR(1536); 经 VectorType 与 float[] 互转 */
    @Type(VectorType.class)
    @Column(columnDefinition = "vector(1536)")
    private float[] embedding;

    private Integer tokenCount;

    @Column(nullable = false)
    private LocalDateTime createdAt;
}
