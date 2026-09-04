package com.dcdr.agent.rag.service;

import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.rag.config.RagProperties;
import com.dcdr.agent.rag.dto.RetrievalResult;
import com.dcdr.agent.rag.retrieval.KnowledgeBaseContentRetriever;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 检索增强 Service (V2.1, 对应 F-20-004 / F-21-003)。
 * <p>
 * V3.2 框架化(从手写 pgvector 迁移到 LangChain4j 原生检索):
 * 本 Service 不再手写向量 SQL, 而是把检索委托给框架 {@link KnowledgeBaseContentRetriever}
 * (内含 {@code EmbeddingStore} 适配器 + 可选 BM25/RRF 混合 + 可选 Rerank)。
 * 默认(hybrid/rerank 关闭)下检索语义与迁移前完全一致: 逐库 KNN → similarity=1-distance →
 * minScore 过滤 → topK 截断 → 补全文件名。
 * <p>
 * 同时支持单知识库与多知识库检索(对话集成时 ChatRequest.knowledgeBaseIds 为列表)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalService {

    private final KnowledgeBaseContentRetriever contentRetriever;
    private final RagProperties properties;

    /**
     * 单知识库检索。
     *
     * @param kbId  知识库 ID
     * @param query 用户问题
     * @param topK  返回条数, null 时用配置默认值
     * @return 命中片段列表(按相似度降序), 已过滤 minScore
     */
    public List<RetrievalResult> retrieve(String kbId, String query, Integer topK) {
        return retrieve(List.of(kbId), query, topK);
    }

    /**
     * 多知识库检索(对话集成用)。
     *
     * @param kbIds 知识库 ID 列表(空则返回空)
     * @param query 用户问题
     * @param topK  返回条数, null 时用配置默认值
     * @return 合并后按相似度降序的 Top-K 片段
     */
    public List<RetrievalResult> retrieve(List<String> kbIds, String query, Integer topK) {
        if (!properties.isEnabled()) {
            throw new BusinessException(503, "RAG 功能未启用", "RAG_DISABLED");
        }
        if (kbIds == null || kbIds.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }
        List<Content> contents = contentRetriever.retrieve(kbIds, topK, query);
        List<RetrievalResult> results = new ArrayList<>(contents.size());
        for (Content c : contents) {
            results.add(toRetrievalResult(c));
        }
        log.info("RAG 检索: kbIds={}, query='{}', 返回 {} 条", kbIds, clip(query), results.size());
        return results;
    }

    /** 把框架 {@link Content} 还原为业务 {@link RetrievalResult}(metadata 键由适配器写入) */
    private RetrievalResult toRetrievalResult(Content content) {
        TextSegment segment = content.textSegment();
        String text = segment.text();
        Metadata meta = segment.metadata();
        Integer chunkIndex = meta.getInteger("chunkIndex");
        Double score = meta.getDouble("score");
        return new RetrievalResult(
                meta.getString("chunkId"),
                meta.getString("documentId"),
                meta.getString("kbId"),
                chunkIndex != null ? chunkIndex : 0,
                text,
                score != null ? score : 0.0,
                meta.getString("filename"));
    }

    private static String clip(String s) {
        if (s == null) return "";
        return s.length() <= 50 ? s : s.substring(0, 50) + "…";
    }
}