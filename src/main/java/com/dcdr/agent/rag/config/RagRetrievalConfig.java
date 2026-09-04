package com.dcdr.agent.rag.config;

import com.dcdr.agent.rag.retrieval.OpenAiCompatibleScoringModel;
import dev.langchain4j.model.scoring.ScoringModel;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 检索的框架原生装配(V3.2 框架化)。
 * <p>
 * 主要组件均为 {@code @Component}({@code PgVectorEmbeddingStoreAdapter} /
 * {@code KnowledgeBaseContentRetriever} / {@code Bm25ContentRetriever}) 由组件扫描自动注册;
 * 本配置仅负责按配置开关生成可选的 {@link ScoringModel}(rerank 重排模型)。
 */
@Configuration
@RequiredArgsConstructor
public class RagRetrievalConfig {

    private final RagProperties ragProperties;

    /**
     * 重排模型: 仅当 rerank.enabled=true 且配置了 baseUrl/apiKey 时才注册。
     * 满足条件时 {@code KnowledgeBaseContentRetriever} 的重排路径自动生效; 否则重排不启用。
     */
    @Bean
    @ConditionalOnExpression(
            "'${agent.rag.retrieval.rerank.enabled:false}' == 'true' " +
            "and '${agent.rag.retrieval.rerank.baseUrl:}' != '' " +
            "and '${agent.rag.retrieval.rerank.apiKey:}' != ''")
    public ScoringModel ragScoringModel() {
        RagProperties.RerankConfig c = ragProperties.getRetrieval().getRerank();
        return new OpenAiCompatibleScoringModel(c.getBaseUrl(), c.getApiKey(), c.getModel());
    }
}