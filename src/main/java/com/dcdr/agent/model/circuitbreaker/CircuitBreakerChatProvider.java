package com.dcdr.agent.model.circuitbreaker;

import com.dcdr.agent.agent.memory.Message;
import com.dcdr.agent.common.constant.ModelType;
import com.dcdr.agent.config.LlmMetrics;
import com.dcdr.agent.dto.ChatChunk;
import com.dcdr.agent.dto.ChatRequest;
import com.dcdr.agent.dto.ChatResponse;
import com.dcdr.agent.model.provider.ChatModelProvider;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * V3.0-rc F-40.4: ChatModelProvider 熔断装饰器。
 * <p>
 * 在 ModelAutoConfiguration 注册时包裹真实 Provider, 透明拦截:
 * <ul>
 *   <li>调用前 {@code check()}: 熔断开启时直接抛 503, 不发起上游 HTTP 请求</li>
 *   <li>流式: error 块记为失败, 正常完成记为成功 (interrupted 状态块不算失败)</li>
 *   <li>同步: 异常记为失败, 正常返回记为成功</li>
 * </ul>
 * 对 AgentOrchestrator/Router 等上层完全透明 (装饰器模式, 不改任何调用方)。
 * <p>
 * 同时作为 F-40.2 统一 LLM 打点出口: 若注入了 {@link LlmMetrics}, 同步记录
 * 调用数/失败数/耗时(dcdr_llm_* 指标)。
 */
public class CircuitBreakerChatProvider implements ChatModelProvider {

    private final ChatModelProvider delegate;
    private final LlmCircuitBreaker circuitBreaker;
    private final LlmMetrics metrics;

    public CircuitBreakerChatProvider(ChatModelProvider delegate, LlmCircuitBreaker circuitBreaker) {
        this(delegate, circuitBreaker, null);
    }

    public CircuitBreakerChatProvider(ChatModelProvider delegate, LlmCircuitBreaker circuitBreaker, LlmMetrics metrics) {
        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
        this.metrics = metrics;
    }

    public ChatModelProvider unwrap() {
        return delegate;
    }

    @Override
    public String getName() { return delegate.getName(); }

    @Override
    public ModelType getType() { return delegate.getType(); }

    @Override
    public String getLabel() { return delegate.getLabel(); }

    @Override
    public String getProvider() { return delegate.getProvider(); }

    @Override
    public boolean supportsVision() { return delegate.supportsVision(); }

    @Override
    public boolean supportsFunctionCalling() { return delegate.supportsFunctionCalling(); }

    @Override
    public ChatResponse invoke(ChatRequest request, List<Message> history) {
        circuitBreaker.check(delegate.getName());
        long start = System.nanoTime();
        try {
            ChatResponse resp = delegate.invoke(request, history);
            circuitBreaker.recordSuccess(delegate.getName());
            record(true, start);
            return resp;
        } catch (RuntimeException e) {
            circuitBreaker.recordFailure(delegate.getName());
            record(false, start);
            throw e;
        }
    }

    @Override
    public Flux<ChatChunk> stream(ChatRequest request, List<Message> history) {
        return wrap(delegate.stream(request, history));
    }

    @Override
    public Flux<ChatChunk> stream(ChatRequest request, List<Message> history, boolean includeCurrentUser) {
        return wrap(delegate.stream(request, history, includeCurrentUser));
    }

    private Flux<ChatChunk> wrap(Flux<ChatChunk> source) {
        return Flux.defer(() -> {
            // defer 内 check: 订阅时刻校验熔断状态 (非构造时刻)
            circuitBreaker.check(delegate.getName());
            AtomicBoolean failed = new AtomicBoolean(false);
            AtomicLong start = new AtomicLong(System.nanoTime());
            return source
                    .doOnNext(chunk -> {
                        // provider 把上游错误包装为 error 块 (type=error), 而非 Flux error 信号
                        if ("error".equals(chunk.getType()) && failed.compareAndSet(false, true)) {
                            circuitBreaker.recordFailure(delegate.getName());
                            record(false, start.get());
                        }
                    })
                    .doOnError(e -> {
                        circuitBreaker.recordFailure(delegate.getName());
                        record(false, start.get());
                    })
                    .doOnComplete(() -> {
                        if (!failed.get()) {
                            circuitBreaker.recordSuccess(delegate.getName());
                            record(true, start.get());
                        }
                    });
        });
    }

    private void record(boolean success, long startNanos) {
        if (metrics == null) return;
        String provider = delegate.getName();
        if (success) metrics.recordCall(provider);
        else metrics.recordError(provider);
        metrics.recordLatency(provider, Duration.ofNanos(System.nanoTime() - startNanos));
    }
}
