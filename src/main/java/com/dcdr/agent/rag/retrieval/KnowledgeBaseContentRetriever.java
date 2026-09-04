package com.dcdr.agent.rag.retrieval;

import com.dcdr.agent.rag.config.RagProperties;
import com.dcdr.agent.rag.embedding.EmbeddingRegistry;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.DefaultContent;
import dev.langchain4j.rag.content.aggregator.ReciprocalRankFuser;
import dev.langchain4j.rag.content.aggregator.ReRankingContentAggregator;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 框架原生 {@link ContentRetriever}: 知识库检索的统一入口。
 * <p>
 * 职责链路: query 向量化 → {@link PgVectorEmbeddingStoreAdapter} 向量检索 → (可选)
 * BM25 全文检索 + {@link ReciprocalRankFuser}(RRF) 混合融合 → (可选)
 * {@link ReRankingContentAggregator} 相关度重排 → 返回 {@link Content} 列表。
 * <p>
 * hybrid / rerank 均为配置开关(默认关闭), 关闭时退化为"纯向量", 与迁移前行为一致。
 * 检索文本上携带的 metadata(chunkId/documentId/kbId/chunkIndex/filename/score)常用于
 * 下游 {code RetrievalService} 还原 {@code RetrievalResult} 与来源引用。
 */
@Slf4j
@Component
public class KnowledgeBaseContentRetriever implements ContentRetriever {

    private final PgVectorEmbeddingStoreAdapter adapter;
    private final EmbeddingRegistry embeddingRegistry;
    private final RagProperties ragProperties;
    /** 混合检索全文路径(仅 hybrid 开启时 injected) */
    @Nullable
    private final Bm25ContentRetriever bm25Retriever;
    /** 重排模型(仅 rerank 开启时 injected) */
    @Nullable
    private final ScoringModel scoringModel;

    public KnowledgeBaseContentRetriever(PgVectorEmbeddingStoreAdapter adapter,
                                         EmbeddingRegistry embeddingRegistry,
                                         RagProperties ragProperties,
                                         @Lazy @Nullable Bm25ContentRetriever bm25Retriever,
                                         @Lazy @Nullable ScoringModel scoringModel) {
        this.adapter = adapter;
        this.embeddingRegistry = embeddingRegistry;
        this.ragProperties = ragProperties;
        this.bm25Retriever = bm25Retriever;
        this.scoringModel = scoringModel;
    }

    /**
     * 带检索作用域/上限的检索(RetrievalService 主路径)。
     * 知识库范围与 topK 因无法挂到框架 {@link Query} 的 metadata 上, 故用显式入参传入。
     */
    public List<Content> retrieve(List<String> kbIds, Integer topK, String queryText) {
        RagProperties.RetrievalConfig cfg = ragProperties.getRetrieval();
        if (kbIds == null || kbIds.isEmpty() || queryText == null || queryText.isBlank()) {
            return List.of();
        }
        int k = topK != null ? topK : cfg.getTopK();
        double minScore = cfg.getMinScore();

        Embedding queryEmbedding = Embedding.from(embeddingRegistry.getDefault().embed(queryText));
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(k)
                .minScore(minScore)
                .filter(new PgVectorEmbeddingStoreAdapter.KbIdsFilter(new ArrayList<>(kbIds)))
                .build();
        EmbeddingSearchResult<dev.langchain4j.data.segment.TextSegment> searchResult = adapter.search(request);

        List<Content> contents = toContents(searchResult);

        // 混合检索: 向量 + BM25, RRF 融合
        boolean hybrid = cfg.getHybrid().isEnabled() && bm25Retriever != null;
        if (hybrid) {
            List<Content> bm25 = bm25Retriever.retrieve(kbIds, k, queryText);
            contents = ReciprocalRankFuser.fuse(List.of(contents, bm25), cfg.getHybrid().getRrfK());
        }

        // 相关度重排: 仅 rerank 开启且已配置 ScoringModel 时生效
        boolean rerank = cfg.getRerank().isEnabled() && scoringModel != null;
        if (rerank) {
            Query query = Query.from(queryText);
            ReRankingContentAggregator aggregator = ReRankingContentAggregator.builder()
                    .scoringModel(scoringModel)
                    .minScore(cfg.getRerank().getMinScore())
                    .maxResults(cfg.getRerank().getMaxResults())
                    .build();
            contents = aggregator.aggregate(Map.of(query, List.of(contents)));
        }

        return contents;
    }

    /** 框架 {@link ContentRetriever} 契约方法: 无作用域上下文(RetrievalService 不走此路径), 返回空 */
    @Override
    public List<Content> retrieve(Query query) {
        return List.of();
    }

    private List<Content> toContents(EmbeddingSearchResult<dev.langchain4j.data.segment.TextSegment> result) {
        List<Content> contents = new ArrayList<>(result.matches().size());
        for (EmbeddingMatch<dev.langchain4j.data.segment.TextSegment> match : result.matches()) {
            contents.add(new DefaultContent(match.embedded()));
        }
        return contents;
    }
}