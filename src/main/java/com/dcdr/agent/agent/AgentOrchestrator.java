package com.dcdr.agent.agent;

import com.dcdr.agent.agent.memory.ChatMemory;
import com.dcdr.agent.agent.memory.Message;
import com.dcdr.agent.agent.memory.TokenEstimator;
import com.dcdr.agent.agent.prompt.RuntimePromptProvider;
import com.dcdr.agent.agent.prompt.SystemPromptResolver;
import com.dcdr.agent.agent.definition.AgentDefinitionService;
import com.dcdr.agent.agent.governance.CostTracker;
import com.dcdr.agent.config.SystemPromptProperties;
import com.dcdr.agent.agent.session.SessionManager;
import com.dcdr.agent.audit.AuditService;
import com.dcdr.agent.dto.ChatChunk;
import com.dcdr.agent.dto.ChatRequest;
import com.dcdr.agent.dto.ChatResponse;
import com.dcdr.agent.dto.SessionDTO;
import com.dcdr.agent.agent.image.ChatImageService;
import com.dcdr.agent.dto.ChatImageRequest;
import com.dcdr.agent.model.provider.ChatModelProvider;
import com.dcdr.agent.rag.config.RagProperties;
import com.dcdr.agent.rag.tool.KnowledgeSearchContextHolder;
import com.dcdr.agent.tool.ToolRegistry;
import com.dcdr.agent.tool.ToolInfo;
import com.dcdr.agent.entity.AgentDefinitionEntity;
import com.dcdr.agent.model.registry.ModelRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private final ToolRegistry toolRegistry;
    private final SystemPromptResolver systemPromptResolver;
    private final RuntimePromptProvider runtimePromptProvider;
    private final RagProperties ragProperties;
    private final AuditService auditService;
    private final AgentDefinitionService agentDefinitionService;
    private final CostTracker costTracker;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 串联提取时, 对每个搜索结果抓取完整正文的最大文章数(控制延迟与 token) */
    private static final int EXTRACT_TOP_N = 3;

    // 预编译所有正则表达式为静态常量
    private static final java.util.regex.Pattern P_ARTICLE_KEYWORD =
            java.util.regex.Pattern.compile(".*/(news|article|articles|story|stories|blog|post|posts|p|read|detail|item)/.*");
    private static final java.util.regex.Pattern P_DATE_PATH =
            java.util.regex.Pattern.compile(".*/(19|20)\\d{2}(/\\d{1,2}(/\\d{1,2})?)?.*");
    private static final java.util.regex.Pattern P_DATE_STR =
            java.util.regex.Pattern.compile(".*(19|20)\\d{2}[-_]\\d{2}[-_]\\d{2}.*");
    private static final java.util.regex.Pattern P_NUM_ID =
            java.util.regex.Pattern.compile(".*/\\d{4,}.*");
    private static final java.util.regex.Pattern P_QUERY_ID =
            java.util.regex.Pattern.compile(".*[?&](id|article_id|aid)=\\d+.*");
    private static final java.util.regex.Pattern P_SLUG =
            java.util.regex.Pattern.compile(".*/[a-z0-9\\-]{15,}.*");
    private static final java.util.regex.Pattern P_AGGREGATE =
            java.util.regex.Pattern.compile(".*/(section|sections|category|categories|topic|topics|tag|tags|channel|list|search|c/).*");

    public AgentOrchestrator(ModelRouter router, ChatMemory memory, SessionManager sessionManager,
                             ActiveChatRegistry activeChatRegistry, TokenEstimator tokenEstimator,
                             ModelRegistry registry, ImageResolver imageResolver, ChatImageService chatImageService,
                             ToolRegistry toolRegistry, SystemPromptResolver systemPromptResolver,
                             RuntimePromptProvider runtimePromptProvider,
                             RagProperties ragProperties, AuditService auditService,
                             AgentDefinitionService agentDefinitionService, CostTracker costTracker) {
        this.router = router;
        this.memory = memory;
        this.sessionManager = sessionManager;
        this.activeChatRegistry = activeChatRegistry;
        this.tokenEstimator = tokenEstimator;
        this.registry = registry;
        this.imageResolver = imageResolver;
        this.chatImageService = chatImageService;
        this.toolRegistry = toolRegistry;
        this.systemPromptResolver = systemPromptResolver;
        this.runtimePromptProvider = runtimePromptProvider;
        this.ragProperties = ragProperties;
        this.auditService = auditService;
        this.agentDefinitionService = agentDefinitionService;
        this.costTracker = costTracker;
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
            int estTokens,
            List<String> agentAllowedTools,
            int memoryTrimmed     // 记忆窗口截断条数(>0 表示已丢弃早期消息, 用于超限提示)
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
    public Flux<ChatChunk> chat(ChatRequest request, String currentUserId) {
        // V3.0-beta F-39: 用户级 token 配额检查 (超额抛 429, 由 ChatController onErrorResume 转为错误块)
        costTracker.checkUserQuota(currentUserId);

        // 带图片时, 准备阶段可能需要视觉模型识别图片(耗时数秒), 期间前端收不到任何数据会显得"卡住";
        // 先立即下发一个状态提示, 让用户知道后台正在处理。
        boolean hasImages = request.getImages() != null && !request.getImages().isEmpty();
        // V2.2: userId 由 Controller 在同步段从 exchange 取出后传入, 跨线程可见

        Flux<ChatChunk> pipeline = Mono.fromCallable(() -> prepareForChat(request, currentUserId))
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

                        // 记忆窗口超限提示: max-messages 截断已发生, 早期消息不再参与本轮回答(完整历史仍可在会话界面查看)
                        if (ctx.memoryTrimmed() > 0) {
                            sink.next(ChatChunk.contextWarning(
                                    String.format("对话已达记忆窗口上限，仅保留最近 %d 条消息参与回答（已省略最早 %d 条），完整历史仍可在会话中查看。",
                                            ctx.history().stream().filter(m -> !"system".equals(m.getRole())).count(),
                                            ctx.memoryTrimmed())));
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

                        // 若模型支持函数调用, 注入标记 supportsStreaming 的工具定义(默认仅 image_generation;
                        // Weather/Calculator 等不会暴露给流式对话)
                        // V2.3: 若指定了自定义 Agent, 仅注入 Agent 工具白名单中的工具
                        // V3.1: 双格式注入 — 框架原生 ToolSpecification(LangChain4j Provider 直连)
                        // + OpenAI Map(legacy 手写 Provider 回退), 各 Provider 按需取用
                        List<Map<String, Object>> toolDefs = toolRegistry.getStreamingToolDefinitionsFiltered(
                                ctx.agentAllowedTools());
                        if (provider.supportsFunctionCalling() && !toolDefs.isEmpty()) {
                            request.setTools(toolDefs);
                            request.setToolSpecifications(
                                    toolRegistry.getStreamingToolSpecificationsFiltered(ctx.agentAllowedTools()));
                        }

                        // 流式调用模型
                        StringBuilder accumulated = new StringBuilder();
                        StringBuilder reasoningAccumulated = new StringBuilder();
                        // 防止正常完成与中断并发时重复保存助手消息(只保存一条)
                        final AtomicBoolean assistantSaved = new AtomicBoolean(false);
                        // 防止正常完成与中断并发时重复记录 token 用量(F-34 成本口径只记一次)
                        final AtomicBoolean costRecorded = new AtomicBoolean(false);
                        // handle 引用需在回调中访问, 用数组持有以满足 effectively final
                        final ActiveChatRegistry.ChatHandle[] handleHolder = new ActiveChatRegistry.ChatHandle[1];
                        // 捕获模型真实返回的 token 消耗与结束原因, 用于回填 done 块
                        final ChatResponse.TokenUsage[] usageHolder = new ChatResponse.TokenUsage[1];
                        final String[] finishReasonHolder = new String[1];
                        // 收集模型发起的工具调用(自动文生图)与生成结果
                        // 用 holder 以支持工具循环: 每轮重置换新列表, 快照供该轮回灌使用
                        final List<ChatChunk>[] toolCallsHolder = new List[1];
                        toolCallsHolder[0] = new ArrayList<>();
                        // 当前正在执行的这一轮工具调用的快照(用于回灌 history + 落库)
                        final List<ChatChunk>[] roundToolCalls = new List[1];
                        roundToolCalls[0] = new ArrayList<>();
                        final List<String> generatedUrls = new ArrayList<>();
                        // 本轮实际生图的内容摘要(prompt), 供落库陈述句使用, 让下一轮模型明确"这些图片已生成过"
                        final List<String> generatedPrompts = new ArrayList<>();
                        final String[] toolError = new String[1];
                        // 非图片工具(如 calculator/weather)的执行结果, 供落库时并入助手消息, 保证可见且可持久化
                        final List<String> toolResults = new ArrayList<>();
                        // 本轮联网搜索/知识库检索等工具产生的来源(引用), 供 done 块下发与落库
                        final List<Map<String, Object>> collectedSources = new ArrayList<>();
                        // Agentic RAG: 合并用户选的知识库 + 平台级配置的知识库 ID,
                        // 通过 ThreadLocal 传给 KnowledgeSearchTool(AgentOrchestrator → executeTools → toolRegistry → tool)
                        final List<String> effectiveKbIds = buildEffectiveKbIds(request);

                        // 主链路: 转发模型流式分块并采集 usage / 文本 / 工具调用
                        // 采集逻辑(每一轮模型流复用同一 collector, 写入 toolCallsHolder[0])
                        Consumer<ChatChunk> collector = chunk -> {
                            if ("usage".equals(chunk.getType()) && chunk.getTokenUsage() != null) {
                                usageHolder[0] = chunk.getTokenUsage();
                                // 仅在模型明确给出非工具结束原因时覆盖(工具调用优先级最高)
                                if (chunk.getFinishReason() != null && !"tool_calls".equals(chunk.getFinishReason())) {
                                    finishReasonHolder[0] = chunk.getFinishReason();
                                }
                                return;
                            }
                            if ("tool_call".equals(chunk.getType())) {
                                // 收到工具调用即标记需要工具, 不依赖 usage 块到达(避免 usage 缺失导致回灌被跳过)
                                toolCallsHolder[0].add(chunk);
                                finishReasonHolder[0] = "tool_calls";
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
                        };

                        // 首轮模型流(provider 自动追加当前 user 消息)
                        Flux<ChatChunk> upstream = provider.stream(request, history).doOnNext(collector);

                        // 工具循环尾部: 若模型发起工具调用, 将本轮工具结果回填 history 后再发起下一轮生成;
                        // 否则收尾。每一轮回灌的是该轮自己的工具调用与结果(支持 agentic 多步),
                        // 回灌内容经截断控制 token 开销。
                        final int[] toolRound = {0};
                        AtomicReference<Flux<ChatChunk>> tailRef = new AtomicReference<>();
                        Flux<ChatChunk> tailFlux = Flux.defer(() -> {
                            // 回灌轮需重新捕获本轮新产生的工具调用(多步: 模型在第 2 轮可能又发起工具)
                            List<ChatChunk> currentRound = new ArrayList<>(toolCallsHolder[0]);
                            boolean needTool = "tool_calls".equals(finishReasonHolder[0]) && !currentRound.isEmpty();
                            if (needTool && toolRound[0] < MAX_TOOL_ROUNDS) {
                                toolRound[0]++;
                                // 把本轮工具结果回填 history: assistant(tool_calls) + 各 tool 结果
                                List<Map<String, Object>> tcs = new ArrayList<>();
                                for (ChatChunk tc : currentRound) {
                                    Map<String, Object> m = new LinkedHashMap<>();
                                    m.put("id", tc.getId());
                                    m.put("name", tc.getTool());
                                    m.put("arguments", tc.getArgs() == null ? "" : tc.getArgs());
                                    tcs.add(m);
                                }
                                Message assistMsg = new Message();
                                assistMsg.setRole("assistant");
                                assistMsg.setToolCalls(tcs);
                                history.add(assistMsg);
                                List<Message> roundMsgs = new ArrayList<>();
                                roundMsgs.add(assistMsg);
                                for (int i = 0; i < currentRound.size(); i++) {
                                    ChatChunk tc = currentRound.get(i);
                                    String raw = i < toolResults.size() ? toolResults.get(i) : "";
                                    // 回灌内容截断, 控制 token 开销
                                    String res = truncate(raw, TOOL_RESULT_MAX_LEN);
                                    Message toolMsg = new Message();
                                    toolMsg.setRole("tool");
                                    toolMsg.setToolCallId(tc.getId());
                                    toolMsg.setName(tc.getTool());
                                    toolMsg.setContent(res);
                                    history.add(toolMsg);
                                    roundMsgs.add(toolMsg);
                                }
                                // 异步持久化本轮工具往返(assistant(tool_calls) + tool 结果),
                                // 刷新后可恢复完整 agentic 上下文
                                final String sessForSave = sessId;
                                Mono.fromCallable(() -> {
                                    for (Message m : roundMsgs) {
                                        memory.save(sessForSave, m);
                                    }
                                    return null;
                                }).subscribeOn(Schedulers.boundedElastic()).subscribe();
                                // 换新列表采集下一轮工具调用(避免污染本轮快照)
                                toolCallsHolder[0] = new ArrayList<>();
                                // 下一轮执行本轮新工具(多步 agentic), 再回灌
                                Flux<ChatChunk> nextTool = Flux
                                        .defer(() -> executeTools(currentRound, accumulated.toString(),
                                                reasoningAccumulated.toString(), generatedUrls, generatedPrompts, toolError, toolResults, collectedSources, request.getMessage(), effectiveKbIds))
                                        .subscribeOn(Schedulers.boundedElastic());
                                // 下一轮: 不再追加当前 user 消息(上下文已在 history)
                                Flux<ChatChunk> next = nextTool.thenMany(provider.stream(request, history, false).doOnNext(collector));
                                return next.concatWith(tailRef.get());
                            }
                            // 无需工具(或已达上限): 收尾并保存
                            return Flux.defer(() -> finalizeAssistant(sessId, provider,
                                    accumulated, reasoningAccumulated, roundToolCalls[0], generatedUrls, generatedPrompts, toolError, toolResults, collectedSources,
                                    usageHolder, finishReasonHolder, startNanos, contextForEstimate, assistantSaved, costRecorded, currentUserId, request));
                        });
                        tailRef.set(tailFlux);

                        Disposable upstreamSub = upstream
                                .concatWith(tailFlux)
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
                                            // 关键: 正常完成也必须 complete 外层 Flux,
                                            // 否则 SSE 连接不关闭, 前端收不到结束信号, 发送按钮卡在"取消"
                                            sink.complete();
                                        }
                                );

                        // 注册句柄: 支持主动中断 + 客户端断开时自动取消上游模型调用
                        ActiveChatRegistry.ChatHandle handle = new ActiveChatRegistry.ChatHandle(sink, upstreamSub);
                        // 中断回调: 下发 interrupted + done。真实 usage 优先(竞态: usage 块可能先于
                        // 中断到达), 拿不到再估算兜底 —— 统一收敛在 TokenEstimator.resolveUsage
                        handle.setOnInterrupt(() -> {
                            ChatResponse.TokenUsage u = tokenEstimator.resolveUsage(
                                    usageHolder[0], contextForEstimate,
                                    accumulated.toString(), reasoningAccumulated.toString());
                            int estP = u.getPrompt();
                            int estC = u.getCompletion();
                            int estT = u.getTotal();
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
                            // V3.0-beta F-34: 中断路径补记 token 用量(成本/配额口径不漏记)。
                            // finalizeAssistant 被取消不会执行, 但需与竞态下的已记录去重。
                            if (costRecorded.compareAndSet(false, true)) {
                                ChatResponse.TokenUsage intUsage = new ChatResponse.TokenUsage();
                                intUsage.setPrompt(estP);
                                intUsage.setCompletion(estC);
                                intUsage.setTotal(estT);
                                costTracker.record(currentUserId, sessId, null, "chat",
                                        provider.getName(), intUsage);
                            }
                            // 审计日志: 中断场景 (F-28)
                            auditService.record("chat", "session", sessId,
                                    buildChatAuditDetail(provider.getName(), estP, estC, estT, durationMs,
                                            request.isWebSearch(), buildEffectiveKbIds(request),
                                            roundToolCalls[0], true, null),
                                    currentUserId);
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
    public Flux<ChatChunk> generateImageInChat(ChatImageRequest req, String currentUserId) {
        // V2.2: userId 由 Controller 从 exchange 取出后传入, 用于会话绑定
        String sessionId = req.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            SessionDTO session = sessionManager.createSession("新对话", null, null, currentUserId);
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
                            // 关键: 按钮生图即使没有任何文本, 也要在 assistant 内容里记下"已生成图片",
                            // 否则下一轮模型在历史中看不到已生成记录, 会把前一张图重新生成一份(历史诱导重画)。
                            // 注意: 不拼接用户原题(req.getPrompt()), 那是对话指令而非图片描述, 直接拼进历史会污染下一轮上下文;
                            // 图片主体已记录在 user 消息中, 此处仅作"本轮已生图"的声明即可。
                            String imgContent = "已生成图片。";
                            chunks.add(ChatChunk.chunk(imgContent));
                            return Flux.fromIterable(chunks).concatWith(Flux.defer(() -> {
                                Message m = Message.assistantImage(urls, imgContent, modelName);
                                memory.save(sessId, m);
                                sessionManager.touch(sessId);
                                // 审计日志: 文生图 (F-28)
                                Map<String, Object> imgDetail = new LinkedHashMap<>();
                                imgDetail.put("model", modelName);
                                imgDetail.put("imageCount", urls.size());
                                auditService.record("chat_image", "session", sessId, imgDetail, currentUserId);
                                return Flux.just(ChatChunk.done(0, 0, 0, 0));
                            }));
                        })
                        .onErrorResume(e -> {
                            Message err = Message.assistant("图片生成失败: " + e.getMessage(), null, modelName, null);
                            err.setError(true);
                            memory.save(sessId, err);
                            // 审计日志: 文生图失败 (F-28)
                            Map<String, Object> errDetail = new LinkedHashMap<>();
                            errDetail.put("model", modelName);
                            errDetail.put("error", e.getMessage());
                            auditService.record("chat_image", "session", sessId, errDetail, currentUserId);
                            return Flux.just(ChatChunk.error("图片生成失败: " + e.getMessage(), "IMAGE_GEN_ERROR"));
                        })
        );
    }

    /**
     * 执行对话内的工具调用: 经通用 ToolRegistry 分发到对应 Tool.execute。
     * 文生图(image_generation)结果额外渲染为 image 分块并回填 outUrls / outPrompts 供落库陈述句;
     * 其它工具结果渲染为 tool_result 分块。
     */
    private Flux<ChatChunk> executeTools(List<ChatChunk> toolCalls, String accumulatedText, String reasoningText,
                                        List<String> outUrls, List<String> outPrompts, String[] outError,
                                        List<String> outToolResults, List<Map<String, Object>> collectedSources,
                                        String userQuery, List<String> knowledgeBaseIds) {
        if (toolCalls.isEmpty()) {
            return Flux.empty();
        }

        // LLM 工具调用去重拦截: 同一轮中参数完全相同的调用只执行第一个，其余拦截
        // 以 "工具名 + 参数JSON" 作为唯一指纹（避免 web_search 等被模型重复发起相同查询）
        List<ChatChunk> originalCalls = toolCalls;
        final List<ChatChunk> effectiveCalls = new ArrayList<>(originalCalls.size());
        Set<String> seenFingerprints = new HashSet<>();
        for (ChatChunk tc : originalCalls) {
            String fingerprint = tc.getTool() + (tc.getArgs() != null && !tc.getArgs().isBlank()
                    ? tc.getArgs() : "{}");
            if (seenFingerprints.add(fingerprint)) {
                effectiveCalls.add(tc);
            } else {
                log.warn("检测到 LLM 重复的工具调用，已拦截: 工具={}, 指纹={}", tc.getTool(), fingerprint);
            }
        }
        if (effectiveCalls.isEmpty()) {
            return Flux.empty();
        }

        // 仅当本轮确有生图调用时, 才下发"正在生成"等待提示(避免普通对话误发)
        boolean hasImageCall = effectiveCalls.stream()
                .anyMatch(tc -> "image_generation".equals(tc.getTool()));
        Flux<ChatChunk> head = hasImageCall
                ? Flux.just(ChatChunk.status("正在生成图片，请稍候…"))
                : Flux.empty();
        return Flux.concat(head, Flux.defer(() -> {
            // Agentic RAG: 在 Flux 订阅时(boundedElastic 线程)注入知识库 ID,
            // 与工具执行在同一线程, finally 清除防止线程复用泄漏
            KnowledgeSearchContextHolder.set(knowledgeBaseIds);
            try {
            List<ChatChunk> chunks = new ArrayList<>();
            int callCount = 0;
            for (ChatChunk tc : effectiveCalls) {
                String name = tc.getTool();
                Map<String, Object> params = new LinkedHashMap<>();
                try {
                    if (tc.getArgs() != null && !tc.getArgs().isBlank()) {
                        params = mapper.readValue(tc.getArgs(), Map.class);
                    }
                } catch (Exception e) {
                    log.warn("工具 {} 参数解析失败: {}", name, tc.getArgs(), e);
                }
                // 文生图兜底: 模型未给 prompt 时用已累积文本作为提示词
                if ("image_generation".equals(name)) {
                    Object p = params.get("prompt");
                    if (p == null || p.toString().isBlank()) {
                        params.put("prompt", accumulatedText);
                    }
                }
                ToolInfo toolInfo;
                try {
                    // 统一经 ToolRegistry 分发; executeInfo 保留来源等扩展信息(如联网搜索)
                    toolInfo = toolRegistry.executeInfo(name, params);
                } catch (Exception e) {
                    outError[0] = e.getMessage();
                    log.error("工具 {} 执行失败", name, e);
                    return Flux.just(ChatChunk.error("工具执行失败: " + e.getMessage(), "TOOL_ERROR"));
                }
                String result = toolInfo.getContent();
                if ("image_generation".equals(name)) {
                    callCount++;
                    try {
                        Map<String, Object> res = mapper.readValue(result, Map.class);
                        Object prompt = res.get("prompt");
                        List<String> urls = new ArrayList<>();
                        Object urlsObj = res.get("urls");
                        if (urlsObj instanceof List) {
                            for (Object u : (List<?>) urlsObj) {
                                if (u != null) urls.add(u.toString());
                            }
                        }
                        if (urls.isEmpty()) {
                            // 生图未产出图片(且未抛异常): 与原始行为一致, 不报错, 直接返回空
                            return Flux.empty();
                        }
                        for (String u : urls) {
                            chunks.add(ChatChunk.image(u));
                            outUrls.add(u);
                        }
                        if (prompt != null) outPrompts.add(prompt.toString());
                    } catch (Exception e) {
                        outError[0] = e.getMessage();
                        log.error("文生图结果解析失败", e);
                        return Flux.just(ChatChunk.error("图片生成失败: " + e.getMessage(), "IMAGE_GEN_ERROR"));
                    }
                } else {
                    List<Map<String, Object>> srcs = toolInfo.getSources();
                    ExtractStat ext = new ExtractStat(0, 0, 0);
                    if ("web_search".equals(name)) {
                        // —— 智能流水线 STEP 1: 搜索与抓取 ——
                        chunks.add(ChatChunk.status("🔍 正在实时检索多个资讯源，并抓取相关页面…"));
                        // —— 串联提取: 对 top-N 具体文章链接抓取完整正文, 提升检索完整性 ——
                        ext = enrichWithExtract(srcs, userQuery);
                        // 用补全后的 sources 重建喂给模型的精简内容(含更完整正文)
                        result = rebuildCompact(srcs);
                    }
                    outToolResults.add(result);
                    chunks.add(ChatChunk.toolResult(name, result, srcs));
                    if (srcs != null && !srcs.isEmpty()) {
                        // 全局兜底: 搜索结果自带 content="undefined" 的条目(部分 Tavily 版本),
                        // 在累计落库前清除, 避免 "undefined" 字符串透传到数据库与模型。
                        for (Map<String, Object> s : srcs) {
                            Object c = s.get("content");
                            if (c != null && "undefined".equalsIgnoreCase(c.toString().trim())) {
                                s.remove("content");
                            }
                        }
                        collectedSources.addAll(srcs);   // 累计联网搜索等来源, 供落库与前端引用
                        if ("web_search".equals(name)) {
                            // —— 智能流水线 STEP 2 + STEP 3 ——
                            StringBuilder step2 = new StringBuilder("🧹 已抓取 ").append(srcs.size())
                                    .append(" 个页面，其中 ");
                            if (ext.candidates() > 0) {
                                step2.append("成功提取 ").append(ext.ok()).append(" 篇完整正文")
                                     .append(ext.failed() > 0 ? ("（" + ext.failed() + " 篇提取失败，已用搜索摘要）") : "");
                            } else {
                                step2.append("暂无可提取的具体文章，已用搜索摘要");
                            }
                            step2.append("，正在剔除广告/导航噪音、合并去重，并重新编排…");
                            chunks.add(ChatChunk.status(step2.toString()));
                        }
                    }
                }
            }
            if (callCount > 1) {
                // system 约束后多余调用本不该发生; 若仍多发, 说明用户确需多张, 全部执行不丢弃
                log.info("本轮检测到 {} 个 image_generation 调用, 全部执行(不丢弃)", callCount);
            }
            return Flux.fromIterable(chunks);
            } finally {
                // 清除 ThreadLocal, 防止线程池线程复用导致知识库 ID 泄漏到下一轮无关对话
                KnowledgeSearchContextHolder.clear();
            }
        }));
    }

    /** 串联提取统计: 候选文章数 / 成功抓取完整正文数 / 失败数 */
    private record ExtractStat(int candidates, int ok, int failed) {}

    // ===== 通用正文清洗（基于 Markdown 结构特征，非关键词硬匹配） =====

    /** HTML 标签残留块: script / style / nav / footer / header 及其内容 */
    private static final Pattern HTML_BLOCK = Pattern.compile(
            "<(script|style|nav|footer|header)\\b[^>]*>.*?</\\1>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** 任意裸露 HTML 标签（块级已去除后，清理剩余的 span/div/br 等） */
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");

    /** HTML 数字实体: &#123; 或 &#x1F; 等 */
    private static final Pattern HTML_NUM_ENTITY = Pattern.compile("&#(x?[0-9A-Fa-f]+);");

    /** 导航链接块判定: 连续 N 行以上均为 * [xxx](url) 或 - [xxx](url) 格式 */
    private static final Pattern NAV_LINK_LINE = Pattern.compile("^\\s*[*\\-]\\s*\\[[^]]*]\\([^)]*\\)\\s*$");
    private static final int NAV_CONSECUTIVE_THRESHOLD = 3;

    /** 纯图片行: 仅含 ![alt](url) 且无其他实质文字 */
    private static final Pattern IMAGE_ONLY_LINE = Pattern.compile("^\\s*!\\[[^]]*]\\([^)]*\\)\\s*$");

    /** 行内图片语法: 任意位置的 ![alt](url)，用于剥离正文中的图片链接（#2） */
    private static final Pattern INLINE_IMAGE = Pattern.compile("!\\[[^]]*]\\([^)]*\\)");

    /** 链接标题行: ## [标题](url) 形式，常与下方 ### 标题重复（#1） */
    private static final Pattern LINK_TITLE_LINE = Pattern.compile("^\\s*#{1,6}\\s*\\[[^]]*]\\([^)]*\\)\\s*$");

    /** 栏目聚合块行: 形如 ## [栏目名](栏目/分类链接) 的整行链接（#3） */
    private static final Pattern SECTION_LINK_LINE = Pattern.compile("^\\s*#{1,6}\\s*\\[[^]]*]\\([^)]*(category|section|cate|channel|topic|tag)[^)]*\\)\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** SPA 动态占位/加载提示（#5） */
    private static final Pattern PLACEHOLDER_LINE = Pattern.compile(
            "^\\s*(加载中|加载更多|点击加载更多|正在加载|Loading\\.{0,3}|暂无相关内容|没有更多了|view more|load more|show more)\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** 页脚特征关键词（截断或删行依据） */
    private static final Pattern FOOTER_KEYWORD = Pattern.compile(
            "\\b(©|Copyright|All rights reserved|Privacy Policy|Terms of Service|Cookie Policy|Subscribe|Newsletter|Follow us)\\b",
            Pattern.CASE_INSENSITIVE);

    /** 正文最大长度（清洗后，超出截断） */
    private static final int MAX_CONTENT_LENGTH = 4000;

    /** 工具循环(agentic loop)最大回灌轮数: 模型发起工具调用后, 最多再回灌生成 N 轮 */
    private static final int MAX_TOOL_ROUNDS = 4;

    /** 工具结果回灌到模型上下文时的最大长度(字符), 超长截断以控制 token 开销 */
    private static final int TOOL_RESULT_MAX_LEN = 6000;

    /**
     * 基于 Markdown 结构特征的通用正文清洗。
     * <p>
     * 规则：
     * <ol>
     *   <li>去除 HTML 残留块（script/style/nav/footer/header 及其内容），再清裸标签</li>
     *   <li>解码 HTML 实体（&amp; &quot; &#39; &#x27; &nbsp; 及数字实体 &#123;/&#x1F;），去除工具/搜索结果常见噪音</li>
     *   <li>去噪连续导航链接块（连续 &ge;3 行均为 {@code * [xxx](url)} / {@code - [xxx](url)}）</li>
     *   <li>去除仅含图片语法的行</li>
     *   <li>去除重复的链接标题行（{@code ## [标题](链接)} 与下方 {@code ### 标题} 重复）</li>
     *   <li>去除栏目聚合块行（{@code ## [栏目名](栏目/分类链接)}）</li>
     *   <li>去除 SPA 动态占位行（加载中/点击加载更多/暂无相关内容 等）</li>
     *   <li>剥离行内图片语法 {@code ![alt](url)}（保留正文文字）</li>
     *   <li>页脚特征截断（命中 {@code FOOTER_KEYWORD} 即从该行起全部截断）</li>
     *   <li>空行压缩（连续多空行合并为一个）</li>
     *   <li>长度截断（超出 {@value #MAX_CONTENT_LENGTH} 字符后追加提示）</li>
     * </ol>
     *
     * @param text 原始 Markdown/HTML 混杂文本
     * @return 清洗后的纯正文
     */
    static String cleanMarkdownContent(String text) {
        if (text == null || text.isBlank()) return "";

        // 1) 去除 HTML 残留块，再清裸标签
        text = HTML_BLOCK.matcher(text).replaceAll("");
        text = HTML_TAG.matcher(text).replaceAll("");
        // 1b) 解码 HTML 实体(&amp; &quot; &#39; &nbsp; 等), 工具/搜索结果常见噪音
        text = decodeHtmlEntities(text);

        // 2) 按行处理：连续导航链接块、纯图片行、页脚
        String[] lines = text.split("\\R", -1);
        int n = lines.length;
        if (n == 0) return "";

        // 标记哪些行需要剔除
        boolean[] remove = new boolean[n];

        // 2a) 连续导航链接块检测（滑动窗口，标记连续 NAV 段）
        int navStart = -1;
        for (int i = 0; i <= n; i++) {
            boolean isNav = i < n && NAV_LINK_LINE.matcher(lines[i]).matches();
            if (isNav) {
                if (navStart < 0) navStart = i;
            } else {
                if (navStart >= 0) {
                    int navLen = i - navStart;
                    if (navLen >= NAV_CONSECUTIVE_THRESHOLD) {
                        for (int j = navStart; j < i; j++) remove[j] = true;
                    }
                    navStart = -1;
                }
            }
        }

        // 2b) 纯图片行
        for (int i = 0; i < n; i++) {
            if (IMAGE_ONLY_LINE.matcher(lines[i]).matches()) {
                remove[i] = true;
            }
        }
        // 2c) 链接标题行（#1）：## [标题](链接) 与下方 ### 标题 重复，删此行
        // 2d) 栏目聚合块行（#3）：## [栏目名](栏目/分类链接)
        // 2e) SPA 动态占位行（#5）：加载中/点击加载更多/暂无相关内容 等
        for (int i = 0; i < n; i++) {
            String line = lines[i];
            if (LINK_TITLE_LINE.matcher(line).matches()
                    || SECTION_LINK_LINE.matcher(line).matches()
                    || PLACEHOLDER_LINE.matcher(line).matches()) {
                remove[i] = true;
            }
        }

        // 3) 页脚特征处理（#4：放宽阈值，命中即截断，不再局限于后 30%）
        int footerLine = -1;
        for (int i = 0; i < n; i++) {
            if (FOOTER_KEYWORD.matcher(lines[i]).find()) {
                footerLine = i;
                break; // 只处理第一个命中的行
            }
        }
        if (footerLine >= 0) {
            double ratio = (double) footerLine / Math.max(1, n);
            if (ratio >= 0.7) {
                // 在后 30% 区域: 从此行起全部截断
                for (int i = footerLine; i < n; i++) remove[i] = true;
            } else {
                // 前/中区域: 命中即从此行起截断（页脚/订阅块通常在文末但亦可能提前出现）
                for (int i = footerLine; i < n; i++) remove[i] = true;
            }
        }

        // 4) 按标记重建文本
        StringBuilder sb = new StringBuilder(text.length());
        int emptyCount = 0;
        for (int i = 0; i < n; i++) {
            if (remove[i]) continue;
            String line = lines[i];
            // 空行压缩: 连续空行只保留一个
            if (line.isBlank()) {
                if (++emptyCount <= 1) sb.append('\n');
            } else {
                emptyCount = 0;
                // #2 剥离行内图片语法 ![alt](url)
                sb.append(INLINE_IMAGE.matcher(line).replaceAll("")).append('\n');
            }
        }

        String result = sb.toString().strip();

        // 5) 长度截断
        if (result.length() > MAX_CONTENT_LENGTH) {
            result = result.substring(0, MAX_CONTENT_LENGTH) + "\n...(内容过长已截断)";
        }

        return result;
    }

    /**
     * 通用截断: 超出 maxLen 字符时截断并追加提示。null/空原样返回。
     */
    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "\n...(工具结果过长已截断)";
    }

    /**
     * 解码常见 HTML 实体: &amp; &lt; &gt; &quot; &#39; &#x27; &nbsp; 以及数字实体(&#123; / &#x1F 等)。
     * 工具/搜索结果常携带这些转义字符, 直接喂给模型会引入噪音。
     */
    private static String decodeHtmlEntities(String text) {
        if (text == null || text.indexOf('&') < 0) return text;
        // 命名实体
        text = text.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&#39;", "'")
                .replace("&#x27;", "'")
                .replace("&nbsp;", " ");
        // 数字实体 &#NN; / &#xHH;
        Matcher m = HTML_NUM_ENTITY.matcher(text);
        StringBuilder sb = new StringBuilder(text.length());
        while (m.find()) {
            try {
                int codePoint;
                String g = m.group(1);
                if (g.startsWith("x") || g.startsWith("X")) {
                    codePoint = Integer.parseInt(g.substring(1), 16);
                } else {
                    codePoint = Integer.parseInt(g);
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(new String(new int[]{codePoint}, 0, 1)));
            } catch (NumberFormatException ignored) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 串联提取: 对搜索结果里 top-N 个「具体文章链接」(含路径、非站点首页)调用 web_extract
     * 抓取完整正文, 写回对应 source 的 {@code content}(全文) 与 {@code snippet}(截断展示),
     * 使最终喂给模型的检索内容更接近 deepseek 的「抓取页面 → 提取正文」完整度。
     */
    private ExtractStat enrichWithExtract(List<Map<String, Object>> srcs, String userQuery) {
        if (srcs == null || srcs.isEmpty()) return new ExtractStat(0, 0, 0);
        // 候选评分: 优先选择「具体文章链接」而非站点首页/栏目聚合页。
        // 评分维度: 含文章特征路径(news/article/story/blog/post…) > 含日期/数字 id > 仅含子路径 > 仅域名根。
        List<Map<String, Object>> candidates = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Map<String, Object> s : srcs) {
            Object u = s.get("url");
            if (u == null) continue;
            String url = u.toString().trim();
            if (isSpecificArticleUrl(url) && seen.add(url)) candidates.add(s);
        }
        // 按文章可能性打分降序, 优先抓取最像单篇文章的链接
        List<Map<String, Object>> articles = candidates.stream()
                .sorted((a, b) -> Integer.compare(
                        articleScore(b.get("url")), articleScore(a.get("url"))))
                .limit(EXTRACT_TOP_N)
                .toList();
        log.info("[enrichWithExtract] 候选具体文章数={}, 优先抓取序(打分)={}",
                candidates.size(), articles.stream()
                        .map(s -> articleScore(s.get("url")) + ":" + s.get("url")).toList());
        if (articles.isEmpty()) {
            log.warn("[enrichWithExtract] 无可用具体文章候选(搜索结果可能全为聚合/首页链接), 跳过 extract。原始URL示例={}",
                    srcs.stream().limit(5).map(s -> String.valueOf(s.get("url"))).toList());
        }
        int ok = 0, failed = 0;
        for (Map<String, Object> s : articles) {
            // 兜底: 搜索阶段可能已自带 content="undefined"(Tavily 部分版本),
            // 若有则移除, 避免该字符串透传到落库字段; 后续 extract 失败时会走 snippet 降级。
            Object pre = s.get("content");
            if (pre != null && "undefined".equalsIgnoreCase(pre.toString().trim())) {
                s.remove("content");
            }
            String url = s.get("url").toString().trim();
            try {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("urls", java.util.Collections.singletonList(url));
                // 把用户原始问题作为 extract 的 query: 按相关性对页面块重排序, 正文更聚焦、token 更省(官方最佳实践)
                if (userQuery != null && !userQuery.isBlank()) {
                    params.put("query", userQuery);
                }
                ToolInfo ext = toolRegistry.executeInfo("web_extract", params);
                List<Map<String, Object>> exSrcs = ext.getSources();
                if (exSrcs != null && !exSrcs.isEmpty()) {
                    Object full = exSrcs.get(0).get("content");
                    // Tavily 抽取失败时常把 content 序列化为字符串 "undefined", 需显式判空,
                    // 否则会被当作有效正文塞给模型(日志里见到的 "Content: undefined" 即此情况)
                    if (full != null && !full.toString().isBlank() && !"undefined".equalsIgnoreCase(full.toString().trim())) {
                        String text = full.toString();
                        text = cleanMarkdownContent(text); // 通用结构清洗（去导航/页脚/HTML 等噪音）
                        s.put("content", text);
                        // snippet 展示正文前若干字(比搜索摘要更完整)
                        s.put("snippet", clip(text, 600));
                        ok++;
                        // 仅打印最终实际使用到的正文(清洗后、截断2000), 去掉中间过程日志
                        log.info("===== WEB EXTRACT CONTENT BEGIN [{}] =====\nURL: {}\n原文长度: {}\n---\n{}\n===== WEB EXTRACT CONTENT END [{}] =====",
                                ok, url, text.length(), clip(text, 2000), ok);
                        continue;
                    } else {
                        log.warn("[enrichWithExtract] web_extract 返回 content 为空, URL={}, 返回文本预览={}",
                                url, ext.getContent() != null ? clip(ext.getContent(), 300) : "null");
                    }
                }
                failed++;
            } catch (Exception e) {
                failed++;
                log.warn("web_extract 抓取失败(忽略, 保留搜索摘要): {}", url, e);
            }
        }
        return new ExtractStat(articles.size(), ok, failed);
    }

    /** 判断 URL 是否为具体文章(而非站点首页/聚合页): 含路径段且非仅域名根, 且不是已知的聚合首页 */
    private static boolean isSpecificArticleUrl(String url) {
        if (url == null || !looksLikeHttp(url)) return false;
        try {
            String path = java.net.URI.create(url).getPath();
            if (path == null || path.length() <= 1) return false;   // 仅域名根不算
            if (isAggregateHomepage(url, path)) return false;        // 聚合/导航首页不算
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 已知聚合/导航首页黑名单: 这些 URL 永远无法提取出单篇新闻正文, 必须剔除 */
    private static boolean isAggregateHomepage(String url, String path) {
        String u = url.toLowerCase();
        String host;
        try {
            host = java.net.URI.create(url).getHost();
        } catch (Exception e) {
            host = u;
        }
        if (host == null) host = u;
        // 路径本身就是顶层聚合页(/home、/news、/topics、/latest 等)
// 补充 category, tag, search, section 等常见聚合栏目
        if (path.matches("^/(home|news|topics|topic|latest|trending|world|china|cn|headlines|category|tag|search|section)/?$")) return true;
        // 聚合新闻门户首页(整站根/栏目, 而非具体文章)
        if (host.contains("news.google.com")) return true;
        if (host.contains("google.com") && path.startsWith("/home")) return true;
        if (host.contains("news.yahoo.com") && (path.equals("/") || path.startsWith("/home"))) return true;
        if (host.contains("bing.com") && path.startsWith("/news")) return true;
        // 路径本身就是顶层聚合页(/home、/news、/topics、/latest 等)
        if (path.matches("^/(home|news|topics|topic|latest|trending|world|china|cn|headlines)/?$")) return true;
        // 含 hl=/ceid=/gl= 等聚合门户参数且无具体文章 slug 的, 基本是首页变体
        if (u.contains("ceid=") && !u.matches(".*/[a-z0-9\\-]{15,}.*")) return true;
        return false;
    }

    /**
     * 文章可能性打分: 越大越像是「单篇具体文章」(而非站点首页/栏目聚合页)。
     * 用于在多个候选里优先抓取正文价值更高的链接。
     */
    private static int articleScore(Object urlObj) {
        if (urlObj == null) return -1;
        String url = urlObj.toString().toLowerCase();
        if (!looksLikeHttp(url)) return -1;

        String path;
        try {
            path = java.net.URI.create(url).getPath();
        } catch (Exception e) {
            return -1;
        }
        if (path == null) return -1;

        if (isAggregateHomepage(url, path)) return -1000;

        // 防御：过滤静态资源文件
        if (path.matches(".*\\.(jpg|jpeg|png|gif|js|css|svg|pdf|zip|mp4)$")) return -500;

        int score = 0;
        // 文章特征路径(高权重) - 使用预编译
        if (P_ARTICLE_KEYWORD.matcher(path).matches()) score += 40;

        // html 后缀
        if (path.endsWith(".html") || path.endsWith(".htm")) score += 15;

        // 含年份/日期路径
        if (P_DATE_PATH.matcher(path).matches() || P_DATE_STR.matcher(path).matches()) score += 25;

        // 含数字 id
        if (P_NUM_ID.matcher(path).matches() || P_QUERY_ID.matcher(url).matches()) score += 20;

        // 含 slug 风格长尾
        if (P_SLUG.matcher(path).matches()) score += 15;

        // 聚合/栏目页特征(降权)
        if (P_AGGREGATE.matcher(path).matches()) score -= 20;

        // 路径深度加分 (修正空指针隐患)
        long segs = path.split("/").length - 1;
        score += Math.min(segs, 5) * 3;

        return score;
    }

    private static boolean looksLikeHttp(String s) {
        return s.startsWith("http://") || s.startsWith("https://");
    }

    /** 把补全后的 sources 拼成喂给模型的精简内容(每个来源带更完整的正文摘要) */
    private static String rebuildCompact(List<Map<String, Object>> srcs) {
        if (srcs == null || srcs.isEmpty()) return "（联网搜索未返回可用结果）";
        // 剔除聚合/首页类 URL(如 Google News 首页变体): 它们无法提取正文, 塞给模型只会产生导航噪音
        List<Map<String, Object>> usable = srcs.stream()
                .filter(s -> {
                    Object u = s.get("url");
                    if (u == null) return true;
                    String url = u.toString();
                    String path = safePath(url);
                    if (isAggregateHomepage(url, path)) return false;
                    // 剔除 content 与 snippet 均为空的低价值来源(title+url 无信息量,
                    // 只占 [[n]] 序号、撑大 sources 落库、前端渲染空卡片), 视为结构化噪音
                    Object c = s.get("content");
                    Object sn = s.get("snippet");
                    boolean hasContent = c != null && !c.toString().isBlank()
                            && !"undefined".equalsIgnoreCase(c.toString().trim());
                    boolean hasSnippet = sn != null && !sn.toString().isBlank();
                    return hasContent || hasSnippet;
                })
                .toList();
        if (usable.isEmpty()) {
            return "（联网搜索返回的结果均为新闻聚合首页/导航页，未能提取到具体文章正文；"
                    + "请勿编造细节，可基于已知信息简要说明，并提示用户结果可能不完整）";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("以下是联网检索到的 ").append(usable.size())
          .append(" 条资料（部分已抓取完整正文），请在回答中通过 [[n]] 标注引用来源（n 为下方序号），并在结尾列出引用：\n\n");
        int i = 1;
        for (Map<String, Object> s : usable) {
            sb.append("[[").append(i).append("]] ").append(s.get("title"));
            Object url = s.get("url");
            if (url != null && !url.toString().isBlank()) sb.append("  (").append(url).append(")");
            Object content = s.get("content");
            boolean hasContent = content != null && !content.toString().isBlank()
                    && !"undefined".equalsIgnoreCase(content.toString().trim());
            String body = hasContent
                    ? clip(content.toString(), 1000)          // 完整正文截断, 比搜索摘要更充分
                    : (s.get("snippet") != null ? s.get("snippet").toString() : "");
            if (!body.isBlank()) sb.append("\n    ").append(body);
            else sb.append("\n    （该来源未能提取到正文，仅作链接参考）");
            sb.append("\n");
            i++;
        }
        return sb.toString();
    }

    private static String safePath(String url) {
        try {
            String p = java.net.URI.create(url).getPath();
            return p == null ? "" : p;
        } catch (Exception e) {
            return "";
        }
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /**
     * 收尾阶段: 根据是否有工具调用与生成结果, 保存助手消息(普通文本 / 带图),
     * 并下发 done 分块与 finish_reason 相关提示。
     */
    private Flux<ChatChunk> finalizeAssistant(String sessId, ChatModelProvider provider,
                                             StringBuilder accumulated, StringBuilder reasoning,
                                             List<ChatChunk> toolCalls, List<String> urls, List<String> prompts, String[] toolError,
                                             List<String> toolResults, List<Map<String, Object>> collectedSources,
                                             ChatResponse.TokenUsage[] usageHolder, String[] finishReasonHolder,
                                             long startNanos, List<Message> contextForEstimate,
                                             AtomicBoolean assistantSaved, AtomicBoolean costRecorded, String userId, ChatRequest request) {
        // 真实 usage 优先, 模型未返回时估算兜底 —— 统一收敛在 TokenEstimator.resolveUsage
        ChatResponse.TokenUsage u = tokenEstimator.resolveUsage(
                usageHolder[0], contextForEstimate, accumulated.toString(), reasoning.toString());
        int p = u.getPrompt();
        int c = u.getCompletion();
        int t = u.getTotal();
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        String fr = finishReasonHolder[0];

        // V3.0-beta F-34: 记录本次对话 token 用量 (成本统计/配额口径, 仅模型自身消耗;
        // 路由为平台探测成本, 不计入用户用量 —— 见 routing_logs.token 字段)。
        // costRecorded 与中断回调的补记互斥, 竞态下只记一次。
        if (costRecorded.compareAndSet(false, true)) {
            costTracker.record(userId, sessId, null, "chat", provider.getName(), u);
        }

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
                    // 工具被调用但未产出图片(非图片工具, 如 calculator/weather): 将执行结果并入助手消息
                    String content = accumulated.toString();
                    if (!toolResults.isEmpty()) {
                        StringBuilder sb = new StringBuilder();
                        if (content != null && !content.isBlank()) {
                            sb.append(content).append("\n");
                        }
                        sb.append(String.join("\n", toolResults));
                        content = sb.toString();
                    }
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
            // 附加本轮联网搜索等来源(引用), 供前端渲染引用卡片并随会话持久化
            if (collectedSources != null && !collectedSources.isEmpty()) {
                assistantMessage.setSources(new ArrayList<>(collectedSources));
            }
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
        // done 块: collectedSources 含联网搜索/知识库检索的引用来源(Agentic RAG 模式下由工具返回),
        // 随 done 下发给前端渲染引用卡片
        if (collectedSources != null && !collectedSources.isEmpty()) {
            tail.add(ChatChunk.done(p, c, t, durationMs, collectedSources));
        } else {
            tail.add(ChatChunk.done(p, c, t, durationMs));
        }
        if ("length".equals(fr)) {
            tail.add(ChatChunk.contextWarning("回答已达长度上限(max_tokens)被截断，可尝试简化问题或开启新对话。"));
        } else if ("insufficient_system_resource".equals(fr)) {
            tail.add(ChatChunk.error("生成因后端推理资源不足被打断", "RESOURCE_LIMIT"));
        } else if ("content_filter".equals(fr)) {
            tail.add(ChatChunk.contextWarning("回答触发安全策略过滤。"));
        }
        // 审计日志: 记录对话元数据(token/耗时/工具), 不记录消息内容 (F-28)
        auditService.record("chat", "session", sessId,
                buildChatAuditDetail(provider.getName(), p, c, t, durationMs, request.isWebSearch(),
                        buildEffectiveKbIds(request), toolCalls, false, fr),
                userId);
        return Flux.fromIterable(tail);
    }

    /**
     * 阻塞的准备阶段(在 boundedElastic 线程执行):
     * 确保会话、加载记忆、路由决策、保存用户消息、图片自动路由(不支持视觉时直接切换视觉模型回答)、上下文上限估算。
     * 结果封装为 ChatContext 交给流式主链路(此时 request 中的图片已被解析为 base64, 最终回答模型已确定)。
     */
    private ChatContext prepareForChat(ChatRequest request, String userId) {
        // 0. V2.3 自定义 Agent: 加载并应用 Agent 配置 (model/temperature/kb_ids/tools/systemPrompt)
        //    必须在路由前完成, 因 Agent.model 会覆盖 request.model 影响路由决策
        AgentOverride agentOverride = applyAgentDefinition(request);

        // 1. 确保有会话
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            SessionDTO session = sessionManager.createSession("新对话", request.getModel(), null, userId);
            sessionId = session.getId();
            request.setSessionId(sessionId);
        }
        final String sessId = sessionId;

        // 2. 加载记忆
        //    load(): 全量历史(落库与前端展示口径); loadWindow(): 发给 LLM 的滑窗视图(system 全保留 + 最近 N 条)。
        //    超出 max-messages 时只截取视图、不删库(旧版直接删最早消息导致会话历史丢失, 已废弃该实现)。
        List<Message> fullHistory = memory.load(sessId);
        List<Message> history = memory.loadWindow(sessId);
        int memoryTrimmed = fullHistory.size() - history.size();
        if (memoryTrimmed > 0) {
            log.info("会话 {} 记忆窗口截断: 全量 {} 条, 发送 LLM {} 条(丢弃最早 {} 条)",
                    sessId, fullHistory.size(), history.size(), memoryTrimmed);
        }
        log.debug("会话 {} 加载记忆 {} 条(窗口)", sessId, history.size());

        // 3. 路由决策
        ChatModelProvider provider = router.route(request, history);
        log.info("会话 {} 路由到模型: {}", sessId, provider.getName());

        // 3.1 分层 system prompt 注入(L1 全局 + L2/chat 场景级):
        //     - 会话级 systemPrompt(创建会话时已落库为 history 中一条 system 消息)优先级最高,
        //       若已存在则只补 L1 全局(避免与场景级重复); 否则注入 L1+L2/chat 合并文本。
        //     - 仅临时注入本次请求, 不落库(与生图策略一致), 避免污染会话记忆。
        String resolvedChatPrompt = systemPromptResolver.resolveChat();
        if (resolvedChatPrompt != null) {
            boolean sessionHasSystem = history.stream()
                    .anyMatch(m -> "system".equals(m.getRole())
                            && (m.getName() == null || !m.getName().startsWith("runtime:")));
            // 会话级已有 system: 仅补 L1 全局(会话级覆盖场景级); 否则注入完整 L1+L2/chat
            String toInject = sessionHasSystem
                    ? systemPromptResolver.resolveDefaultOnly()
                    : resolvedChatPrompt;
            if (toInject != null && history.stream().noneMatch(m ->
                    "system".equals(m.getRole()) && toInject.equals(m.getContent()))) {
                List<Message> withSys = new ArrayList<>(history);
                withSys.add(0, Message.system(toInject));
                history = withSys;
            }
        }

        // 3.1.1 V2.3 自定义 Agent 角色提示词注入: 作为会话级 system 消息, 优先级高于 L2/chat 场景级
        //       (与 sessionHasSystem 的会话级 system 同级), 模型据此扮演指定角色。
        //       仅临时注入本次请求, 不落库; 用 name="agent:role" 标识便于排查。
        if (agentOverride != null && agentOverride.systemPrompt() != null
                && !agentOverride.systemPrompt().isBlank()) {
            List<Message> withAgent = new ArrayList<>(history);
            withAgent.add(0, Message.system("agent:role", agentOverride.systemPrompt()));
            history = withAgent;
        }

        // 3.2 平台级运行时约束(生图策略 + 联网搜索引导), 与同步 chatSync 路径共用, 保证两条链路注入一致
        history = injectRuntimeSystemPrompts(history, provider, request);

        // 3.3 RAG 知识库检索增强: 已改为 Agentic 模式(混合式)。
        //     用户选的知识库 + 平台级配置的知识库, 通过 ThreadLocal 传给 KnowledgeSearchTool,
        //     由 LLM 自主决定是否调用 knowledge_base_search 工具检索。
        //     不再在此处强制检索 + system prompt 注入。

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
        //    注意: 必须基于「全量历史」而非滑窗视图估算 —— 滑窗只保留最近 max-messages 条(默认 6)，
        //    按 6 条对话估算远达不到 context-token-limit 阈值, 导致 nearLimit 恒为 false 提示从未触发。
        //    contextTokenLimit 衡量的是会话累计上下文占用, 故用 fullHistory + 当前提问来估算。
        List<Message> contextForEstimate = new ArrayList<>(fullHistory);
        contextForEstimate.add(Message.user(request.getMessage()));
        boolean nearLimit = tokenEstimator.isNearLimit(contextForEstimate);
        int est = nearLimit ? tokenEstimator.estimate(contextForEstimate) : 0;

        return new ChatContext(sessId, history, provider, imageFallbackWarning, imageUnrecognizedError,
                prep.visionModel(), prep.imageStatus(), prep.imageCaption(), prep.imageTokenUsage(),
                contextForEstimate, nearLimit, est,
                agentOverride != null ? agentOverride.allowedTools() : null,
                memoryTrimmed);
    }

    /**
     * V2.3 自定义 Agent 上下文: 加载并应用 Agent 配置到请求, 返回 Agent 角色提示词与工具白名单。
     * <p>应用项: model (覆盖) / temperature (覆盖) / kbIds (合并到 request.knowledgeBaseIds) /
     * tools (返回白名单供流式工具定义过滤) / systemPrompt (返回供 system 消息注入)。
     */
    private record AgentOverride(String systemPrompt, List<String> allowedTools) {}

    private AgentOverride applyAgentDefinition(ChatRequest request) {
        if (request.getAgentId() == null || request.getAgentId().isBlank()) return null;
        try {
            AgentDefinitionEntity agent = agentDefinitionService.loadEntity(request.getAgentId());
            // 1. model override (Agent 配置的模型优先于请求模型)
            if (agent.getModel() != null && !agent.getModel().isBlank()) {
                request.setModel(agent.getModel());
            }
            // 2. temperature override
            if (agent.getTemperature() != null) {
                request.setTemperature(agent.getTemperature().doubleValue());
            }
            // 3. kb_ids merge (Agent 关联的知识库 + 用户选的知识库, 去重合并)
            List<String> agentKbIds = parseStrList(agent.getKbIds());
            if (!agentKbIds.isEmpty()) {
                List<String> merged = new ArrayList<>(agentKbIds);
                if (request.getKnowledgeBaseIds() != null) {
                    for (String kbId : request.getKnowledgeBaseIds()) {
                        if (!merged.contains(kbId)) merged.add(kbId);
                    }
                }
                request.setKnowledgeBaseIds(merged);
            }
            // 4. tools whitelist (空列表表示不限制; null 表示无 Agent)
            List<String> allowedTools = parseStrList(agent.getTools());
            log.info("应用自定义 Agent: id={}, name={}, model={}, tools={}, kbIds={}",
                    agent.getId(), agent.getName(), agent.getModel(), allowedTools, agentKbIds);
            return new AgentOverride(agent.getSystemPrompt(), allowedTools);
        } catch (Exception e) {
            log.warn("加载 Agent 定义失败 agentId={}, 忽略 Agent 配置: {}", request.getAgentId(), e.getMessage());
            return null;
        }
    }

    /** 反序列化 JSON 数组字符串为 List<String> */
    private List<String> parseStrList(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * 合并用户选的知识库 ID + 平台级配置的知识库 ID, 供 Agentic RAG 工具使用。
     * <p>平台级知识库(visibility=PLATFORM)对所有对话无感可用, 通过 agent.rag.platform-kb-ids 配置;
     * 用户级知识库由前端选择, 通过 ChatRequest.knowledgeBaseIds 传入。
     */
    private List<String> buildEffectiveKbIds(ChatRequest request) {
        List<String> ids = new ArrayList<>();
        // 平台级知识库(无感注入)
        if (ragProperties.getPlatformKbIds() != null) {
            ids.addAll(ragProperties.getPlatformKbIds());
        }
        // 用户选的知识库
        if (request.getKnowledgeBaseIds() != null) {
            for (String kbId : request.getKnowledgeBaseIds()) {
                if (!ids.contains(kbId)) {
                    ids.add(kbId);
                }
            }
        }
        return ids;
    }

    /**
     * 构建对话审计日志的元数据 (不记录消息内容, 仅记录 token/耗时/工具等技术指标)。
     * 用于成本统计、异常排查和使用分析 (F-28)。
     */
    private Map<String, Object> buildChatAuditDetail(String model, Integer promptTokens, Integer completionTokens,
                                                     Integer totalTokens, Long durationMs, Boolean webSearch,
                                                     List<String> kbIds, List<ChatChunk> toolCalls,
                                                     boolean interrupted, String finishReason) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("model", model);
        if (totalTokens != null) {
            Map<String, Integer> usage = new LinkedHashMap<>();
            if (promptTokens != null) usage.put("prompt", promptTokens);
            if (completionTokens != null) usage.put("completion", completionTokens);
            usage.put("total", totalTokens);
            detail.put("tokenUsage", usage);
        }
        if (durationMs != null) detail.put("durationMs", durationMs);
        if (webSearch != null) detail.put("webSearch", webSearch);
        if (kbIds != null && !kbIds.isEmpty()) detail.put("knowledgeBaseIds", kbIds);
        if (toolCalls != null && !toolCalls.isEmpty()) {
            List<String> tools = new ArrayList<>();
            for (ChatChunk tc : toolCalls) {
                if (tc.getTool() != null && !tools.contains(tc.getTool())) tools.add(tc.getTool());
            }
            if (!tools.isEmpty()) detail.put("tools", tools);
        }
        if (interrupted) detail.put("interrupted", true);
        if (finishReason != null) detail.put("finishReason", finishReason);
        return detail;
    }

    /**
     * 同步对话
     */
    public ChatResponse chatSync(ChatRequest request, String currentUserId) {
        // V3.0-beta F-39: 用户级 token 配额检查 (超额抛 429, 拦截本次 LLM 调用)
        costTracker.checkUserQuota(currentUserId);

        // V2.3: 自定义 Agent 应用 (model/temperature/kb_ids/systemPrompt 覆盖)
        AgentOverride agentOverride = applyAgentDefinition(request);

        // V2.2: userId 由 Controller 从 exchange 取出后传入, 用于会话绑定
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            SessionDTO session = sessionManager.createSession("新对话", request.getModel(), null, currentUserId);
            sessionId = session.getId();
            request.setSessionId(sessionId);
        }

        // 记忆窗口: 同步路径同样只把滑窗视图发给 LLM(system 全保留 + 最近 N 条), 超限时通过 contextWarning 提示
        List<Message> fullHistorySync = memory.load(sessionId);
        List<Message> history = memory.loadWindow(sessionId);
        int memoryTrimmedSync = fullHistorySync.size() - history.size();
        ChatModelProvider provider = router.route(request, history);

        // 分层 system prompt 注入(与流式 chat 路径保持一致)
        String resolvedChatPromptSync = systemPromptResolver.resolveChat();
        if (resolvedChatPromptSync != null) {
            boolean sessionHasSystem = history.stream()
                    .anyMatch(m -> "system".equals(m.getRole())
                            && (m.getName() == null || !m.getName().startsWith("runtime:")));
            String toInject = sessionHasSystem
                    ? systemPromptResolver.resolveDefaultOnly()
                    : resolvedChatPromptSync;
            if (toInject != null && history.stream().noneMatch(m ->
                    "system".equals(m.getRole()) && toInject.equals(m.getContent()))) {
                List<Message> withSys = new ArrayList<>(history);
                withSys.add(0, Message.system(toInject));
                history = withSys;
            }
        }

        // V2.3 自定义 Agent 角色提示词注入 (与流式路径保持一致)
        if (agentOverride != null && agentOverride.systemPrompt() != null
                && !agentOverride.systemPrompt().isBlank()) {
            List<Message> withAgent = new ArrayList<>(history);
            withAgent.add(0, Message.system("agent:role", agentOverride.systemPrompt()));
            history = withAgent;
        }

        // 图片自动路由(必须先执行, 因其会 mutate request 并决定最终回答模型)
        String userOriginal = request.getMessage();
        List<String> originalImages = request.getImages();  // 捕获原始图片(相对路径/url), 单独用于落库; 模型侧统一用解析后的 base64
        ImagePrep prepSync = prepareImagesForModel(request, history, provider);
        String imageFallbackWarning = prepSync.fallbackWarning();

        // 分层 system prompt 已在路由后注入; 此处补齐平台级运行时约束(生图策略 + 联网搜索引导),
        // 与流式 chat 路径完全一致
        history = injectRuntimeSystemPrompts(history, provider, request);

        // RAG 已改为 Agentic 模式(混合式): chatSync 路径同样由 LLM 决定是否调用 knowledge_base_search 工具,
        // 不再在此处强制检索。同步路径暂不支持工具循环(chatSync 为简化路径, 仅 agent 模式才走工具),
        // 若需 Agentic RAG 请使用流式 /api/chat/stream 端点。

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

        // 上下文接近上限提示(与流式 chat 路径一致): 用「全量历史 + 当前提问」做估算,
        // 而非滑窗视图(滑窗仅保留最近 max-messages 条, 估算远达不到阈值导致提示从未触发)
        List<Message> contextForEstimateSync = new ArrayList<>(fullHistorySync);
        contextForEstimateSync.add(Message.user(request.getMessage()));
        if (tokenEstimator.isNearLimit(contextForEstimateSync) && response.getContextWarning() == null) {
            response.setContextWarning(String.format(
                    "对话已较长（约 %d token），接近上下文上限，建议开启新对话以免丢失早期内容。",
                    tokenEstimator.estimate(contextForEstimateSync)));
        }
        // 记忆窗口超限提示(优先级低于图片降级与 token 接近上限提示, 未被覆盖时下发)
        if (memoryTrimmedSync > 0 && response.getContextWarning() == null) {
            response.setContextWarning(String.format(
                    "对话已达记忆窗口上限，仅保留最近 %d 条消息参与回答（已省略最早 %d 条），完整历史仍可在会话中查看。",
                    history.stream().filter(m -> !"system".equals(m.getRole())).count(), memoryTrimmedSync));
        }
        // V3.0-beta F-34: 记录本次对话 token 用量 (成本统计/配额口径, 仅模型自身消耗;
        // 路由为平台探测成本, 不计入用户用量 —— 见 routing_logs.token 字段)
        ChatResponse.TokenUsage syncUsage = response.getTokenUsage();
        costTracker.record(currentUserId, sessionId, null, "chat",
                prepSync.answerProvider().getName(), syncUsage);

        Integer usage = syncUsage != null ? syncUsage.getTotal() : null;
        memory.save(sessionId, Message.assistant(response.getContent(), null, prepSync.answerProvider().getName(), usage));

        sessionManager.touch(sessionId);

        // 审计日志: 同步对话元数据 (F-28)
        auditService.record("chat_sync", "session", sessionId,
                buildChatAuditDetail(prepSync.answerProvider().getName(),
                        syncUsage != null ? syncUsage.getPrompt() : null,
                        syncUsage != null ? syncUsage.getCompletion() : null,
                        syncUsage != null ? syncUsage.getTotal() : null,
                        null, request.isWebSearch(), buildEffectiveKbIds(request),
                        null, false, null),
                currentUserId);

        return response;
    }

    /**
     * 注入平台级运行时 system 约束(生图策略 + 联网搜索引导)。
     * <p>
     * 抽出为独立方法, 供流式 {@code chat} 与同步 {@code chatSync} 两条路径共用, 保证注入完全一致。
     * 仅临时注入本次请求, 不落库(避免污染会话记忆); 通过「是否已存在相同内容」去重, 幂等可重复调用。
     *
     * @param history  当前对话历史(会被复制后在头部插入约束, 不修改原列表)
     * @param provider 路由后的回答模型(用于判断是否支持函数调用)
     * @param request  聊天请求(用于判断 webSearch 开关)
     * @return 注入约束后的新历史列表
     */
    private List<Message> injectRuntimeSystemPrompts(List<Message> history, ChatModelProvider provider,
                                                     ChatRequest request) {
        List<Message> result = new ArrayList<>(history);

        // 统一按「name 去重键」判断：history 中已存在同 name 的 system 消息则跳过（幂等）。
        // 所有运行时约束文本均来自 RuntimePromptProvider（配置优先 + 默认回退），不在本方法硬编码。

        // 1) 生图策略约束: 仅当本轮会注入生图工具时前置为 system 消息
        //    与 executeTools 的协议层硬收敛(每轮最多一张)形成「软 + 硬」双保险
        if (provider != null && provider.supportsFunctionCalling() && registry.getDefaultImageProvider() != null) {
            injectIfAbsent(result, runtimePromptProvider.imagePolicy());
        }

        // 2) 联网搜索引导: 用户开启联网搜索且已注册 web_search 工具时, 提示优先联网检索
        if (request != null && request.isWebSearch() && toolRegistry.getTool("web_search") != null) {
            injectIfAbsent(result, runtimePromptProvider.webSearch());
        }

        // 3) 多轮对话约束: 要求模型只回答用户「当前」提问, 历史仅作参考, 避免重复历史答案
        //    —— 当会话已有历史(user/assistant 轮次)时注入
        boolean hasHistory = result.stream().anyMatch(m ->
                "user".equals(m.getRole()) || "assistant".equals(m.getRole()));
        if (hasHistory) {
            injectIfAbsent(result, runtimePromptProvider.multiTurn());
        }

        // 4) 当前真实时间: 每轮动态注入(不幂等缓存), 保证时间是「当下」。
        //    根因修复: tool_choice=auto 下模型常不调用 time 工具而用训练知识臆造日期(差数月),
        //    此处直接把准确时间写进 system prompt, 模型无需调用工具也能拿到正确时间。
        //    时间必须每轮刷新: 用 injectOrReplace 而非 injectIfAbsent, 避免历史中旧时间被复用。
        injectOrReplace(result, runtimePromptProvider.currentTime());

        return result;
    }

    /**
     * 按 name 去重键注入一条运行时约束：history 中已存在同 name 的 system 消息则跳过（幂等可重复调用）。
     * fragment 为 null 或 text 为空时直接跳过。
     */
    private void injectIfAbsent(List<Message> history, SystemPromptProperties.PromptFragment fragment) {
        if (fragment == null || fragment.getText() == null || fragment.getText().isBlank()) {
            return;
        }
        boolean exists = history.stream().anyMatch(m ->
                "system".equals(m.getRole()) && fragment.getName().equals(m.getName()));
        if (!exists) {
            history.add(Message.system(fragment.getName(), fragment.getText()));
        }
    }

    /**
     * 按 name 去重键注入/替换一条运行时约束：已存在同 name 的 system 消息则先移除再添加（强制刷新）。
     * 用于内容随每轮变化的约束（如当前时间），避免历史旧值被复用。
     */
    private void injectOrReplace(List<Message> history, SystemPromptProperties.PromptFragment fragment) {
        if (fragment == null || fragment.getText() == null || fragment.getText().isBlank()) {
            return;
        }
        history.removeIf(m -> "system".equals(m.getRole()) && fragment.getName().equals(m.getName()));
        history.add(Message.system(fragment.getName(), fragment.getText()));
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
            // 无任何可用视觉模型: 不应继续调用非视觉模型硬答(模型会误说"未收到图片")。
            // 设置 unrecognizedError 以触发既有的"直接提示并中止、不调用 LLM"机制(流式/同步均处理)。
            String tip = String.format("当前模型「%s」不支持图片识别，且系统中没有可用的视觉模型。"
                    + "请换用支持图片识别的模型，或仅发送文字。", provider.getName());
            log.warn("用户上传了图片, 但当前模型[{}]不支持视觉且无可用视觉模型, 中止作答并提示", provider.getName());
            return new ImagePrep(null, tip, null, "none", null, null, provider);
        }
        log.info("当前模型[{}]不支持看图, 自动切换到视觉模型[{}]直接回答", provider.getName(), vision.getName());
        return new ImagePrep(
                String.format("当前模型「%s」不支持直接看图，已自动切换到视觉模型「%s」直接理解并回答图片。",
                        provider.getName(), vision.getName()),
                null, vision.getName(), "vision", null, null, vision);
    }

}
