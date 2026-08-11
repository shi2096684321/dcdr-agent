package com.dcdr.agent.entity;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "文档分块 + 向量实体 (V2.0 新增, RAG 向量检索核心)")
@Table(name = "document_chunks", indexes = {
        @Index(name = "idx_chunk_doc", columnList = "docId"),
        @Index(name = "idx_chunk_kb", columnList = "kbId")
})
public class DocumentChunkEntity {

    @Schema(description = "主键 ID (字符串型 UUID)")
    @Id
    @Column(length = 64)
    private String id;

    @Schema(description = "所属文档 ID")
    @Column(nullable = false, length = 64)
    private String docId;

    @Schema(description = "所属知识库 ID")
    @Column(length = 64)
    private String kbId;

    @Schema(description = "所属用户 ID")
    @Column(length = 64)
    private String userId;

    @Schema(description = "分块在文档内的序号, 用于保持顺序")
    @Column(nullable = false)
    private int chunkIndex;

    @Schema(description = "分块文本内容")
    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Schema(description = "向量嵌入, 对应数据库 VECTOR(1536); 经 VectorType 与 float[] 互转")
    @Type(VectorType.class)
    @Column(columnDefinition = "vector(1536)")
    private float[] embedding;

    @Schema(description = "分块 token 数量")
    private Integer tokenCount;

    @Schema(description = "创建时间")
    @Column(nullable = false)
    private LocalDateTime createdAt;
}
