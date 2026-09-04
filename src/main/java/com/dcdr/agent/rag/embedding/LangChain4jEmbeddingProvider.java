package com.dcdr.agent.rag.embedding;

import com.dcdr.agent.common.constant.ModelType;
import com.dcdr.agent.model.langchain4j.LangChain4jHttp;
import com.dcdr.agent.rag.config.RagProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 LangChain4j OpenAiEmbeddingModel 的 Embedding Provider (V3.1 框架化改造)。
 * <p>
 * 实现 {@link EmbeddingProvider} 业务接口, 内核替换为框架的
 * {@link OpenAiEmbeddingModel}: 请求构造/批量分片/maxRetries 由框架完成,
 * 替代手写版 {@code OpenAiCompatibleEmbeddingProvider} 的 WebClient + Jackson 实现。
 * <p>
 * base-url 约定一致: 框架在 baseUrl 后追加 /embeddings, 手写版
 * getEmbeddingsPath() 同为 /embeddings, 配置原样复用。
 * 批量分片: 框架按 maxSegmentsPerBatch 自动分批(对应手写版 batchSize 逻辑)。
 * <p>
 * 已知差异: 框架按上游返回顺序映射向量(OpenAI 兼容接口均按入参顺序返回),
 * 手写版额外按 index 字段排序属防御性处理, 此处不再重复。
 */
@Slf4j
public class LangChain4jEmbeddingProvider implements EmbeddingProvider {

    /** 单次调用超时, 对齐手写版 TIMEOUT(60s) */
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final EmbeddingModel embeddingModel;
    private final String name;
    private final String label;
    private final String provider;
    private final String model;
    private final int dimensions;
    private final int batchSize;

    public LangChain4jEmbeddingProvider(RagProperties.EmbeddingProviderConfig config) {
        this.embeddingModel = OpenAiEmbeddingModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModel())
                .dimensions(config.getDimensions())
                .maxSegmentsPerBatch(Math.max(1, config.getBatchSize()))
                .timeout(TIMEOUT)
                // 强制 HTTP/1.1: 消除 H2 连接池惰性清理竞态导致的高频 Connection reset(详见 LangChain4jHttp)
                .httpClientBuilder(LangChain4jHttp.forcedHttp11())
                .build();
        this.name = config.getName();
        this.label = config.getModel();
        this.provider = config.getProvider();
        this.model = config.getModel();
        this.dimensions = config.getDimensions();
        this.batchSize = Math.max(1, config.getBatchSize());
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public ModelType getType() {
        return ModelType.EMBEDDING;
    }

    @Override
    public String getLabel() {
        return label;
    }

    @Override
    public String getProvider() {
        return provider;
    }

    @Override
    public String getModel() {
        return model;
    }

    @Override
    public int getDimensions() {
        return dimensions;
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("待向量化的文本不能为空");
        }
        Response<Embedding> response = embeddingModel.embed(text);
        return response.content().vector();
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<TextSegment> segments = texts.stream().map(TextSegment::from).toList();
        // 框架内部按 maxSegmentsPerBatch 分批调用上游, 等价于手写版的 batchSize 分片循环
        Response<List<Embedding>> response = embeddingModel.embedAll(segments);
        List<Embedding> embeddings = response.content();
        if (embeddings == null || embeddings.size() != texts.size()) {
            log.warn("{} embedding 返回数量({})与入参({})不一致",
                    name, embeddings == null ? 0 : embeddings.size(), texts.size());
        }
        List<float[]> result = new ArrayList<>(embeddings != null ? embeddings.size() : 0);
        if (embeddings != null) {
            for (Embedding e : embeddings) {
                result.add(e.vector());
            }
        }
        return result;
    }
}
