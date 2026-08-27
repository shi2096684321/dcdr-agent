package com.dcdr.agent.repository;

import com.dcdr.agent.entity.DocumentChunkEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentChunkRepository extends JpaRepository<DocumentChunkEntity, String> {

    List<DocumentChunkEntity> findByKbIdOrderByChunkIndexAsc(String kbId);

    List<DocumentChunkEntity> findByDocIdOrderByChunkIndexAsc(String docId);

    /**
     * 按知识库 + 向量余弦距离检索最相近的 Top-K 分块 (V2.1 RAG 检索核心)
     * queryVec 以 PgVector 文本格式传入 (如 '[0.1,0.2,...]')
     */
    @Query(value = """
            SELECT * FROM document_chunks
            WHERE kb_id = :kbId AND embedding IS NOT NULL
            ORDER BY embedding <=> CAST(:queryVec AS vector)
            LIMIT :topK
            """, nativeQuery = true)
    List<DocumentChunkEntity> findTopKByKbId(
            @Param("kbId") String kbId,
            @Param("queryVec") String queryVec,
            @Param("topK") int topK);

    /**
     * 检索 Top-K 并返回真实余弦距离 (V3.1 修复: 原实现 score 按排序位置估算,
     * min-score 过滤依据失真)。
     * <p>
     * pgvector {@code <=>} 返回 cosine distance (0~2), similarity = 1 - distance。
     */
    @Query(value = """
            SELECT id, doc_id, kb_id, chunk_index, content,
                   embedding <=> CAST(:queryVec AS vector) AS distance
            FROM document_chunks
            WHERE kb_id = :kbId AND embedding IS NOT NULL
            ORDER BY distance
            LIMIT :topK
            """, nativeQuery = true)
    List<ChunkWithDistance> findTopKWithDistance(
            @Param("kbId") String kbId,
            @Param("queryVec") String queryVec,
            @Param("topK") int topK);

    /**
     * 带真实距离的检索结果投影 (native query 列名 → getter 驼峰映射)。
     */
    interface ChunkWithDistance {
        String getId();

        String getDocId();

        String getKbId();

        Integer getChunkIndex();

        String getContent();

        double getDistance();
    }
}
