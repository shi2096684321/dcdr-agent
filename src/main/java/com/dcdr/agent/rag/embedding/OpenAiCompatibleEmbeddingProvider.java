package com.dcdr.agent.rag.embedding;

import com.dcdr.agent.common.constant.ModelType;
import com.dcdr.agent.rag.config.RagProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI 兼容格式 Embedding Provider 抽象基类。
 * <p>
 * OpenAI / 通义(DashScope 兼容模式) / 智谱 均遵循 {@code POST /embeddings} 协议:
 * <pre>
 * 请求: {"model": "...", "input": ["t1","t2"], "dimensions": 1536, "encoding_format": "float"}
 * 响应: {"data": [{"embedding": [...], "index": 0}, ...], "usage": {...}}
 * </pre>
 * 不同 provider 仅 baseUrl / 鉴权 / 是否支持 dimensions 参数不同, 由子类决定。
 */
@Slf4j
public abstract class OpenAiCompatibleEmbeddingProvider implements EmbeddingProvider {

    protected final WebClient webClient;
    protected final ObjectMapper mapper = new ObjectMapper();
    protected final String name;
    protected final String label;
    protected final String provider;
    protected final String model;
    protected final int dimensions;
    protected final int batchSize;

    /** 单次同步调用总超时 */
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    /**
     * WebClient 单响应内存缓冲上限。
     * <p>
     * 默认仅 256KB, 当批量向量化条目较多时(如 batchSize=100, 每条 1536 维 float 文本约 1.5MB),
     * 响应体超 256KB 会抛 {@code DataBufferLimitException}。
     * 这里放宽到 10MB 以容纳整批向量响应; 若未来批次更大可继续上调。
     */
    private static final int MAX_IN_MEMORY_BUFFER = 10 * 1024 * 1024;

    protected OpenAiCompatibleEmbeddingProvider(RagProperties.EmbeddingProviderConfig config, String label) {
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_BUFFER))
                .build();
        this.webClient = WebClient.builder().baseUrl(config.getBaseUrl())
                .exchangeStrategies(strategies)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + config.getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .build();
        this.name = config.getName();
        this.label = label;
        this.provider = config.getProvider();
        this.model = config.getModel();
        this.dimensions = config.getDimensions();
        this.batchSize = Math.max(1, config.getBatchSize());
    }

    /** Embeddings 接口路径, 统一不带 /v1(版本段由 base-url 携带, 与 langchain4j 引擎约定一致) */
    protected String getEmbeddingsPath() {
        return "/embeddings";
    }

    /** 是否在请求体中显式传 dimensions 字段(仅 text-embedding-3/v3 系列支持) */
    protected boolean sendDimensionsParam() {
        return true;
    }

    @Override
    public ModelType getType() {
        return ModelType.EMBEDDING;
    }

    @Override
    public String getName() {
        return name;
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
        return embedBatch(List.of(text)).get(0);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<float[]> result = new ArrayList<>(texts.size());
        // 按 batchSize 分片调用上游, 避免超出单次请求 token/条目限制
        for (int i = 0; i < texts.size(); i += batchSize) {
            int end = Math.min(i + batchSize, texts.size());
            List<String> slice = texts.subList(i, end);
            try {
                result.addAll(callEmbeddings(slice));
            } catch (Exception e) {
                log.error("{} 批量向量化失败, 切片[{}..{}], 首条预览={}", name, i, end,
                        slice.isEmpty() ? "" : slice.get(0).substring(0, Math.min(50, slice.get(0).length())), e);
                throw e;
            }
        }
        return result;
    }

    /**
     * 调用上游 /embeddings 接口并解析返回向量列表。
     */
    private List<float[]> callEmbeddings(List<String> texts) {
        String body = buildRequestBody(texts);
        log.debug("{} 调用 embeddings: {} 条文本, body={}", name, texts.size(), body);
        String resp = webClient.post().uri(getEmbeddingsPath()).bodyValue(body)
                .retrieve().bodyToMono(String.class).block(TIMEOUT);
        return parseResponse(resp, texts.size());
    }

    protected String buildRequestBody(List<String> texts) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        ArrayNode input = body.putArray("input");
        for (String t : texts) {
            input.add(t);
        }
        if (sendDimensionsParam()) {
            body.put("dimensions", dimensions);
        }
        body.put("encoding_format", "float");
        return body.toString();
    }

    protected List<float[]> parseResponse(String resp, int expected) {
        List<float[]> out = new ArrayList<>(expected);
        try {
            JsonNode root = mapper.readTree(resp);
            JsonNode data = root.path("data");
            if (!data.isArray()) {
                throw new IllegalStateException("embedding 响应缺少 data 数组: " + resp);
            }
            // 上游可能不按入参顺序返回, 按 index 字段排序以保证顺序一致
            List<JsonNode> sorted = new ArrayList<>();
            data.forEach(sorted::add);
            sorted.sort((a, b) -> Integer.compare(a.path("index").asInt(0), b.path("index").asInt(0)));
            for (JsonNode item : sorted) {
                JsonNode emb = item.path("embedding");
                if (!emb.isArray()) {
                    throw new IllegalStateException("embedding 项缺少 embedding 数组: " + item);
                }
                float[] vec = new float[emb.size()];
                for (int i = 0; i < emb.size(); i++) {
                    vec[i] = (float) emb.get(i).asDouble();
                }
                out.add(vec);
            }
            if (out.size() != expected) {
                log.warn("{} embedding 返回数量({})与入参({})不一致", name, out.size(), expected);
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(name + " 解析 embedding 响应失败: " + e.getMessage(), e);
        }
    }
}
