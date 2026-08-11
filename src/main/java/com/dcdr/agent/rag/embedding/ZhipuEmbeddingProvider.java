package com.dcdr.agent.rag.embedding;

import com.dcdr.agent.rag.config.RagProperties;

/**
 * 智谱 GLM embedding-2 Provider (1024 维)。
 * <p>
 * 协议 {@code https://open.bigmodel.cn/api/paas/v4/embeddings}, <b>不支持</b> dimensions 参数。
 * <p>
 * <b>注意</b>: embedding-2 固定 1024 维, 与 schema {@code document_chunks.embedding VECTOR(1536)}
 * 不匹配; 启用前必须同步修改 schema 维度, 否则入库会失败。
 */
public class ZhipuEmbeddingProvider extends OpenAiCompatibleEmbeddingProvider {

    public ZhipuEmbeddingProvider(RagProperties.EmbeddingProviderConfig config) {
        super(config, "Zhipu-Embedding");
    }

    @Override
    protected String getEmbeddingsPath() {
        // baseUrl 已含 /api/paas/v4, 此处仅补 /embeddings
        return "/embeddings";
    }

    @Override
    protected boolean sendDimensionsParam() {
        // 智谱 embedding-2 不支持 dimensions 参数
        return false;
    }
}
