package com.dcdr.agent.rag.retrieval;

import com.dcdr.agent.rag.config.RagProperties;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容 rerank 端点适配器(V3.2 框架化: 可选重排路径)。
 * <p>
 * 请求 {@code POST {baseUrl}/rerank}, body {@code {model, query, documents:[...]}}, 响应
 * {@code {results:[{index, relevance_score,...}]}}。与 Jina / BGE 等 OpenAI 兼容 rerank 契约一致。
 * <p>
 * 仅当 {@code agent.rag.retrieval.rerank.enabled=true} 且配置了 {@code baseUrl/apiKey} 时,
 * 由 {@link RagRetrievalConfig} 生成该 Bean; 否则重排路径不启用。
 * <p>
 * 该端点契约随供应商不同可能差异, 若对接方不兼容请调整此处解析或改用供应商原生 SDK。
 */
@Slf4j
public class OpenAiCompatibleScoringModel implements ScoringModel {

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public OpenAiCompatibleScoringModel(String baseUrl, String apiKey, String model) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public Response<List<Double>> scoreAll(List<TextSegment> segments, String query) {
        List<String> documents = new ArrayList<>(segments.size());
        for (TextSegment s : segments) {
            documents.add(s.text());
        }
        try {
            String payload = buildPayload(query, documents);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/rerank"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                log.error("rerank 失败: status={}, body={}", resp.statusCode(), truncate(resp.body()));
                throw new IllegalStateException("rerank 服务返回 " + resp.statusCode());
            }
            List<Double> scores = parseScores(resp.body(), segments.size());
            return Response.from(scores);
        } catch (Exception e) {
            throw new IllegalStateException("rerank 调用失败: " + e.getMessage(), e);
        }
    }

    private String buildPayload(String query, List<String> documents) throws Exception {
        StringBuilder docs = new StringBuilder();
        for (String d : documents) {
            if (docs.length() > 0) docs.append(',');
            docs.append('"').append(escape(d)).append('"');
        }
        return "{\"model\":\"" + escape(model) + "\",\"query\":\"" + escape(query)
                + "\",\"documents\":[" + docs + "]}";
    }

    private List<Double> parseScores(String body, int expected) throws Exception {
        List<Double> scores = new ArrayList<>(expected);
        // 预置与入参等长的占位, 未出现的 index 用 0
        for (int i = 0; i < expected; i++) {
            scores.add(0.0);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> root = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(body, Map.class);
        if (root.get("results") instanceof List<?> results) {
            for (Object o : results) {
                if (!(o instanceof Map<?, ?> m)) continue;
                Number idx = (Number) m.get("index");
                Number score = (Number) m.get("relevance_score");
                if (idx != null && score != null && idx.intValue() < expected) {
                    scores.set(idx.intValue(), score.doubleValue());
                }
            }
        }
        return scores;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 200 ? s : s.substring(0, 200);
    }
}