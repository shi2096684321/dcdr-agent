package com.dcdr.agent.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RAG 检索单条结果 (V2.1)。
 * <p>
 * 由 {@code RetrievalService.retrieve} 返回, Phase 3 注入对话时也用此结构拼 prompt。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalResult {

    /** 命中的文档分块 ID */
    private String chunkId;

    /** 分块所属文档 ID */
    private String documentId;

    /** 分块所属知识库 ID */
    private String kbId;

    /** 块序号(同一文档内) */
    private int chunkIndex;

    /** 命中的文本内容 */
    private String content;

    /** 余弦相似度 (0~1, 越大越相似) */
    private double score;

    /** 来源文件名(便于前端展示引用来源) */
    private String filename;
}
