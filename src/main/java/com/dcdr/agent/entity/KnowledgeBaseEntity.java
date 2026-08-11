package com.dcdr.agent.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库实体 (V2.0 新增, 支撑 V2.1 RAG)
 */
@Data
@Entity
@Schema(description = "知识库实体 (V2.0 新增, 支撑 V2.1 RAG)")
@Table(name = "knowledge_bases", indexes = {
        @Index(name = "idx_kb_user", columnList = "userId")
})
public class KnowledgeBaseEntity {

    @Schema(description = "主键 ID (字符串型 UUID)")
    @Id
    @Column(length = 64)
    private String id;

    @Schema(description = "所属用户 ID")
    @Column(length = 64)
    private String userId;

    @Schema(description = "知识库名称")
    @Column(nullable = false)
    private String name;

    @Schema(description = "知识库描述")
    @Column(columnDefinition = "TEXT")
    private String description;

    @Schema(description = "向量化使用的嵌入模型名称", defaultValue = "text-embedding-v3")
    @Column(nullable = false, length = 128)
    private String embeddingModel = "text-embedding-v3";

    @Schema(description = "文档分块大小 (字符数)", defaultValue = "1000")
    @Column(nullable = false)
    private int chunkSize = 1000;

    @Schema(description = "分块重叠大小 (字符数, 用于保留上下文连续性)", defaultValue = "200")
    @Column(nullable = false)
    private int chunkOverlap = 200;

    @Schema(description = "知识库内文档数量", defaultValue = "0")
    @Column(nullable = false)
    private int docCount = 0;

    @Schema(description = "状态: ready / indexing / error", defaultValue = "ready")
    @Column(nullable = false, length = 16)
    private String status = "ready";

    /**
     * 可见性: USER(用户级, 仅创建者可见) / PLATFORM(平台级, 所有对话无感可用)。
     * <p>平台级知识库由管理员维护, 用户在前端选择器看不到也选不了,
     * 但对话时自动加入 Agentic RAG 检索范围(通过 agent.rag.platform-kb-ids 配置)。
     */
    @Schema(description = "可见性: USER(用户级) / PLATFORM(平台级)", defaultValue = "USER")
    @Column(nullable = false, length = 16)
    private String visibility = "USER";

    @Schema(description = "创建时间")
    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Schema(description = "最后更新时间")
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
