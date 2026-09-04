package com.dcdr.agent.rag.retrieval;

import com.dcdr.agent.entity.VectorType;
import com.dcdr.agent.repository.DocumentChunkRepository;
import com.dcdr.agent.repository.DocumentRepository;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LangChain4j {@link EmbeddingStore} 适配器: 包装现有 {@code document_chunks VECTOR(1536)} 表。
 * <p>
 * 检索复刻 {@code RetrievalService} 原有语义(逐知识库 KNN → similarity=1-distance →
 * minScore 过滤 → topK 截断 → 批量补文件名), 保证 {@code POST /{kbId}/search} 结果与迁移前一致。
 * <p>
 * 泛型用 {@link TextSegment}: 检索结果携带文本 + 元数据(chunkId/documentId/kbId/chunkIndex/
 * filename/score), 供下游还原 {@code RetrievalResult} 与 {@code [知识库|文件]} 引用成型。
 * <p>
 * 写路径(入库)不在此: 文档入库仍由 {@code DocumentService} 直写 {@code document_chunks}, 故抛出
 * {@link UnsupportedOperationException}(适配器仅承担检索查询职责)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PgVectorEmbeddingStoreAdapter implements EmbeddingStore<TextSegment> {

    /** 元数据键: 与 RetrievalService 返回的 RetrievalResult 字段一一对应 */
    public static final String META_CHUNK_ID = "chunkId";
    public static final String META_DOC_ID = "documentId";
    public static final String META_KB_ID = "kbId";
    public static final String META_CHUNK_INDEX = "chunkIndex";
    public static final String META_FILENAME = "filename";
    public static final String META_SCORE = "score";

    private final DocumentChunkRepository chunkRepository;
    private final DocumentRepository documentRepository;

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        List<String> kbIds = extractScope(request);
        if (kbIds.isEmpty()) {
            return new EmbeddingSearchResult<>(List.of());
        }
        float[] queryVec = request.queryEmbedding().vector();
        String vecStr = VectorType.toVectorString(queryVec);
        int topK = request.maxResults();
        double minScore = request.minScore();

        // 逐知识库 KNN 检索(语义与迁移前 RetrievalService 完全一致: 不跨库全局重排,
        // 保持库序拼接后统一 过滤+截断)
        List<Candidate> candidates = new ArrayList<>();
        for (String kbId : kbIds) {
            try {
                for (DocumentChunkRepository.ChunkWithDistance c : chunkRepository.findTopKWithDistance(kbId, vecStr, topK)) {
                    candidates.add(new Candidate(c, 1.0 - c.getDistance()));
                }
            } catch (Exception e) {
                // H2 profile 下 pgvector 操作会失败, 单库失败不影响其他库
                log.warn("知识库 {} 检索失败(可能是 H2 不支持 pgvector): {}", kbId, e.getMessage());
            }
        }

        // 过滤 minScore + 跨库全局按相似度降序重排 + 截断 topK。
        // 消除"库序拼接截断"导致的库序依赖: 只要 query/topK/minScore 与库集合一致,
        // 手动检索与 LLM 检索结果即完全一致, 排前的库不再霸占名额(更匹配却在后库的片段能正确进入)。
        List<Candidate> kept = candidates.stream()
                .filter(c -> c.score >= minScore)
                .sorted(Comparator.comparingDouble((Candidate c) -> c.score).reversed())
                .limit(topK)
                .toList();

        fillFilenames(kept);

        Embedding queryEmbedding = request.queryEmbedding();
        List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>(kept.size());
        for (Candidate c : kept) {
            Metadata meta = Metadata.from(Map.of(
                    META_CHUNK_ID, c.chunk.getId(),
                    META_DOC_ID, c.chunk.getDocId(),
                    META_KB_ID, c.chunk.getKbId(),
                    META_CHUNK_INDEX, c.chunk.getChunkIndex(),
                    META_FILENAME, c.filename,
                    META_SCORE, c.score));
            TextSegment segment = TextSegment.from(c.chunk.getContent(), meta);
            matches.add(new EmbeddingMatch<>(c.score, c.chunk.getId(), queryEmbedding, segment));
        }
        return new EmbeddingSearchResult<>(matches);
    }

    /** 从 request.filter() 解析知识库范围(自定义 {@link KbIdsFilter}); 无则视为空范围 */
    private List<String> extractScope(EmbeddingSearchRequest request) {
        Filter filter = request.filter();
        if (filter instanceof KbIdsFilter kbIdsFilter) {
            return kbIdsFilter.kbIds;
        }
        return List.of();
    }

    /** 批量查 documents 表补全文件名(逻辑迁移自原 RetrievalService#fillFilenames) */
    private void fillFilenames(List<Candidate> candidates) {
        if (candidates.isEmpty()) return;
        Map<String, String> docIdToFilename = new HashMap<>();
        for (Candidate c : candidates) {
            docIdToFilename.putIfAbsent(c.chunk.getDocId(), c.chunk.getDocId());
        }
        for (String docId : new ArrayList<>(docIdToFilename.keySet())) {
            try {
                documentRepository.findById(docId).ifPresent(d -> docIdToFilename.put(docId, d.getFilename()));
            } catch (Exception e) {
                log.warn("查询文档文件名失败: {}", docId, e);
            }
        }
        for (Candidate c : candidates) {
            c.filename = docIdToFilename.getOrDefault(c.chunk.getDocId(), c.chunk.getDocId());
        }
    }

    /** 检索范围过滤器: 承载目标知识库 ID 集合, 供 {@code search} 从请求中读取作用域 */
    @RequiredArgsConstructor
    public static final class KbIdsFilter implements Filter {
        private final List<String> kbIds;
        @Override
        public boolean test(Object o) {
            return false; // 不做行级过滤, 仅作作用域传输载体, 实际限定在 search 内按库查询
        }
    }

    /** 检索候选(分块 + 相似度 + 回填文件名) */
    private static final class Candidate {
        final DocumentChunkRepository.ChunkWithDistance chunk;
        final double score;
        String filename;

        Candidate(DocumentChunkRepository.ChunkWithDistance chunk, double score) {
            this.chunk = chunk;
            this.score = score;
        }
    }

    // ------- 写路径(入库不由本适配器负责) -------

    @Override
    public String add(Embedding embedding) {
        throw new UnsupportedOperationException("入库由 DocumentService 直写 document_chunks");
    }

    @Override
    public void add(String id, Embedding embedding) {
        throw new UnsupportedOperationException("入库由 DocumentService 直写 document_chunks");
    }

    @Override
    public String add(Embedding embedding, TextSegment textSegment) {
        throw new UnsupportedOperationException("入库由 DocumentService 直写 document_chunks");
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        throw new UnsupportedOperationException("入库由 DocumentService 直写 document_chunks");
    }
}