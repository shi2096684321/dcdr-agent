package com.example.agent.agent;

import com.example.agent.agent.memory.ChatMemory;
import com.example.agent.agent.memory.Message;
import com.example.agent.agent.session.SessionManager;
import com.example.agent.dto.ChatChunk;
import com.example.agent.dto.ChatRequest;
import com.example.agent.dto.ChatResponse;
import com.example.agent.dto.SessionDTO;
import com.example.agent.model.provider.ChatModelProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

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

    public AgentOrchestrator(ModelRouter router, ChatMemory memory, SessionManager sessionManager) {
        this.router = router;
        this.memory = memory;
        this.sessionManager = sessionManager;
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

                // 2. 加载记忆
                List<Message> history = memory.load(sessId);
                log.debug("会话 {} 加载记忆 {} 条", sessId, history.size());

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
                provider.stream(request)
                        .doOnNext(chunk -> {
                            sink.next(chunk);
                            if ("chunk".equals(chunk.getType()) && chunk.getContent() != null) {
                                accumulated.append(chunk.getContent());
                            }
                        })
                        .doOnComplete(() -> {
                            // 6. 保存助手回复到记忆
                            memory.save(sessId, Message.assistant(accumulated.toString(), provider.getName()));
                            // 7. 更新会话时间
                            sessionManager.touch(sessId);
                            // 8. 发送完成信号
                            sink.next(ChatChunk.done(0, 0, 0));
                            sink.complete();
                            log.info("会话 {} 对话完成", sessId);
                        })
                        .doOnError(e -> {
                            log.error("会话 {} 对话失败", sessId, e);
                            sink.next(ChatChunk.error("模型调用失败: " + e.getMessage(), "MODEL_ERROR"));
                            sink.complete();
                        })
                        .subscribe();
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

        ChatResponse response = provider.invoke(request);
        memory.save(sessionId, Message.assistant(response.getContent(), provider.getName()));

        sessionManager.touch(sessionId);

        return response;
    }
}
