package com.dcdr.agent.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * V3.0-rc F-38 + F-40.2: Ollama 本地模型主动健康探测。
 * <p>
 * 从 {@code agent.models.chat} 中取 provider=ollama 的启用配置,
 * ping {@code {base-url 去掉 /v1}/api/tags} (Ollama 原生模型列表端点)。
 * <ul>
 *   <li>未配置 Ollama 模型 → UNKNOWN (不参与聚合状态判定)</li>
 *   <li>ping 通 → UP, 附模型名与已安装本地模型数</li>
 *   <li>ping 不通 → DOWN + 错误信息 (Ollama 服务未启动)</li>
 * </ul>
 * 暴露于 {@code GET /actuator/health} (含 ollama 组件)。
 */
@Component
public class OllamaHealthIndicator implements ReactiveHealthIndicator {

    private final AgentProperties properties;
    private final org.springframework.web.reactive.function.client.WebClient webClient;

    public OllamaHealthIndicator(AgentProperties properties) {
        this.properties = properties;
        this.webClient = org.springframework.web.reactive.function.client.WebClient.create();
    }

    @Override
    public Mono<Health> health() {
        AgentProperties.ModelConfig ollama = findOllamaConfig();
        if (ollama == null) {
            return Mono.just(Health.unknown().withDetail("reason", "未启用 Ollama 模型").build());
        }
        // OpenAI 兼容 base-url 形如 http://host:11434/v1, 原生端点去掉 /v1
        String baseUrl = ollama.getBaseUrl() == null ? "http://localhost:11434" : ollama.getBaseUrl();
        String tagsUrl = baseUrl.replaceAll("/v1/?$", "") + "/api/tags";
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("baseUrl", baseUrl);
        details.put("model", ollama.getModel());
        return webClient.get().uri(tagsUrl)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(3))
                .map(body -> {
                    int models = body.split("\"name\"").length - 1;
                    details.put("localModels", models);
                    return Health.up().withDetails(details).build();
                })
                .onErrorResume(e -> Mono.just(Health.down()
                        .withDetails(details)
                        .withDetail("error", "Ollama 不可达: " + e.getMessage())
                        .build()));
    }

    private AgentProperties.ModelConfig findOllamaConfig() {
        if (properties.getModels().getChat() == null) return null;
        return properties.getModels().getChat().stream()
                .filter(c -> c.isEnabled() && "ollama".equals(c.getProvider()))
                .findFirst().orElse(null);
    }
}
