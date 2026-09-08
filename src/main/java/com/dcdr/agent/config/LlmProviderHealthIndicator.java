package com.dcdr.agent.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V3.0 F-40.2 系统级可观测性: LLM Provider 主动健康探测。
 * <p>
 * 除了已有 OllamaHealthIndicator(本地), 对每个启用的远程 `chat` provider(非 ollama)
 * 探测其 OpenAI 兼容端点的 {@code GET {base-url}/models}(部分厂商列表端点无需鉴权即回 200)。
 * <ul>
 *   <li>任何 HTTP 响应(含 401/403)= 服务可达 → UP, 附 statusCode 与模型名</li>
 *   <li>连接失败/超时 = 不可达 → DOWN</li>
 *   <li>未配置远程 chat 模型 → UNKNOWN(不参与聚合)</li>
 * </ul>
 * 暴露于 {@code GET /actuator/health}(含 llm 组件), 供 Prometheus 抓取与告警。
 */
@Component
public class LlmProviderHealthIndicator implements ReactiveHealthIndicator {

    private final AgentProperties properties;
    private final WebClient webClient;

    public LlmProviderHealthIndicator(AgentProperties properties) {
        this.properties = properties;
        this.webClient = WebClient.create();
    }

    @Override
    public Mono<Health> health() {
        List<AgentProperties.ModelConfig> remote = remoteChatConfigs();
        if (remote.isEmpty()) {
            return Mono.just(Health.unknown().withDetail("reason", "未启用远程 LLM chat 模型").build());
        }
        // 并行探测各 provider, 聚合结果
        return Mono.zip(remote.stream().map(this::probe).toList(), results -> {
            List<Map<String, Object>> list = new ArrayList<>();
            boolean anyUp = false;
            for (Object r : results) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) r;
                list.add(m);
                if (Boolean.TRUE.equals(m.get("online"))) anyUp = true;
            }
            Health.Builder b = anyUp ? Health.up() : Health.down();
            return b.withDetail("providers", list).build();
        });
    }

    private Mono<Map<String, Object>> probe(AgentProperties.ModelConfig c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("model", c.getName());
        m.put("provider", c.getProvider());
        String baseUrl = c.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            m.put("online", Boolean.FALSE);
            m.put("error", "未配置 base-url");
            return Mono.just(m);
        }
        String modelsUrl = baseUrl.endsWith("/") ? baseUrl + "models" : baseUrl + "/models";
        return doProbe(c, modelsUrl, m);
    }

    private Mono<Map<String, Object>> doProbe(AgentProperties.ModelConfig c, String modelsUrl, Map<String, Object> m) {
        WebClient.RequestHeadersSpec<?> spec = webClient.get().uri(modelsUrl);
        if (c.getApiKey() != null && !c.getApiKey().isBlank()) {
            spec = spec.headers(h -> h.setBearerAuth(c.getApiKey()));
        }
        return spec.exchangeToMono(resp -> {
            m.put("online", Boolean.TRUE);
            m.put("status", resp.statusCode().value());
            return Mono.just(m);
        }).timeout(Duration.ofSeconds(3))
          .onErrorResume(e -> {
              m.put("online", Boolean.FALSE);
              m.put("error", "不可达: " + e.getMessage());
              return Mono.just(m);
          });
    }

    private List<AgentProperties.ModelConfig> remoteChatConfigs() {
        List<AgentProperties.ModelConfig> list = new ArrayList<>();
        if (properties.getModels().getChat() == null) return list;
        for (AgentProperties.ModelConfig c : properties.getModels().getChat()) {
            if (c.isEnabled() && !"ollama".equals(c.getProvider())) list.add(c);
        }
        return list;
    }
}