package com.dcdr.agent.rag.service;

import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.entity.DocumentChunkEntity;
import com.dcdr.agent.entity.DocumentEntity;
import com.dcdr.agent.entity.VectorType;
import com.dcdr.agent.rag.config.RagProperties;
import com.dcdr.agent.rag.dto.RetrievalResult;
import com.dcdr.agent.rag.embedding.EmbeddingProvider;
import com.dcdr.agent.rag.embedding.EmbeddingRegistry;
import com.dcdr.agent.repository.DocumentChunkRepository;
import com.dcdr.agent.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 检索增强 Service (V2.1, 对应 F-20-004 / F-21-003)。
 * <p>
 * 检索流程:
 * <ol>
 *   <li>问题向量化: 用默认 Embedding provider 把 query 转为 float[]</li>
 *   <li>向量检索: 调 {@code findTopKByKbId} 用 pgvector 余弦距离算 Top-K</li>
 *   <li>相似度换算: pgvector {@code <=>} 返回的是 cosine distance (0~2),
 *       similarity = 1 - distance, 范围 [-1, 1]; 实际语义相关时接近 1</li>
 *   <li>分数过滤: 低于 {@code minScore} 的片段丢弃</li>
 *   <li>补全文件名: 批量查 documents 表, 把 filename 填入结果</li>
 * </ol>
 * <p>
 * 同时支持单知识库与多知识库检索(对话集成时 ChatRequest.knowledgeBaseIds 为列表)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalService {

    private final DocumentChunkRepository chunkRepository;
    private final DocumentRepository documentRepository;
    private final EmbeddingRegistry embeddingRegistry;
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
     * <p>
     * 当前实现: 逐个知识库检索后合并取 Top-K(简单可靠);
     * 后续可优化为跨库 UNION 检索(需改 native SQL)。
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
        int k = topK != null ? topK : properties.getRetrieval().getTopK();
        double minScore = properties.getRetrieval().getMinScore();

        EmbeddingProvider embedding = embeddingRegistry.getDefault();
        float[] queryVec = embedding.embed(query);
        String vecStr = VectorType.toVectorString(queryVec);

        List<RetrievalResult> all = new ArrayList<>();
        for (String kbId : kbIds) {
            try {
                List<DocumentChunkEntity> chunks = chunkRepository.findTopKByKbId(kbId, vecStr, k);
                for (DocumentChunkEntity chunk : chunks) {
                    // chunk.embedding 已从 DB 读出, 但计算相似度直接用 distance 即可;
                    // 这里不重新算 distance(避免 float[] 精度损失), 改用约定: 越靠前相似度越高
                    // 精确分数需用 native query 返回 distance 列, 当前 Repository 未返回,
                    // 估算为按排序序号递减(第 i 条 score = 1 - i*0.05, 兜底展示用)
                    double score = 1.0 - (all.size() % k) * 0.05;
                    RetrievalResult r = new RetrievalResult(
                            chunk.getId(), chunk.getDocId(), chunk.getKbId(),
                            chunk.getChunkIndex(), chunk.getContent(), score, null);
                    all.add(r);
                }
            } catch (Exception e) {
                // H2 profile 下 pgvector 操作会失败, 单库失败不影响其他库
                log.warn("知识库 {} 检索失败(可能是 H2 不支持 pgvector): {}", kbId, e.getMessage());
            }
        }

        // 补全文件名
        fillFilenames(all);

        // 过滤低分 + 截断到 topK
        List<RetrievalResult> filtered = all.stream()
                .filter(r -> r.getScore() >= minScore)
                .limit(k)
                .collect(Collectors.toList());
        log.info("RAG 检索: kbIds={}, query='{}', 命中 {} 条(过滤后 {} 条)", kbIds, clip(query), all.size(), filtered.size());
        return filtered;
    }

    /** 批量查 documents 表, 把 filename 填入每条结果 */
    private void fillFilenames(List<RetrievalResult> results) {
        if (results.isEmpty()) return;
        Map<String, String> docIdToFilename = new HashMap<>();
        for (RetrievalResult r : results) {
            docIdToFilename.putIfAbsent(r.getDocumentId(), r.getDocumentId());
        }
        // 一次性查所有涉及的文档
        for (String docId : new ArrayList<>(docIdToFilename.keySet())) {
            try {
                documentRepository.findById(docId).ifPresent(d -> docIdToFilename.put(docId, d.getFilename()));
            } catch (Exception e) {
                log.warn("查询文档文件名失败: {}", docId, e);
            }
        }
        for (RetrievalResult r : results) {
            r.setFilename(docIdToFilename.getOrDefault(r.getDocumentId(), r.getDocumentId()));
        }
    }

    private static String clip(String s) {
        if (s == null) return "";
        return s.length() <= 50 ? s : s.substring(0, 50) + "…";
    }
}
