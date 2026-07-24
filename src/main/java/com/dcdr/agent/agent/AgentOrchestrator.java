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
import com.dcdr.agent.model.registry.ModelRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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
    private final ModelRegistry registry;
    private final ImageResolver imageResolver;

    public AgentOrchestrator(ModelRouter router, ChatMemory memory, SessionManager sessionManager,
                             ActiveChatRegistry activeChatRegistry, TokenEstimator tokenEstimator,
                             ModelRegistry registry, ImageResolver imageResolver) {
        this.router = router;
        this.memory = memory;
        this.sessionManager = sessionManager;
        this.activeChatRegistry = activeChatRegistry;
        this.tokenEstimator = tokenEstimator;
        this.registry = registry;
        this.imageResolver = imageResolver;
    }

    /**
     * 流式对话
     */
    /** 流式对话准备阶段的结果: 在弹性线程完成阻塞工作(含图片识别)后, 传给流式主链路 */
    private record ChatContext(
            String sessId,
            List<Message> history,
            ChatModelProvider provider,
            String imageFallbackWarning,
            List<Message> contextForEstimate,
            boolean nearLimit,
            int estTokens
    ) {}

    /**
     * 流式对话
     * <p>
     * 设计: 阻塞的准备阶段(会话/记忆/路由/图片识别)放在 boundedElastic 线程执行;
     * 流式主链路通过 flatMapMany 回到 parallel 线程, 不再占用弹性线程(弹性线程仅被占用"识别那几秒")。
     */
    public Flux<ChatChunk> chat(ChatRequest request) {
        // 带图片时, 准备阶段可能需要视觉模型识别图片(耗时数秒), 期间前端收不到任何数据会显得"卡住";
        // 先立即下发一个状态提示, 让用户知道后台正在处理。
        boolean hasImages = request.getImages() != null && !request.getImages().isEmpty();

        Flux<ChatChunk> pipeline = Mono.fromCallable(() -> prepareForChat(request))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(ctx -> Flux.<ChatChunk>create(sink -> {
                    try {
                        final String sessId = ctx.sessId();
                        final List<Message> history = ctx.history();
                        final ChatModelProvider provider = ctx.provider();
                        final List<Message> contextForEstimate = ctx.contextForEstimate();

                        // 计时起点(用于统计模型生成耗时, 不含图片识别预处理)
                        final long startNanos = System.nanoTime();

                        // 上下文接近上限提示: 用「将发送给模型的完整上下文」做估算, 在调用模型前先下发, 不阻断对话
                        if (ctx.nearLimit()) {
                            sink.next(ChatChunk.contextWarning(
                                    String.format("对话已较长（约 %d token），接近上下文上限，建议开启新对话以免丢失早期内容。",
                                            ctx.estTokens())));
                        }
                        // 图片自动路由降级提示(已用视觉模型识别后作答)
                        if (ctx.imageFallbackWarning() != null) {
                            sink.next(ChatChunk.contextWarning(ctx.imageFallbackWarning()));
                        }

                        // 流式调用模型
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
                                    // 统计 token: 优先模型真实 usage, 否则用已累积文本估算兜底(杜绝"0 token")
                                    ChatResponse.TokenUsage u = usageHolder[0];
                                    int p = u != null ? u.getPrompt() : tokenEstimator.estimate(contextForEstimate);
                                    int c = u != null ? u.getCompletion()
                                            : tokenEstimator.estimate(accumulated.toString())
                                                + tokenEstimator.estimate(reasoningAccumulated.toString());
                                    int t = u != null ? u.getTotal() : (p + c);
                                    long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                                    // 保存助手回复(含推理内容 + token 消耗)到记忆
                                    String fr = finishReasonHolder[0];
                                    Message assistantMessage = Message.assistant(accumulated.toString(),
                                            reasoningAccumulated.toString(), provider.getName(), t);
                                    if ("insufficient_system_resource".equals(fr) || "content_filter".equals(fr)) {
                                        assistantMessage.setError(true);
                                    }
                                    memory.save(sessId, assistantMessage);
                                    // 更新会话时间
                                    sessionManager.touch(sessId);
                                    sink.next(ChatChunk.done(p, c, t, durationMs));
                                    // 根据 finish_reason 补充提示(截断/被打断不再伪装成正常完成)
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
                                    // 持久化失败消息, 便于前端展示并支持重试
                                    Message errMsg = Message.assistant("模型调用失败: " + e.getMessage(), null,
                                            provider != null ? provider.getName() : null, null);
                                    errMsg.setError(true);
                                    memory.save(sessId, errMsg);
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
                }).subscribeOn(Schedulers.parallel()));

        // 有图片时在管线前拼接"正在识别图片"状态提示(立即发出, 不阻塞)
        Flux<ChatChunk> result = hasImages
                ? Flux.concat(Flux.just(ChatChunk.status("正在识别图片，请稍候…")), pipeline)
                : pipeline;

        return result.onErrorResume(e -> Flux.just(ChatChunk.error("系统错误: " + e.getMessage(), "INTERNAL_ERROR")));
    }

    /**
     * 阻塞的准备阶段(在 boundedElastic 线程执行):
     * 确保会话、加载记忆、路由决策、保存用户消息、图片自动路由(含视觉识别降级)、上下文上限估算。
     * 结果封装为 ChatContext 交给流式主链路(此时 request 中的图片已被解析为 base64 / 识别文本已注入)。
     */
    private ChatContext prepareForChat(ChatRequest request) {
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

        // 4. 保存用户消息到记忆(含上传图片)
        boolean firstMessage = history.stream().noneMatch(m -> "user".equals(m.getRole()));
        memory.save(sessId, Message.user(request.getMessage(), request.getImages()));
        if (firstMessage) {
            sessionManager.autoTitleFromFirstMessage(sessId, request.getMessage());
        }

        // 4.1 图片自动路由: 解析本地图片为 base64, 并对不支持视觉的模型先做图片识别降级(阻塞调用)
        String imageFallbackWarning = prepareImagesForModel(request, history, provider);

        // 2.1 上下文上限估算(用于调用模型前的提示)
        List<Message> contextForEstimate = new ArrayList<>(history);
        contextForEstimate.add(Message.user(request.getMessage()));
        boolean nearLimit = tokenEstimator.isNearLimit(contextForEstimate);
        int est = nearLimit ? tokenEstimator.estimate(contextForEstimate) : 0;

        return new ChatContext(sessId, history, provider, imageFallbackWarning, contextForEstimate, nearLimit, est);
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
        memory.save(sessionId, Message.user(request.getMessage(), request.getImages()));
        if (firstMessage) {
            sessionManager.autoTitleFromFirstMessage(sessionId, request.getMessage());
        }

        // 图片自动路由: 解析本地图片为 base64, 并对不支持视觉的模型先做图片识别降级
        String imageFallbackWarning = prepareImagesForModel(request, history, provider);

        ChatResponse response = provider.invoke(request, history);
        if (imageFallbackWarning != null) {
            response.setContextWarning(imageFallbackWarning);
        }
        Integer usage = response.getTokenUsage() != null ? response.getTokenUsage().getTotal() : null;
        memory.save(sessionId, Message.assistant(response.getContent(), null, provider.getName(), usage));

        sessionManager.touch(sessionId);

        return response;
    }

    /**
     * 图片自动路由与可访问性处理:
     * 1. 将本地相对路径图片解析为 base64 data URL(无论哪种模型, 保证视觉模型能读到)
     * 2. 若当前模型不支持视觉但用户上传了图片 → 自动用视觉模型把图片识别成文字描述,
     *    注入当前用户消息文本并清空 images(降级为纯文本模型处理), 避免图片被静默丢弃
     */
    /** 返回降级提示文案(若有), 无降级则返回 null */
    private String prepareImagesForModel(ChatRequest request, List<Message> history, ChatModelProvider provider) {
        // 1. 解析当前消息图片为可访问的 base64 形式
        List<String> resolved = imageResolver.resolveForModel(request.getImages());
        request.setImages(resolved);

        // 2. 解析历史消息中的图片(供视觉模型多轮上下文使用)
        for (Message m : history) {
            if (m.getImages() != null && !m.getImages().isEmpty() && "user".equals(m.getRole())) {
                m.setImages(imageResolver.resolveForModel(m.getImages()));
            }
        }

        // 3. 自动路由降级: 当前模型不支持视觉但有图片
        if (provider.supportsVision() || resolved == null || resolved.isEmpty()) {
            return null;
        }
        ChatModelProvider vision = registry.getFirstVisionChatProvider();
        if (vision == null) {
            log.warn("用户上传了图片, 但当前模型[{}]不支持视觉且无可用视觉模型, 图片将被忽略", provider.getName());
            return null;
        }
        StringBuilder desc = new StringBuilder();
        int idx = 1;
        for (String img : resolved) {
            try {
                String d = recognizeImage(vision, img);
                if (d != null && !d.isBlank()) {
                    desc.append("\n[图片 ").append(idx).append(" 识别结果]: ").append(d.trim());
                }
            } catch (Exception e) {
                log.warn("图片识别失败(降级跳过): {}", img, e);
            }
            idx++;
        }
        if (desc.length() > 0) {
            String aug = (request.getMessage() == null ? "" : request.getMessage())
                    + "\n\n[以下是用户上传图片的识别内容, 请基于这些信息回答问题]:" + desc;
            request.setMessage(aug);
            request.setImages(null); // 纯文本模型不再携带图片
            log.info("已用视觉模型[{}]识别 {} 张图片, 注入文本模型[{}]", vision.getName(), resolved.size(), provider.getName());
            return String.format("当前模型「%s」不支持直接看图，已用视觉模型「%s」先将图片内容识别为文字，再交由该模型作答；"
                    + "模型无法直接引用原图中的细节，如需更精确的视觉理解，请切换到支持看图的模型。",
                    provider.getName(), vision.getName());
        }
        return null;
    }

    /** 用视觉模型识别单张图片, 返回文字描述 */
    private String recognizeImage(ChatModelProvider vision, String img) {
        ChatRequest r = new ChatRequest();
        r.setModel(vision.getName());
        r.setMessage("请尽可能详细地描述这张图片: 包括图中所有可见文字(OCR)、图表与数据、界面布局、物体与颜色等。"
                + "只输出图片本身的描述, 不要回答任何问题。");
        r.setImages(List.of(img));
        ChatResponse resp = vision.invoke(r, List.of());
        return resp != null ? resp.getContent() : null;
    }
}
