package com.dcdr.agent.rag.embedding;

import com.dcdr.agent.rag.config.RagProperties;

/**
 * OpenAI text-embedding-3-small Provider (1536 维)。
 * <p>
 * 标准协议 {@code https://api.openai.com/v1/embeddings}, 支持 dimensions 参数。
 */
public class OpenAiEmbeddingProvider extends OpenAiCompatibleEmbeddingProvider {

    public OpenAiEmbeddingProvider(RagProperties.EmbeddingProviderConfig config) {
        super(config, "OpenAI-Embedding");
    }
}
