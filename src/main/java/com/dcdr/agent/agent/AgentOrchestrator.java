package com.dcdr.agent.agent;

import com.dcdr.agent.agent.memory.ChatMemory;
import com.dcdr.agent.agent.memory.Message;
import com.dcdr.agent.agent.memory.TokenEstimator;
import com.dcdr.agent.agent.session.SessionManager;
import com.dcdr.agent.dto.ChatChunk;
import com.dcdr.agent.dto.ChatRequest;
import com.dcdr.agent.dto.ChatResponse;
import com.dcdr.agent.dto.SessionDTO;
import com.dcdr.agent.agent.image.ChatImageService;
import com.dcdr.agent.dto.ChatImageRequest;
import com.dcdr.agent.model.provider.ChatModelProvider;
import com.dcdr.agent.model.registry.ModelRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final ChatImageService chatImageService;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgentOrchestrator(ModelRouter router, ChatMemory memory, SessionManager sessionManager,
                             ActiveChatRegistry activeChatRegistry, TokenEstimator tokenEstimator,
                             ModelRegistry registry, ImageResolver imageResolver, ChatImageService chatImageService) {
        this.router = router;
        this.memory = memory;
        this.sessionManager = sessionManager;
        this.activeChatRegistry = activeChatRegistry;
        this.tokenEstimator = tokenEstimator;
        this.registry = registry;
        this.imageResolver = imageResolver;
        this.chatImageService = chatImageService;
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
            String imageUnrecognizedError,
            String visionModel,
            String imageStatus,
            String imageCaption,
            String imageTokenUsage,
            List<Message> contextForEstimate,
            boolean nearLimit,
            int estTokens
    ) {}

    /** 图片预处理结果(含路由元信息, 用于落库与前端提示) */
    private record ImagePrep(
            String fallbackWarning,     // 切换到视觉模型直接回答的提示
            String unrecognizedError,   // 识别失败提示(需直接提示并中止作答)
            String visionModel,         // 最终回答的视觉模型名
            String imageStatus,         // none(无图/忽略) / vision(视觉模型直接回答)
            String imageCaption,        // 预留(直接回答模式下为空)
            String imageTokenUsage,     // 预留(直接回答模式下为空)
            ChatModelProvider answerProvider  // 最终用于回答的模型(视觉模型直接回答时为视觉模型)
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

                        // 图片识别失败: 直接提示并结束, 不再调用模型作答(避免文本模型基于缺失图片硬答)
                        if (ctx.imageUnrecognizedError() != null) {
                            sink.next(ChatChunk.error(ctx.imageUnrecognizedError(), "IMAGE_UNRECOGNIZED"));
                            sink.complete();
                            activeChatRegistry.unregister(sessId);
                            return;
                        }

                        // 上下文接近上限提示: 用「将发送给模型的完整上下文」做估算, 在调用模型前先下发, 不阻断对话
                        if (ctx.nearLimit()) {
                            sink.next(ChatChunk.contextWarning(
                                    String.format("对话已较长（约 %d token），接近上下文上限，建议开启新对话以免丢失早期内容。",
                                            ctx.estTokens())));
                        }
                        // 图片自动路由降级提示(已切换视觉模型直接作答)
                        if (ctx.imageFallbackWarning() != null) {
                            sink.next(ChatChunk.contextWarning(ctx.imageFallbackWarning()));
                        }
                        // 图片识别过程提示: 视觉模型从读图到产出首个 token 需数秒,
                        // 先下发识别中状态, 避免前端在此空窗期显得"卡住"
                        if ("vision".equals(ctx.imageStatus())) {
                            sink.next(ChatChunk.status("正在识别图片内容，请稍候…"));
                        }

                        // 若模型支持函数调用且已配置生图模型, 注入 image_generation 工具, 支持 AI 自动文生图
                        if (provider.supportsFunctionCalling() && chatImageService != null
                                && registry.getDefaultImageProvider() != null) {
                            request.setTools(Collections.singletonList(chatImageService.getToolDefinition()));
                        }

                        // 流式调用模型
                        StringBuilder accumulated = new StringBuilder();
                        StringBuilder reasoningAccumulated = new StringBuilder();
                        // 防止正常完成与中断并发时重复保存助手消息(只保存一条)
                        final AtomicBoolean assistantSaved = new AtomicBoolean(false);
                        // handle 引用需在回调中访问, 用数组持有以满足 effectively final
                        final ActiveChatRegistry.ChatHandle[] handleHolder = new ActiveChatRegistry.ChatHandle[1];
                        // 捕获模型真实返回的 token 消耗与结束原因, 用于回填 done 块
                        final ChatResponse.TokenUsage[] usageHolder = new ChatResponse.TokenUsage[1];
                        final String[] finishReasonHolder = new String[1];
                        // 收集模型发起的工具调用(自动文生图)与生成结果
                        final List<ChatChunk> toolCalls = new ArrayList<>();
                        final List<String> generatedUrls = new ArrayList<>();
                        // 本轮实际生图的内容摘要(prompt), 供落库陈述句使用, 让下一轮模型明确"这些图片已生成过"
                        final List<String> generatedPrompts = new ArrayList<>();
                        final String[] toolError = new String[1];

                        // 主链路: 转发模型流式分块并采集 usage / 文本 / 工具调用
                        Flux<ChatChunk> upstream = provider.stream(request, history)
                                .doOnNext(chunk -> {
                                    if ("usage".equals(chunk.getType()) && chunk.getTokenUsage() != null) {
                                        usageHolder[0] = chunk.getTokenUsage();
                                        if (chunk.getFinishReason() != null) {
                                            finishReasonHolder[0] = chunk.getFinishReason();
                                        }
                                        return;
                                    }
                                    if ("tool_call".equals(chunk.getType())) {
                                        toolCalls.add(chunk);
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
                                });

                        // 工具执行阶段(文生图, 含阻塞 IO, 走弹性线程)
                        Flux<ChatChunk> toolFlux = Flux
                                .defer(() -> executeImageTools(toolCalls, accumulated.toString(),
                                        reasoningAccumulated.toString(), generatedUrls, generatedPrompts, toolError))
                                .subscribeOn(Schedulers.boundedElastic());

                        // 收尾阶段: 保存助手消息(含图片)并下发 done + 结束原因提示
                        Flux<ChatChunk> finalizeFlux = Flux.defer(() -> finalizeAssistant(sessId, provider,
                                accumulated, reasoningAccumulated, toolCalls, generatedUrls, generatedPrompts, toolError,
                                usageHolder, finishReasonHolder, startNanos, contextForEstimate, assistantSaved));

                        Disposable upstreamSub = upstream
                                .concatWith(toolFlux)
                                .concatWith(finalizeFlux)
                                .subscribe(
                                        sink::next,
                                        e -> {
                                            log.error("会话 {} 对话失败", sessId, e);
                                            sink.next(ChatChunk.error("模型调用失败: " + e.getMessage(), "MODEL_ERROR"));
                                            sink.complete();
                                            // 落库失败消息改为异步尽力而为, 不阻塞 SSE 关闭,
                                            // 避免 DB 锁/慢查询导致前端收不到结束信号、发送按钮卡在"取消"
                                            try {
                                                Message errMsg = Message.assistant("模型调用失败: " + e.getMessage(),
                                                        null, provider != null ? provider.getName() : null, null);
                                                errMsg.setError(true);
                                                Message toSave = errMsg;
                                                Mono.fromCallable(() -> { memory.save(sessId, toSave); return null; })
                                                        .subscribeOn(Schedulers.boundedElastic())
                                                        .timeout(Duration.ofSeconds(10))
                                                        .doOnError(ex -> log.error("保存失败消息异常", ex))
                                                        .subscribe();
                                            } catch (Exception ex) {
                                                log.error("保存失败消息异常", ex);
                                            }
                                            if (handleHolder[0] != null) {
                                                handleHolder[0].markFinished();
                                            }
                                            activeChatRegistry.unregister(sessId);
                                        },
                                        () -> {
                                            if (handleHolder[0] != null) {
                                                handleHolder[0].markFinished();
                                            }
                                            activeChatRegistry.unregister(sessId);
                                            log.info("会话 {} 对话完成", sessId);
                                        }
                                );

                        // 注册句柄: 支持主动中断 + 客户端断开时自动取消上游模型调用
                        ActiveChatRegistry.ChatHandle handle = new ActiveChatRegistry.ChatHandle(sink, upstreamSub);
                        // 中断回调: 下发 interrupted + 用已累积文本估算 token 的 done(模型 usage 中途断开时拿不到)
                        handle.setOnInterrupt(() -> {
                            int estP = tokenEstimator.estimate(contextForEstimate);
                            int estC = tokenEstimator.estimate(accumulated.toString())
                                    + tokenEstimator.estimate(reasoningAccumulated.toString());
                            int estT = estP + estC;
                            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                            // 手动终止也要保留已生成的部分(即使不完整)
                            String intC = accumulated.toString();
                            String intR = reasoningAccumulated.toString();
                            if (!intC.isEmpty() || !intR.isEmpty()) {
                                if (assistantSaved.compareAndSet(false, true)) {
                                    Message partial = Message.assistant(intC, intR, provider.getName(), estT);
                                    partial.setInterrupted(true);
                                    // 异步落库, 不阻塞 SSE 结束
                                    Message toSave = partial;
                                    Mono.fromCallable(() -> { memory.save(sessId, toSave); return null; })
                                            .subscribeOn(Schedulers.boundedElastic())
                                            .timeout(Duration.ofSeconds(10))
                                            .doOnError(ex -> log.error("保存中断消息异常", ex))
                                            .subscribe();
                                }
                            }
                            sink.next(ChatChunk.interrupted());
                            sink.next(ChatChunk.done(estP, estC, estT, durationMs));
                            sink.complete();
                            activeChatRegistry.unregister(sessId);
                            log.info("会话 {} 手动中断, 已保留部分生成内容", sessId);
                        });
                        handleHolder[0] = handle;
                        activeChatRegistry.register(sessId, handle);
                        sink.onDispose(() -> {
                            // 客户端断开 SSE 连接时, 取消上游 HTTP 请求, 避免孤儿流继续消耗 token
                            if (!upstreamSub.isDisposed()) {
                                upstreamSub.dispose();
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
                ? Flux.concat(Flux.just(ChatChunk.status("正在处理图片，请稍候…")), pipeline)
                : pipeline;

        return result.onErrorResume(e -> Flux.just(ChatChunk.error("系统错误: " + e.getMessage(), "INTERNAL_ERROR")));
    }

    /**
     * 对话内文生图(按钮触发): 即时流式返回生成的图片, 并落库用户消息与助手图片消息
     */
    public Flux<ChatChunk> generateImageInChat(ChatImageRequest req) {
        String sessionId = req.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            SessionDTO session = sessionManager.createSession("新对话", null, null);
            sessionId = session.getId();
            req.setSessionId(sessionId);
        }
        final String sessId = sessionId;

        // 确保会话存在并落库用户消息
        List<Message> history = memory.load(sessId);
        boolean firstMessage = history.stream().noneMatch(m -> "user".equals(m.getRole()));
        Message userMsg = Message.user(req.getPrompt());
        memory.save(sessId, userMsg);
        if (firstMessage) {
            sessionManager.autoTitleFromFirstMessage(sessId, req.getPrompt());
        }

        // 校验生图模型是否已配置
        if (registry.getDefaultImageProvider() == null) {
            Message err = Message.assistant("未配置图片生成模型，无法使用文生图功能。", null, null, null);
            err.setError(true);
            memory.save(sessId, err);
            return Flux.just(ChatChunk.error("未配置图片生成模型，无法使用文生图功能。", "NO_IMAGE_MODEL"));
        }
        final String modelName = registry.getDefaultImageProvider().getName();

        // 等待提示先于阻塞生图下发, 保证前端先看到"正在生成", 而非图片生成完才提示
        return Flux.concat(
                Flux.just(ChatChunk.status("正在生成图片，请稍候…")),
                Mono.fromCallable(() -> chatImageService.generate(req.getPrompt(), req.getSize(), req.getStyle()))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMapMany(urls -> {
                            if (urls == null || urls.isEmpty()) {
                                return Flux.just(ChatChunk.error("图片生成失败：模型未返回图片", "IMAGE_GEN_ERROR"));
                            }
                            List<ChatChunk> chunks = new ArrayList<>();
                            for (String u : urls) {
                                chunks.add(ChatChunk.image(u));
                            }
                            return Flux.fromIterable(chunks).concatWith(Flux.defer(() -> {
                                Message m = Message.assistantImage(urls, "", modelName);
                                memory.save(sessId, m);
                                sessionManager.touch(sessId);
                                return Flux.just(ChatChunk.done(0, 0, 0, 0));
                            }));
                        })
                        .onErrorResume(e -> {
                            Message err = Message.assistant("图片生成失败: " + e.getMessage(), null, modelName, null);
                            err.setError(true);
                            memory.save(sessId, err);
                            return Flux.just(ChatChunk.error("图片生成失败: " + e.getMessage(), "IMAGE_GEN_ERROR"));
                        })
        );
    }

    /**
     * 平台级生图约束(不持久化, 仅在向模型发起请求时注入为 system 消息):
     * 约束模型只生成本轮新请求的图片, 不为历史已完成的图片重复生图(根治历史诱导重画)。
     */
    private static final String IMAGE_GENERATION_POLICY =
            "文生图策略：只生成用户本轮明确新请求的图片。历史对话中已生成过的图片视为已完成, "
            + "不要重复生成; 若用户本轮要求对历史图片进行修改, 应明确说明修改意图后再生成。";

    /**
     * 执行对话内的文生图工具调用(image_generation):
     * 逐个解析工具入参并调用生图服务, 返回图片分块(image), 并把生成的 URL 回填到 outUrls。
     */
    private Flux<ChatChunk> executeImageTools(List<ChatChunk> toolCalls, String accumulatedText,
                                             String reasoningText, List<String> outUrls,
                                             List<String> outPrompts, String[] outError) {
        // 仅当本轮确有生图调用时, 才下发"正在生成"等待提示(避免普通对话误发)
        boolean hasImageCall = toolCalls.stream()
                .anyMatch(tc -> "image_generation".equals(tc.getTool()));
        if (!hasImageCall) {
            return Flux.empty();
        }
        // status 先即时下发, 生图阻塞放入 defer 之后执行, 保证前端先看到等待提示再看到结果
        return Flux.concat(
                Flux.just(ChatChunk.status("正在生成图片，请稍候…")),
                Flux.defer(() -> {
                    List<String> all = new ArrayList<>();
                    int callCount = 0;
                    for (ChatChunk tc : toolCalls) {
                        if (!"image_generation".equals(tc.getTool())) {
                            continue;
                        }
                        callCount++;
                        try {
                            Map<String, Object> args = new LinkedHashMap<>();
                            if (tc.getArgs() != null && !tc.getArgs().isBlank()) {
                                args = mapper.readValue(tc.getArgs(), Map.class);
                            }
                            Object p = args.get("prompt");
                            String prompt = p != null ? p.toString() : null;
                            if (prompt == null || prompt.isBlank()) {
                                prompt = accumulatedText; // 兜底用已累积文本作为提示词
                            }
                            Object s = args.get("size");
                            Object st = args.get("style");
                            List<String> urls = chatImageService.generate(
                                    prompt, s != null ? s.toString() : null, st != null ? st.toString() : null);
                            if (urls != null) {
                                all.addAll(urls);
                            }
                            outPrompts.add(prompt);   // 记录本轮实际生图内容, 供落库陈述句使用
                        } catch (Exception e) {
                            outError[0] = e.getMessage();
                            log.error("对话内文生图工具执行失败", e);
                            return Flux.just(ChatChunk.error("图片生成失败: " + e.getMessage(), "IMAGE_GEN_ERROR"));
                        }
                    }
                    if (callCount > 1) {
                        // 历史形态修正 + system 约束后, 多余调用本不该发生; 若仍多发, 说明用户确需多张, 全部执行不丢弃
                        log.info("本轮检测到 {} 个 image_generation 调用, 全部执行(不丢弃)", callCount);
                    }
                    if (all.isEmpty()) {
                        return Flux.empty();
                    }
                    outUrls.addAll(all);
                    List<ChatChunk> chunks = new ArrayList<>();
                    for (String u : all) {
                        chunks.add(ChatChunk.image(u));
                    }
                    return Flux.fromIterable(chunks);
                })
        );
    }

    /**
     * 收尾阶段: 根据是否有工具调用与生成结果, 保存助手消息(普通文本 / 带图),
     * 并下发 done 分块与 finish_reason 相关提示。
     */
    private Flux<ChatChunk> finalizeAssistant(String sessId, ChatModelProvider provider,
                                             StringBuilder accumulated, StringBuilder reasoning,
                                             List<ChatChunk> toolCalls, List<String> urls, List<String> prompts, String[] toolError,
                                             ChatResponse.TokenUsage[] usageHolder, String[] finishReasonHolder,
                                             long startNanos, List<Message> contextForEstimate,
                                             AtomicBoolean assistantSaved) {
        ChatResponse.TokenUsage u = usageHolder[0];
        int p = u != null ? u.getPrompt() : tokenEstimator.estimate(contextForEstimate);
        int c = u != null ? u.getCompletion()
                : tokenEstimator.estimate(accumulated.toString()) + tokenEstimator.estimate(reasoning.toString());
        int t = u != null ? u.getTotal() : (p + c);
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        String fr = finishReasonHolder[0];

        // 仅构建待落库消息(不在此处阻塞 SSE)
        Message assistantMessage = null;
        // 生图轮给下一轮模型的陈述句(仅追加部分): 同时回传前端, 保证实时流与刷新后内容一致
        final String[] appendedStatement = { null };
        if (assistantSaved.compareAndSet(false, true)) {
            if (!toolCalls.isEmpty()) {
                if (!urls.isEmpty()) {
                    // 落库为陈述句形态(含本轮生图内容摘要), 让下一轮模型明确"这些图片已生成过",
                    // 避免历史诱导模型把已生成图片当成本轮目标重复生成。
                    String content = accumulated.toString();
                    if (!prompts.isEmpty()) {
                        StringBuilder sb = new StringBuilder();
                        if (content != null && !content.isBlank()) {
                            sb.append(content).append("\n");
                        }
                        String stmt = "已生成图片：" + String.join("、", prompts) + "。";
                        sb.append(stmt);
                        appendedStatement[0] = stmt;
                        content = sb.toString();
                    }
                    assistantMessage = Message.assistantImage(urls, content, provider.getName());
                    // 关键: 生图轮也要携带推理内容, 否则刷新后会丢失(assistantImage 工厂不设置 reasoningContent)
                    assistantMessage.setReasoningContent(reasoning.toString());
                } else {
                    // 工具被调用但未产出图片(失败): 退化为文本/错误消息
                    String content = accumulated.toString();
                    if (content.isEmpty() && toolError[0] != null) {
                        content = "图片生成失败：" + toolError[0];
                    }
                    assistantMessage = Message.assistant(content, reasoning.toString(), provider.getName(), t);
                    if (toolError[0] != null) {
                        assistantMessage.setError(true);
                    }
                }
            } else {
                assistantMessage = Message.assistant(accumulated.toString(), reasoning.toString(), provider.getName(), t);
            }
            if ("insufficient_system_resource".equals(fr) || "content_filter".equals(fr)) {
                assistantMessage.setError(true);
            }
        }

        // 落库与会话时间戳更新改为异步尽力而为, 不阻塞 SSE 结束信号;
        // 否则 DB 锁/慢查询会卡住 SSE 关闭, 前端一直收不到 done、发送按钮卡在"取消"
        if (assistantMessage != null) {
            Message toSave = assistantMessage;
            Mono.fromCallable(() -> { memory.save(sessId, toSave); return null; })
                    .subscribeOn(Schedulers.boundedElastic())
                    .timeout(Duration.ofSeconds(10))
                    .doOnError(ex -> log.error("保存助手消息异常", ex))
                    .subscribe();
        }
        Mono.fromCallable(() -> { sessionManager.touch(sessId); return null; })
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(Duration.ofSeconds(10))
                .doOnError(ex -> log.error("更新会话时间戳异常", ex))
                .subscribe();

        List<ChatChunk> tail = new ArrayList<>();
        // 把落库陈述句回传给前端(仅追加部分), 使实时流与刷新后持久化内容一致, 避免"刷新后多一句已生成图片"
        if (appendedStatement[0] != null) {
            tail.add(ChatChunk.chunk(appendedStatement[0]));
        }
        tail.add(ChatChunk.done(p, c, t, durationMs));
        if ("length".equals(fr)) {
            tail.add(ChatChunk.contextWarning("回答已达长度上限(max_tokens)被截断，可尝试简化问题或开启新对话。"));
        } else if ("insufficient_system_resource".equals(fr)) {
            tail.add(ChatChunk.error("生成因后端推理资源不足被打断", "RESOURCE_LIMIT"));
        } else if ("content_filter".equals(fr)) {
            tail.add(ChatChunk.contextWarning("回答触发安全策略过滤。"));
        }
        return Flux.fromIterable(tail);
    }

    /**
     * 阻塞的准备阶段(在 boundedElastic 线程执行):
     * 确保会话、加载记忆、路由决策、保存用户消息、图片自动路由(不支持视觉时直接切换视觉模型回答)、上下文上限估算。
     * 结果封装为 ChatContext 交给流式主链路(此时 request 中的图片已被解析为 base64, 最终回答模型已确定)。
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

        // 3.1 平台级生图约束(仅当本轮会注入生图工具时前置为 system 消息, 不落库):
        //     与 executeImageTools 的协议层硬收敛(每轮最多一张)形成「软 + 硬」双保险,
        //     从源头降低模型因历史图片而重复调用 image_generation。
        if (provider.supportsFunctionCalling() && registry.getDefaultImageProvider() != null) {
            boolean hasPolicy = history.stream()
                    .anyMatch(m -> "system".equals(m.getRole()) && IMAGE_GENERATION_POLICY.equals(m.getContent()));
            if (!hasPolicy) {
                List<Message> withPolicy = new ArrayList<>(history);
                withPolicy.add(0, Message.system(IMAGE_GENERATION_POLICY));
                history = withPolicy;
            }
        }

        // 4.1 图片自动路由(必须先执行: 会 mutate request —— 解析 base64 / 决定最终回答模型)
        //      —— 必须在保存用户消息之前完成, 以便把路由元信息(模型/状态)一并落库
        String userOriginal = request.getMessage();   // 捕获用户原话(模型路由前)
        List<String> originalImages = request.getImages();  // 捕获原始图片(相对路径/url), 单独用于落库; 模型侧统一用解析后的 base64
        ImagePrep prep = prepareImagesForModel(request, history, provider);
        provider = prep.answerProvider();  // 最终回答模型(不支持视觉时切换为视觉模型, 直接看图回答)
        String imageFallbackWarning = prep.fallbackWarning();
        String imageUnrecognizedError = prep.unrecognizedError();

        // 4. 保存用户消息到记忆(含图片路由元信息; content 存用户原话, imageCaption 直接回答模式下为空)
        boolean firstMessage = history.stream().noneMatch(m -> "user".equals(m.getRole()));
        Message userMsg = Message.user(userOriginal, originalImages);
        userMsg.setVisionModel(prep.visionModel());
        userMsg.setImageStatus(prep.imageStatus());
        userMsg.setImageCaption(prep.imageCaption());
        userMsg.setImageTokenUsage(prep.imageTokenUsage());
        memory.save(sessId, userMsg);
        if (firstMessage) {
            sessionManager.autoTitleFromFirstMessage(sessId, userOriginal);
        }

        // 2.1 上下文上限估算(用于调用模型前的提示)
        List<Message> contextForEstimate = new ArrayList<>(history);
        contextForEstimate.add(Message.user(request.getMessage()));
        boolean nearLimit = tokenEstimator.isNearLimit(contextForEstimate);
        int est = nearLimit ? tokenEstimator.estimate(contextForEstimate) : 0;

        return new ChatContext(sessId, history, provider, imageFallbackWarning, imageUnrecognizedError,
                prep.visionModel(), prep.imageStatus(), prep.imageCaption(), prep.imageTokenUsage(),
                contextForEstimate, nearLimit, est);
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

        // 图片自动路由(必须先执行, 因其会 mutate request 并决定最终回答模型)
        String userOriginal = request.getMessage();
        List<String> originalImages = request.getImages();  // 捕获原始图片(相对路径/url), 单独用于落库; 模型侧统一用解析后的 base64
        ImagePrep prepSync = prepareImagesForModel(request, history, provider);
        String imageFallbackWarning = prepSync.fallbackWarning();

        boolean firstMessage = history.stream().noneMatch(m -> "user".equals(m.getRole()));
        Message userMsg = Message.user(userOriginal, originalImages);
        userMsg.setVisionModel(prepSync.visionModel());
        userMsg.setImageStatus(prepSync.imageStatus());
        userMsg.setImageCaption(prepSync.imageCaption());
        userMsg.setImageTokenUsage(prepSync.imageTokenUsage());
        memory.save(sessionId, userMsg);
        if (firstMessage) {
            sessionManager.autoTitleFromFirstMessage(sessionId, userOriginal);
        }

        // 图片未能识别: 直接返回提示, 不再调用模型作答
        if (prepSync.unrecognizedError() != null) {
            ChatResponse r = new ChatResponse();
            r.setContent(prepSync.unrecognizedError());
            r.setContextWarning(prepSync.unrecognizedError());
            r.setModel(prepSync.visionModel());
            return r;
        }

        ChatResponse response = prepSync.answerProvider().invoke(request, history);
        if (imageFallbackWarning != null) {
            response.setContextWarning(imageFallbackWarning);
        }
        Integer usage = response.getTokenUsage() != null ? response.getTokenUsage().getTotal() : null;
        memory.save(sessionId, Message.assistant(response.getContent(), null, prepSync.answerProvider().getName(), usage));

        sessionManager.touch(sessionId);

        return response;
    }

    /**
     * 图片自动路由与可访问性处理:
     * 1. 将本地相对路径图片解析为 base64 data URL(供视觉模型直接读取)
     * 2. 若当前模型不支持视觉但用户上传了图片 → 直接切换到视觉模型看图回答(一步),
     *    不再“先识别再交给纯文本模型”, 避免重复调用与图片细节丢失
     */
    /** 图片预处理: 返回降级成功提示或识别失败提示(失败时需直接提示并中止作答) */
    private ImagePrep prepareImagesForModel(ChatRequest request, List<Message> history, ChatModelProvider provider) {
        // 1. 解析当前消息图片为可访问的 base64 形式(供视觉模型直接读取)
        List<String> resolved = imageResolver.resolveForModel(request.getImages());
        request.setImages(resolved);

        // 2. 解析历史消息中的图片(供视觉模型多轮上下文使用)
        for (Message m : history) {
            if (m.getImages() != null && !m.getImages().isEmpty() && "user".equals(m.getRole())) {
                m.setImages(imageResolver.resolveForModel(m.getImages()));
            }
        }

        // 3. 决定最终回答模型:
        //    有图片且当前模型不支持视觉时, 直接用视觉模型看图回答(一步), 不再"先识别再交给纯文本模型"。
        boolean hasImage = resolved != null && !resolved.isEmpty();
        if (!hasImage) {
            // 无图片: 原模型直接回答
            return new ImagePrep(null, null, null, "none", null, null, provider);
        }
        if (provider.supportsVision()) {
            // 当前模型即视觉模型: 直接看图回答
            return new ImagePrep(null, null, provider.getName(), "vision", null, null, provider);
        }
        // 当前模型不支持视觉但有图片: 自动切换到视觉模型直接回答(一步)
        ChatModelProvider vision = registry.getFirstVisionChatProvider();
        if (vision == null) {
            log.warn("用户上传了图片, 但当前模型[{}]不支持视觉且无可用视觉模型, 图片将被忽略", provider.getName());
            return new ImagePrep("当前没有可用的视觉模型，上传的图片将被忽略，仅按文字内容回答。",
                    null, null, "none", null, null, provider);
        }
        log.info("当前模型[{}]不支持看图, 自动切换到视觉模型[{}]直接回答", provider.getName(), vision.getName());
        return new ImagePrep(
                String.format("当前模型「%s」不支持直接看图，已自动切换到视觉模型「%s」直接理解并回答图片。",
                        provider.getName(), vision.getName()),
                null, vision.getName(), "vision", null, null, vision);
    }

}
