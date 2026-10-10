package com.dcdr.agent.rag.config;

import com.dcdr.agent.rag.retrieval.OpenAiCompatibleScoringModel;
import dev.langchain4j.community.model.dashscope.QwenScoringModel;
import dev.langchain4j.model.scoring.ScoringModel;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * RAG 检索的框架原生装配(V3.2 框架化)。
 * <p>
 * 主要组件均为 {@code @Component}({@code PgVectorEmbeddingStoreAdapter} /
 * {@code KnowledgeBaseContentRetriever} / {@code Bm25ContentRetriever}) 由组件扫描自动注册;
 * 本配置仅负责按配置开关生成可选的 {@link ScoringModel}(rerank 重排模型)。
 * <p>
 * 重排支持两种引擎, 由 {@code agent.rag.retrieval.rerank.engine} 选择(与
 * {@code agent.rag.embedding.providers[].engine} 同风格):
 * <ul>
 *   <li>{@code dashscope}=百炼原生 {@link QwenScoringModel}, 走官方 dashscope-sdk-java,
 *       适配 {@code qwen3.7-text-rerank} 等模型</li>
 *   <li>{@code openai}=OpenAI 兼容 HTTP 端点, 由自研 {@link OpenAiCompatibleScoringModel}
 *       对接 Jina / Cohere / 自部署 bge-reranker</li>
 * </ul>
 * 两个 {@link Bean} 条件互斥, 同一时刻最多注册一个; 条件均不满足时重排不启用。
 */
@Configuration
@RequiredArgsConstructor
public class RagRetrievalConfig {

    private final RagProperties ragProperties;

    /**
     * 百炼原生重排模型: 仅 engine=dashscope、rerank 开启且配有 apiKey 时注册。
     * <p>
     * <b>刻意不设置 {@code topN}</b>: 设置后服务端仅返回 topN 条结果, 会使 {@code scoreAll}
     * 返回的分数列表短于入参, 破坏 {@link ScoringModel} 的 1:1 契约。截断交由
     * {@code ReRankingContentAggregator} 的 maxResults 完成。
     */
    @Bean
    @ConditionalOnProperty(prefix = "agent.rag.retrieval.rerank", name = "engine", havingValue = "dashscope")
    @ConditionalOnExpression(
            "'${agent.rag.retrieval.rerank.enabled:false}' == 'true' "
            + "and '${agent.rag.retrieval.rerank.apiKey:}' != ''")
    public ScoringModel dashscopeRagScoringModel() {
        RagProperties.RerankConfig c = ragProperties.getRetrieval().getRerank();
        QwenScoringModel.QwenScoringModelBuilder builder = QwenScoringModel.builder()
                .apiKey(c.getApiKey())
                .modelName(c.getModel());
        // baseUrl 留空时走 SDK 默认端点; 需专属域名({WorkspaceId}.cn-beijing.maas.aliyuncs.com)时再填
        if (StringUtils.hasText(c.getBaseUrl())) {
            builder.baseUrl(c.getBaseUrl());
        }
        // instruct 留空时用模型默认(问答检索任务), 正是 RAG 场景
        if (StringUtils.hasText(c.getInstruct())) {
            builder.instruct(c.getInstruct());
        }
        return builder.build();
    }

    /**
     * OpenAI 兼容重排模型(默认引擎): 仅 engine 非 dashscope、rerank 开启且配有
     * baseUrl/apiKey 时注册。
     */
    @Bean
    @ConditionalOnExpression(
            "'${agent.rag.retrieval.rerank.enabled:false}' == 'true' "
            + "and '${agent.rag.retrieval.rerank.engine:openai}' != 'dashscope' "
            + "and '${agent.rag.retrieval.rerank.baseUrl:}' != '' "
            + "and '${agent.rag.retrieval.rerank.apiKey:}' != ''")
    public ScoringModel openAiCompatibleRagScoringModel() {
        RagProperties.RerankConfig c = ragProperties.getRetrieval().getRerank();
        return new OpenAiCompatibleScoringModel(c.getBaseUrl(), c.getApiKey(), c.getModel());
    }
}
