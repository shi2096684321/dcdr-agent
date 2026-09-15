package com.dcdr.agent.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档实体 (V2.0 新增, 支撑 V2.1 RAG 入库)
 */
@Data
@Entity
@EntityListeners(OwnershipFillListener.class)
@Schema(description = "文档实体 (V2.0 新增, 支撑 V2.1 RAG 入库)")
@Table(name = "documents", indexes = {
        @Index(name = "idx_doc_kb", columnList = "kbId"),
        @Index(name = "idx_doc_user", columnList = "userId")
})
public class DocumentEntity implements OwnedEntity {

    @Schema(description = "主键 ID (字符串型 UUID)")
    @Id
    @Column(length = 64)
    private String id;

    @Schema(description = "所属知识库 ID")
    @Column(nullable = false, length = 64)
    private String kbId;

    @Schema(description = "上传用户 ID")
    @Column(length = 64)
    private String userId;

    @Schema(description = "文件名")
    @Column(nullable = false)
    private String filename;

    @Schema(description = "文件类型 (扩展名, 如 pdf/txt/md)")
    @Column(length = 32)
    private String fileType;

    @Schema(description = "文件大小 (字节)")
    private Long fileSize;

    @Schema(description = "文档分块数量", defaultValue = "0")
    @Column(nullable = false)
    private int chunkCount = 0;

    @Schema(description = "状态: pending / indexing / completed / failed", defaultValue = "pending")
    @Column(nullable = false, length = 16)
    private String status = "pending";

    @Schema(description = "失败时的错误信息")
    @Column(columnDefinition = "TEXT")
    private String errorMsg;

    @Schema(description = "创建时间")
    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Schema(description = "最后更新时间")
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
