package com.dcdr.agent.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V3.0 F-40.2 系统级可观测性: LLM 调用自定义业务指标。
 * <p>
 * 封装 Micrometer {@link MeterRegistry}, 在统一的 LLM 出口(CircuitBreakerChatProvider)打点,
 * 暴露以下指标供 Prometheus 抓取:
 * <ul>
 *   <li>{@code dcdr_llm_calls_total{provider}} — 成功调用数(流式在完成时计)</li>
 *   <li>{@code dcdr_llm_errors_total{provider}} — 失败调用数(含流式错误块/异常信号)</li>
 *   <li>{@code dcdr_llm_latency_seconds{provider}} — 单次调用耗时分布(含流式整体)</li>
 * </ul>
 * provider 维度=模型配置名(与 LlmCircuitBreaker 口径一致)。
 * <p>
 * 除供 Prometheus 抓取外, 也可经 {@link #snapshot()} 在前端内置监控面板展示
 * (MonitorController /api/monitor/llm), 使 LLM 调用统计不依赖外部监控组件。
 */
@Component
public class LlmMetrics {

    private final MeterRegistry registry;

    public LlmMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 记录一次成功的 LLM 调用 */
    public void recordCall(String provider) {
        registry.counter("dcdr.llm.calls", "provider", provider).increment();
    }

    /** 记录一次失败的 LLM 调用 */
    public void recordError(String provider) {
        registry.counter("dcdr.llm.errors", "provider", provider).increment();
    }

    /** 记录一次调用的耗时 */
    public void recordLatency(String provider, Duration duration) {
        registry.timer("dcdr.llm.latency", "provider", provider).record(duration);
    }

    /**
     * 读取当前各 provider 的调用/失败/耗时指标快照(供内置监控面板展示)。
     * <p>
     * 每个元素: {@code {provider, calls, errors, errorRate(0~1), latencyMeanSeconds}}。
     * 从 MeterRegistry 只读, 不触发任何外部调用, 可安全在同步端点内返回。
     */
    public List<Map<String, Object>> snapshot() {
        Map<String, Map<String, Object>> byProvider = new LinkedHashMap<>();
        for (Counter c : registry.find("dcdr.llm.calls").counters()) {
            String p = c.getId().getTag("provider");
            if (p == null) continue;
            entry(byProvider, p).put("calls", c.count());
        }
        for (Counter c : registry.find("dcdr.llm.errors").counters()) {
            String p = c.getId().getTag("provider");
            if (p == null) continue;
            entry(byProvider, p).put("errors", c.count());
        }
        for (Timer t : registry.find("dcdr.llm.latency").timers()) {
            String p = t.getId().getTag("provider");
            if (p == null) continue;
            entry(byProvider, p).put("latencyMeanSeconds", round3(t.mean(java.util.concurrent.TimeUnit.SECONDS)));
            // 唯一注册 StatefulMeter: 以 timer 的 count 作为总调用次数兜底
            entry(byProvider, p).putIfAbsent("calls", t.count());
        }
        // 计算失败率
        for (Map<String, Object> m : byProvider.values()) {
            long calls = ((Number) m.getOrDefault("calls", 0L)).longValue();
            long errors = ((Number) m.getOrDefault("errors", 0L)).longValue();
            long tot = calls + errors;
            double rate = tot == 0 ? 0d : (double) errors / tot;
            m.put("errorRate", round3(rate));
        }
        return new ArrayList<>(byProvider.values());
    }

    private Map<String, Object> entry(Map<String, Map<String, Object>> byProvider, String p) {
        return byProvider.computeIfAbsent(p, k -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("provider", k);
            m.put("calls", 0L);
            m.put("errors", 0L);
            m.put("latencyMeanSeconds", 0d);
            return m;
        });
    }

    private double round3(double v) {
        return Math.round(v * 1000d) / 1000d;
    }
}