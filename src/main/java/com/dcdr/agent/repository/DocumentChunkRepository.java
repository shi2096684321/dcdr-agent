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
}
