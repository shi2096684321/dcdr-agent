package com.dcdr.agent.rag.retrieval;

import com.dcdr.agent.repository.DocumentChunkRepository;
import com.dcdr.agent.repository.DocumentRepository;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BM25 全文检索 {@link ContentRetriever}(V3.2 框架化: 混合检索的全文路径)。
 * <p>
 * 基于 Postgres FTS, 输出 metadata 键与向量路径({@link PgVectorEmbeddingStoreAdapter})一致,
 * 以支持 {@code ReciprocalRankFuser} 按内容正确融合。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Bm25ContentRetriever implements ContentRetriever {

    private final DocumentChunkRepository chunkRepository;
    private final DocumentRepository documentRepository;

    /** 带作用域的 BM25 检索(Hybrid 路径由 {@code KnowledgeBaseContentRetriever} 调用) */
    public List<Content> retrieve(List<String> kbIds, int topK, String queryText) {
        List<TextSegment> segments = new ArrayList<>();
        for (String kbId : kbIds) {
            try {
                for (DocumentChunkRepository.ChunkWithFtsScore c : chunkRepository.findTopKByKbFts(kbId, queryText, topK)) {
                    segments.add(toSegment(c));
                }
            } catch (Exception e) {
                // FTS 对 H2 profile/无索引环境失败, 单库失败不影响其他库
                log.warn("知识库 {} BM25 检索失败: {}", kbId, e.getMessage());
            }
        }
        fillFilenames(segments);
        List<Content> contents = new ArrayList<>(segments.size());
        for (TextSegment s : segments) {
            contents.add(Content.from(s));
        }
        return contents;
    }

    @Override
    public List<Content> retrieve(Query query) {
        return List.of();
    }

    private TextSegment toSegment(DocumentChunkRepository.ChunkWithFtsScore c) {
        // 复用向量路径的 metadata 键, filename 待批量补齐
        Metadata meta = Metadata.from(Map.of(
                PgVectorEmbeddingStoreAdapter.META_CHUNK_ID, c.getId(),
                PgVectorEmbeddingStoreAdapter.META_DOC_ID, c.getDocId(),
                PgVectorEmbeddingStoreAdapter.META_KB_ID, c.getKbId(),
                PgVectorEmbeddingStoreAdapter.META_CHUNK_INDEX, c.getChunkIndex(),
                PgVectorEmbeddingStoreAdapter.META_FILENAME, c.getDocId(),
                PgVectorEmbeddingStoreAdapter.META_SCORE, c.getScore()));
        return TextSegment.from(c.getContent(), meta);
    }

    /** 批量查 documents 表补全文件名 */
    private void fillFilenames(List<TextSegment> segments) {
        if (segments.isEmpty()) return;
        Map<String, String> docIdToFilename = new HashMap<>();
        for (TextSegment s : segments) {
            String docId = s.metadata().getString(PgVectorEmbeddingStoreAdapter.META_DOC_ID);
            docIdToFilename.putIfAbsent(docId, docId);
        }
        for (String docId : new ArrayList<>(docIdToFilename.keySet())) {
            try {
                documentRepository.findById(docId).ifPresent(d -> docIdToFilename.put(docId, d.getFilename()));
            } catch (Exception e) {
                log.warn("查询文档文件名失败: {}", docId, e);
            }
        }
        for (TextSegment s : segments) {
            String docId = s.metadata().getString(PgVectorEmbeddingStoreAdapter.META_DOC_ID);
            s.metadata().put(PgVectorEmbeddingStoreAdapter.META_FILENAME,
                    docIdToFilename.getOrDefault(docId, docId));
        }
    }
}