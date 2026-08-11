package com.dcdr.agent.rag.embedding;

import com.dcdr.agent.rag.config.RagProperties;

/**
 * 通义 DashScope text-embedding-v4 Provider (Qwen3-Embedding, 1536 维)。
 * <p>
 * 复用 OpenAI 兼容协议, baseUrl 走 {@code https://dashscope.aliyuncs.com/compatible-mode/v1},
 * 路径 {@code /embeddings}; v4 支持 dimensions 参数(64/128/256/512/768/1024/1536/2048),
 * 此处用 1536 与 schema document_chunks.embedding VECTOR(1536) 对齐。
 * <p>
 * 注: text-embedding-v3 仅支持 1024/768/512 维, 传 1536 会返回 400; v4 是推荐模型。
 */
public class QwenEmbeddingProvider extends OpenAiCompatibleEmbeddingProvider {

    public QwenEmbeddingProvider(RagProperties.EmbeddingProviderConfig config) {
        super(config, "Qwen-Embedding");
    }

    @Override
    protected String getEmbeddingsPath() {
        // baseUrl 已含 /compatible-mode/v1, 此处仅补 /embeddings
        return "/embeddings";
    }
}
