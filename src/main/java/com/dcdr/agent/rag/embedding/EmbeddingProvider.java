package com.dcdr.agent.rag.embedding;

import com.dcdr.agent.common.constant.ModelType;

import java.util.List;

/**
 * Embedding 向量化模型提供者接口 (V2.1 RAG)。
 * <p>
 * 所有 Embedding 模型(OpenAI/智谱/通义)实现此接口, 由 EmbeddingRegistry 统一管理。
 * 与 {@code ChatModelProvider} 平行, 命名/抽象风格保持一致。
 */
public interface EmbeddingProvider {

    /** 提供者唯一标识, 如 "qwen-embedding" */
    String getName();

    /** 模型类型, 固定为 EMBEDDING */
    ModelType getType();

    /** 显示标签 */
    String getLabel();

    /** 提供商名称: qwen / openai / zhipu */
    String getProvider();

    /** 实际调用的模型名, 如 text-embedding-v3 */
    String getModel();

    /** 向量维度, 必须与 schema document_chunks.embedding VECTOR(N) 一致 */
    int getDimensions();

    /**
     * 单条文本向量化。
     *
     * @param text 文本, 不能为空
     * @return 向量, 长度 = {@link #getDimensions()}
     */
    float[] embed(String text);

    /**
     * 批量向量化, 内部按 {@code batchSize} 分批调用上游接口。
     *
     * @param texts 文本列表, 不能为空
     * @return 向量列表, 顺序与入参一致, 每条长度 = {@link #getDimensions()}
     */
    List<float[]> embedBatch(List<String> texts);
}
