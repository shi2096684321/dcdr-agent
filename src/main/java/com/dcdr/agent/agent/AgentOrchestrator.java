package com.dcdr.agent.agent;

import com.dcdr.agent.agent.memory.ChatMemory;
import com.dcdr.agent.agent.memory.Message;
import com.dcdr.agent.agent.memory.TokenEstimator;
import com.dcdr.agent.agent.session.SessionManager;
import com.dcdr.agent.dto.ChatChunk;
import com.dcdr.agent.dto.ChatRequest;
import com.dcdr.agent.dto.ChatResponse;
import com.dcdr.agent.dto.SessionDTO;
import com.dcdr.agent.model.provider.ChatModelProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 编排器 - 协调模型路由、记忆管理、流式输出
 * <p>
 * 系统核心: 接收用户消息 → 加载记忆 → 路由模型 → 流式调用 → 保存记忆
 */
@Slf4j
@Component
public class AgentOrchestrator {

    private final ModelRouter router;
    private final ChatMemory memory;
    private final SessionManager sessionManager;
    private final ActiveChatRegistry activeChatRegistry;
    private final TokenEstimator tokenEstimator;

    public AgentOrchestrator(ModelRouter router, ChatMemory memory, SessionManager sessionManager,
                             ActiveChatRegistry activeChatRegistry, TokenEstimator tokenEstimator) {
        this.router = router;
        this.memory = memory;
        this.sessionManager = sessionManager;
        this.activeChatRegistry = activeChatRegistry;
        this.tokenEstimator = tokenEstimator;
    }

    /**
     * 流式对话
     */
    public Flux<ChatChunk> chat(ChatRequest request) {
        return Flux.create(sink -> {
            try {
                // 1. 确保有会话
                String sessionId = request.getSessionId();
                if (sessionId == null || sessionId.isBlank()) {
                    SessionDTO session = sessionManager.createSession("新对话", request.getModel(), null);
                    sessionId = session.getId();
                    request.setSessionId(sessionId);
                }
                final String sessId = sessionId;

                // 计时起点(用于统计本次请求服务端耗时)
                final long startNanos = System.nanoTime();

                // 2. 加载记忆
                List<Message> history = memory.load(sessId);
                log.debug("会话 {} 加载记忆 {} 条", sessId, history.size());

                // 2.1 上下文接近上限提示: 用「将发送给模型的完整上下文」做估算,
                //     在调用模型前先下发 context_warning, 不阻断对话, 由用户决定是否开新对话
                List<Message> contextForEstimate = new ArrayList<>(history);
                contextForEstimate.add(Message.user(request.getMessage()));
                if (tokenEstimator.isNearLimit(contextForEstimate)) {
                    int est = tokenEstimator.estimate(contextForEstimate);
                    sink.next(ChatChunk.contextWarning(
                            String.format("对话已较长（约 %d token），接近上下文上限，建议开启新对话以免丢失早期内容。",
                                    est)));
                }

                // 3. 路由决策
                ChatModelProvider provider = router.route(request, history);
                log.info("会话 {} 路由到模型: {}", sessId, provider.getName());

                // 4. 保存用户消息到记忆
                boolean firstMessage = history.stream().noneMatch(m -> "user".equals(m.getRole()));
                memory.save(sessId, Message.user(request.getMessage()));
                if (firstMessage) {
                    sessionManager.autoTitleFromFirstMessage(sessId, request.getMessage());
                }

                // 5. 流式调用模型
                StringBuilder accumulated = new StringBuilder();
                StringBuilder reasoningAccumulated = new StringBuilder();
                // handle 引用需在 doOnComplete/doOnError 中访问, 用数组持有以满足 effectively final
                final ActiveChatRegistry.ChatHandle[] handleHolder = new ActiveChatRegistry.ChatHandle[1];
                // 捕获模型真实返回的 token 消耗与结束原因, 用于回填 done 块
                final ChatResponse.TokenUsage[] usageHolder = new ChatResponse.TokenUsage[1];
                final String[] finishReasonHolder = new String[1];
                Disposable upstream = provider.stream(request, history)
                        .doOnNext(chunk -> {
                            sink.next(chunk);
                            if ("usage".equals(chunk.getType()) && chunk.getTokenUsage() != null) {
                                usageHolder[0] = chunk.getTokenUsage();
                                if (chunk.getFinishReason() != null) {
                                    finishReasonHolder[0] = chunk.getFinishReason();
                                }
                                return;
                            }
                            if ("chunk".equals(chunk.getType())) {
                                if (chunk.getContent() != null) {
                                    accumulated.append(chunk.getContent());
                                }
                                if (chunk.getReasoningContent() != null) {
                                    reasoningAccumulated.append(chunk.getReasoningContent());
                                }
                            }
                        })
                        .doOnComplete(() -> {
                            // 8. 统计 token: 优先模型真实 usage, 否则用已累积文本估算兜底(杜绝"0 token")
                            ChatResponse.TokenUsage u = usageHolder[0];
                            int p = u != null ? u.getPrompt() : tokenEstimator.estimate(contextForEstimate);
                            int c = u != null ? u.getCompletion()
                                    : tokenEstimator.estimate(accumulated.toString())
                                        + tokenEstimator.estimate(reasoningAccumulated.toString());
                            int t = u != null ? u.getTotal() : (p + c);
                            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                            // 6. 保存助手回复(含推理内容 + token 消耗)到记忆
                            memory.save(sessId, Message.assistant(accumulated.toString(),
                                    reasoningAccumulated.toString(), provider.getName(), t));
                            // 7. 更新会话时间
                            sessionManager.touch(sessId);
                            sink.next(ChatChunk.done(p, c, t, durationMs));
                            // 8.1 根据 finish_reason 补充提示(截断/被打断不再伪装成正常完成)
                            String fr = finishReasonHolder[0];
                            if ("length".equals(fr)) {
                                sink.next(ChatChunk.contextWarning(
                                        "回答已达长度上限(max_tokens)被截断，可尝试简化问题或开启新对话。"));
                            } else if ("insufficient_system_resource".equals(fr)) {
                                sink.next(ChatChunk.error("生成因后端推理资源不足被打断", "RESOURCE_LIMIT"));
                            } else if ("content_filter".equals(fr)) {
                                sink.next(ChatChunk.contextWarning("回答触发安全策略过滤。"));
                            }
                            sink.complete();
                            if (handleHolder[0] != null) {
                                handleHolder[0].markFinished();
                            }
                            activeChatRegistry.unregister(sessId);
                            log.info("会话 {} 对话完成", sessId);
                        })
                        .doOnError(e -> {
                            log.error("会话 {} 对话失败", sessId, e);
                            sink.next(ChatChunk.error("模型调用失败: " + e.getMessage(), "MODEL_ERROR"));
                            sink.complete();
                            if (handleHolder[0] != null) {
                                handleHolder[0].markFinished();
                            }
                            activeChatRegistry.unregister(sessId);
                        })
                        .subscribe();

                // 注册句柄: 支持主动中断 + 客户端断开时自动取消上游模型调用
                ActiveChatRegistry.ChatHandle handle = new ActiveChatRegistry.ChatHandle(sink, upstream);
                // 中断回调: 下发 interrupted + 用已累积文本估算 token 的 done(模型 usage 中途断开时拿不到)
                handle.setOnInterrupt(() -> {
                    int estP = tokenEstimator.estimate(contextForEstimate);
                    int estC = tokenEstimator.estimate(accumulated.toString())
                            + tokenEstimator.estimate(reasoningAccumulated.toString());
                    int estT = estP + estC;
                    long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                    sink.next(ChatChunk.interrupted());
                    sink.next(ChatChunk.done(estP, estC, estT, durationMs));
                    sink.complete();
                    activeChatRegistry.unregister(sessId);
                });
                handleHolder[0] = handle;
                activeChatRegistry.register(sessId, handle);
                sink.onDispose(() -> {
                    // 客户端断开 SSE 连接时, 取消上游 HTTP 请求, 避免孤儿流继续消耗 token
                    if (!upstream.isDisposed()) {
                        upstream.dispose();
                    }
                    activeChatRegistry.unregister(sessId);
                });
            } catch (Exception e) {
                log.error("编排失败", e);
                sink.next(ChatChunk.error("系统错误: " + e.getMessage(), "INTERNAL_ERROR"));
                sink.complete();
            }
        });
    }

    /**
     * 同步对话
     */
    public ChatResponse chatSync(ChatRequest request) {
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            SessionDTO session = sessionManager.createSession("新对话", request.getModel(), null);
            sessionId = session.getId();
            request.setSessionId(sessionId);
        }

        List<Message> history = memory.load(sessionId);
        ChatModelProvider provider = router.route(request, history);
        boolean firstMessage = history.stream().noneMatch(m -> "user".equals(m.getRole()));
        memory.save(sessionId, Message.user(request.getMessage()));
        if (firstMessage) {
            sessionManager.autoTitleFromFirstMessage(sessionId, request.getMessage());
        }

        ChatResponse response = provider.invoke(request, history);
        Integer usage = response.getTokenUsage() != null ? response.getTokenUsage().getTotal() : null;
        memory.save(sessionId, Message.assistant(response.getContent(), null, provider.getName(), usage));

        sessionManager.touch(sessionId);

        return response;
    }
}
