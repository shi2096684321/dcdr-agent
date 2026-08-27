package com.dcdr.agent.model.circuitbreaker;

import com.dcdr.agent.agent.memory.Message;
import com.dcdr.agent.common.constant.ModelType;
import com.dcdr.agent.dto.ChatChunk;
import com.dcdr.agent.dto.ChatRequest;
import com.dcdr.agent.dto.ChatResponse;
import com.dcdr.agent.model.provider.ChatModelProvider;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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
 */
public class CircuitBreakerChatProvider implements ChatModelProvider {

    private final ChatModelProvider delegate;
    private final LlmCircuitBreaker circuitBreaker;

    public CircuitBreakerChatProvider(ChatModelProvider delegate, LlmCircuitBreaker circuitBreaker) {
        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
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
        try {
            ChatResponse resp = delegate.invoke(request, history);
            circuitBreaker.recordSuccess(delegate.getName());
            return resp;
        } catch (RuntimeException e) {
            circuitBreaker.recordFailure(delegate.getName());
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
            return source
                    .doOnNext(chunk -> {
                        // provider 把上游错误包装为 error 块 (type=error), 而非 Flux error 信号
                        if ("error".equals(chunk.getType()) && failed.compareAndSet(false, true)) {
                            circuitBreaker.recordFailure(delegate.getName());
                        }
                    })
                    .doOnError(e -> circuitBreaker.recordFailure(delegate.getName()))
                    .doOnComplete(() -> {
                        if (!failed.get()) circuitBreaker.recordSuccess(delegate.getName());
                    });
        });
    }
}
