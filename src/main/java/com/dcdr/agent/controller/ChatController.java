package com.dcdr.agent.controller;

import com.dcdr.agent.agent.ActiveChatRegistry;
import com.dcdr.agent.agent.AgentOrchestrator;
import com.dcdr.agent.agent.memory.ChatMemory;
import com.dcdr.agent.agent.memory.Message;
import com.dcdr.agent.agent.prompt.ContextLimitPreference;
import com.dcdr.agent.agent.router.IntentRouter;
import com.dcdr.agent.agent.session.SessionManager;
import com.dcdr.agent.agent.workflow.WorkflowEngine;
import com.dcdr.agent.auth.SecurityContext;
import com.dcdr.agent.auth.ownership.AuthContext;
import com.dcdr.agent.auth.ownership.OwnershipGuard;
import com.dcdr.agent.auth.ownership.ResourceType;
import com.dcdr.agent.common.Result;
import com.dcdr.agent.common.log.LlmCallTrace;
import com.dcdr.agent.config.V3Properties;
import com.dcdr.agent.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 对话接口。
 * <p>
 * V3.0-alpha (F-30 智能路由层): 对话入口集成 IntentRouter, 用户正常说话即可自动命中
 * 工作流 / 单 Agent, 全程无感。可通过 {@code agent.v3.smart-routing.enabled=false} 关闭,
 * 退化为 V2.3 行为。
 * <p>
 * <b>线程模型</b>: 路由 + 工作流执行 + 同步对话均为阻塞 LLM 调用, 必须切到 boundedElastic
 * 线程池, 避免阻塞 reactor-http-nio (项目硬约束)。
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@Tag(name = "对话", description = "AI 对话接口，支持流式 (SSE) 与同步两种模式；V3.0 集成智能路由 (F-30)")
public class ChatController {

    private final AgentOrchestrator orchestrator;
    private final ActiveChatRegistry activeChatRegistry;
    private final IntentRouter intentRouter;
    private final WorkflowEngine workflowEngine;
    private final SessionManager sessionManager;
    private final ChatMemory memory;
    private final V3Properties v3Properties;
    /** 调用链日志落库(与定时任务/工作流共用同一个组件, 避免各自维护一套字段映射) */
    private final com.dcdr.agent.agent.log.LlmCallLogWriter callLogWriter;
    /** V3.1 F-41: 对话内 RAG 检索范围的归属校验(防止携带他人知识库 ID 越权检索) */
    private final OwnershipGuard ownershipGuard;
    /** V3.4: 「总上下文大小」的用户级设置(占用百分比的分母) */
    private final ContextLimitPreference contextLimitPreference;

    private static final Function<ChatChunk, ServerSentEvent<ChatChunk>> TO_SSE =
            chunk -> ServerSentEvent.<ChatChunk>builder().data(chunk).build();

    /**
     * 流式对话 (SSE)。
     * <p>
     * V3.0: 先做一次轻量路由判断, 命中工作流则静默执行并以工作流输出作为回复;
     * 命中单 Agent 则注入 agentId; 否则走普通对话。
     */
    @Operation(
            summary = "流式对话 (SSE)",
            description = "通过 Server-Sent Events 逐块推送对话内容，支持工具调用过程的实时推送。\n\n" +
                    "V3.0: 集成智能路由, 对话即触发工作流/Agent (F-30)。\n\n" +
                    "块类型 (ChatChunk.type):\n" +
                    "- `chunk`: 文本内容片段\n" +
                    "- `tool_call`: 工具调用开始\n" +
                    "- `tool_result`: 工具执行结果\n" +
                    "- `done`: 结束块，携带 token 统计\n" +
                    "- `error`: 错误信息\n" +
                    "- `interrupted`: 用户主动中断\n" +
                    "- `context_warning`: 上下文接近上限，建议开启新对话\n" +
                    "- `status`: 路由/处理中提示\n" +
                    "- `user_input_request`: 模型需要用户确认/补充信息，携带 questions 选项列表。\n" +
                    "  **收到本块后本轮立即结束**（其后只有 done），前端渲染选项卡片；用户选择后\n" +
                    "  以普通请求再发一次，并把选择放在 `questionAnswer` 字段里（形状 `{q1:[值...]}`）。\n" +
                    "  用户不点选项、直接打字也是合法回答，此时 questionAnswer 可省略。",
            responses = {
                    @ApiResponse(responseCode = "200", description = "SSE 流，逐条推送 ChatChunk",
                            content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                                    schema = @Schema(implementation = ChatChunk.class)))
            }
    )
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatChunk>> chatStream(@RequestBody ChatRequest request,
                                                       ServerWebExchange exchange) {
        log.info("流式对话: session={}", request.getSessionId());
        String userId = SecurityContext.getUserId(exchange);
        // V3.1 F-41: 剔除请求中越权的知识库 ID(对话内 RAG 检索范围)
        sanitizeKnowledgeBaseIds(request, exchange);
        // LLM 调用链: 本轮请求的全部模型调用(路由/各生成轮)+工具步, 结束输出汇总(grep 'llm-trace')
        // 注意收尾处的 doFinally 把落库切到了 boundedElastic: 链路快照的持久化是同步 JPA 写,
        // 而 cancel(用户点停止 / 前端断开)时的终止信号可能来自 reactor-http-nio 线程
        final LlmCallTrace trace = LlmCallTrace.begin();
        callLogWriter.bind(trace, userId, request.getSessionId(), request.getMessage(),
                com.dcdr.agent.agent.session.SessionSource.USER);

        // 用户已显式选择 Agent / 无消息 → 跳过路由, 尊重显式选择
        if (shouldSkipRouting(request)) {
            return orchestrator.chat(request, userId, trace)
                    .map(TO_SSE)
                    .doFinally(sig -> Schedulers.boundedElastic().schedule(() -> trace.summary(sig.name())));
        }

        return Mono.fromCallable(() -> intentRouter.route(request.getMessage(), userId, request.getSessionId(), trace))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(route -> applyRouteStream(route, request, userId, trace))
                .map(TO_SSE)
                .doFinally(sig -> Schedulers.boundedElastic().schedule(() -> trace.summary(sig.name())))
                .onErrorResume(e -> {
                    log.error("流式对话处理失败", e);
                    return Flux.just(TO_SSE.apply(ChatChunk.error("对话处理失败: " + e.getMessage(), "CHAT_ERROR")));
                });
    }

    /** 应用路由结果到流式对话 */
    private Flux<ChatChunk> applyRouteStream(RoutingDtos.RouteResult route, ChatRequest request, String userId,
                                             LlmCallTrace trace) {
        Flux<ChatChunk> hint = routeHint(route);
        if (route.isWorkflow()) {
            // 工作流: 静默执行, 结果作为回复
            return hint.concatWith(
                    Mono.fromCallable(() -> executeWorkflowAsReply(route, request, userId))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMapMany(this::buildWorkflowChunks));
        }
        if (route.isAgent() && (request.getAgentId() == null || request.getAgentId().isBlank())) {
            request.setAgentId(route.getTarget());
        }
        return hint.concatWith(orchestrator.chat(request, userId, trace));
    }

    /** 工作流执行结果转流式块 */
    private Flux<ChatChunk> buildWorkflowChunks(WorkflowDtos.ExecutionResult res) {
        List<ChatChunk> chunks = new ArrayList<>();
        if ("failed".equals(res.getStatus()) && res.getError() != null) {
            chunks.add(ChatChunk.error("工作流执行失败: " + res.getError(), "WORKFLOW_FAILED"));
        } else {
            chunks.add(ChatChunk.chunk(replyOf(res)));
        }
        chunks.add(ChatChunk.done(0, 0, 0, res.getTotalDurationMs()));
        return Flux.fromIterable(chunks);
    }

    /**
     * 对话内文生图 (SSE)
     */
    @Operation(
            summary = "对话内文生图 (SSE)",
            description = "根据提示词生成图片, 通过 SSE 逐块推送。",
            responses = {
                    @ApiResponse(responseCode = "200", description = "SSE 流，逐条推送 ChatChunk",
                            content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                                    schema = @Schema(implementation = ChatChunk.class)))
            }
    )
    @PostMapping(value = "/image", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatChunk>> chatImage(@RequestBody ChatImageRequest request,
                                                      ServerWebExchange exchange) {
        log.info("对话内文生图: session={}, prompt={}", request.getSessionId(), request.getPrompt());
        return orchestrator.generateImageInChat(request, SecurityContext.getUserId(exchange))
                .map(TO_SSE);
    }

    /**
     * 同步对话。
     * <p>
     * V3.0: 集成智能路由 (F-30)。阻塞调用 LLM, 在 boundedElastic 线程池执行。
     */
    @Operation(
            summary = "同步对话",
            description = "阻塞式返回完整对话结果。V3.0 集成智能路由, 命中工作流时返回工作流输出。",
            responses = {
                    @ApiResponse(responseCode = "200", description = "成功",
                            content = @Content(schema = @Schema(implementation = ChatResponse.class)))
            }
    )
    @PostMapping
    public Mono<Result<ChatResponse>> chat(@RequestBody ChatRequest request,
                                           ServerWebExchange exchange) {
        log.info("同步对话: session={}", request.getSessionId());
        String userId = SecurityContext.getUserId(exchange);
        // V3.1 F-41: 剔除请求中越权的知识库 ID(对话内 RAG 检索范围)
        sanitizeKnowledgeBaseIds(request, exchange);
        // LLM 调用链(与流式路径一致): 路由 + 生成调用, 结束输出汇总(grep 'llm-trace')
        final LlmCallTrace trace = LlmCallTrace.begin();
        callLogWriter.bind(trace, userId, request.getSessionId(), request.getMessage(),
                com.dcdr.agent.agent.session.SessionSource.USER);

        if (shouldSkipRouting(request)) {
            return Mono.fromCallable(() -> orchestrator.chatSync(request, userId, trace))
                    .subscribeOn(Schedulers.boundedElastic())
                    .map(Result::success)
                    .doFinally(sig -> Schedulers.boundedElastic().schedule(() -> trace.summary(sig.name())));
        }
        return Mono.fromCallable(() -> {
                    RoutingDtos.RouteResult route = intentRouter.route(request.getMessage(), userId, request.getSessionId(), trace);
                    return applyRouteSync(route, request, userId, trace);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::success)
                .doFinally(sig -> Schedulers.boundedElastic().schedule(() -> trace.summary(sig.name())));
    }

    /** 应用路由结果到同步对话 */
    private ChatResponse applyRouteSync(RoutingDtos.RouteResult route, ChatRequest request, String userId,
                                        LlmCallTrace trace) {
        if (route.isWorkflow()) {
            WorkflowDtos.ExecutionResult res = executeWorkflowAsReply(route, request, userId);
            ChatResponse resp = new ChatResponse();
            resp.setContent(replyOf(res));
            resp.setContextWarning("已自动使用「" + route.getTargetName() + "」工作流处理");
            return resp;
        }
        if (route.isAgent() && (request.getAgentId() == null || request.getAgentId().isBlank())) {
            request.setAgentId(route.getTarget());
        }
        return orchestrator.chatSync(request, userId, trace);
    }

    // ===== 归属校验辅助 (V3.1 F-41) =====

    /**
     * 过滤对话内 RAG 检索的知识库范围。
     * <p>
     * {@code ChatRequest.knowledgeBaseIds} 由前端传入, 可能包含他人 USER 级知识库 ID ——
     * 直接采信会让用户检索到他人的私有文档(V3.0 遗留越权面)。此处按统一归属口径
     * ({@link OwnershipGuard}) 逐个校验, 不可访问的 ID <b>静默剔除</b>而非报错,
     * 避免攻击者借错误信息确认资源是否存在。
     * <p>
     * 仅作用于 HTTP 入口(用户请求); 工作流定义 / 定时任务内部链路的知识库由平台配置,
     * 属于可信来源, 不在此过滤。
     */
    private void sanitizeKnowledgeBaseIds(ChatRequest request, ServerWebExchange exchange) {
        List<String> requested = request.getKnowledgeBaseIds();
        if (requested == null || requested.isEmpty()) return;

        AuthContext ctx = ownershipGuard.context(exchange);
        List<String> allowed = new ArrayList<>(requested.size());
        for (String kbId : requested) {
            if (ownershipGuard.canAccess(ResourceType.KNOWLEDGE_BASE, kbId, ctx, false)) {
                allowed.add(kbId);
            } else {
                log.warn("对话请求携带无权限的知识库 ID, 已剔除: kbId={}, user={}", kbId, ctx.userId());
            }
        }
        request.setKnowledgeBaseIds(allowed);
    }

    // ===== 路由辅助 =====

    /** 是否跳过智能路由 (用户已显式选择 Agent, 或无消息) */
    private boolean shouldSkipRouting(ChatRequest request) {
        if (request.getAgentId() != null && !request.getAgentId().isBlank()) return true;
        if (request.getMessage() == null || request.getMessage().isBlank()) return true;
        return false;
    }

    /** 路由命中提示 (可由 agent.v3.smart-routing.visible-hint 关闭) */
    private Flux<ChatChunk> routeHint(RoutingDtos.RouteResult route) {
        if (!v3Properties.getSmartRouting().isVisibleHint()) return Flux.empty();
        if (route.getTargetName() == null) return Flux.empty();
        if (route.isWorkflow()) {
            return Flux.just(ChatChunk.status("已自动使用「" + route.getTargetName() + "」工作流处理…"));
        }
        if (route.isAgent()) {
            return Flux.just(ChatChunk.status("已自动使用「" + route.getTargetName() + "」Agent…"));
        }
        return Flux.empty();
    }

    /**
     * 执行工作流并把结果作为对话回复持久化到当前会话 (用户消息 + 助手消息)。
     * <p>
     * 工作流内部各步在临时会话中执行 (不污染主会话), 仅把最终输出落库为主会话的助手消息,
     * 保证对话历史完整、刷新后可见。
     */
    private WorkflowDtos.ExecutionResult executeWorkflowAsReply(RoutingDtos.RouteResult route,
                                                                ChatRequest request, String userId) {
        // 确保会话存在
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            SessionDTO sess = sessionManager.createSession("新对话", request.getModel(), userId);
            sessionId = sess.getId();
            request.setSessionId(sessionId);
        }
        boolean firstMessage = memory.load(sessionId).stream()
                .noneMatch(m -> "user".equals(m.getRole()));
        memory.save(sessionId, Message.user(request.getMessage()));
        if (firstMessage) {
            sessionManager.autoTitleFromFirstMessage(sessionId, request.getMessage());
        }
        // 执行工作流 (trigger_type=chat, 记录执行轨迹)
        WorkflowDtos.ExecuteWorkflowRequest er = new WorkflowDtos.ExecuteWorkflowRequest();
        er.setWorkflowId(route.getTarget());
        er.setUserInput(request.getMessage());
        er.setTriggerType("chat");
        WorkflowDtos.ExecutionResult res = workflowEngine.execute(er, userId);
        // 落库助手回复
        String reply = replyOf(res);
        memory.save(sessionId, Message.assistant(reply, null, "workflow:" + route.getTargetName()));
        sessionManager.touch(sessionId);
        return res;
    }

    /** 工作流执行结果 → 回复文本 */
    private String replyOf(WorkflowDtos.ExecutionResult res) {
        if (res.getError() != null) return "[工作流执行失败: " + res.getError() + "]";
        if (res.getFinalOutput() != null && !res.getFinalOutput().isBlank()) return res.getFinalOutput();
        return "[工作流未产生输出]";
    }

    /**
     * 中断会话(停止生成)
     */
    @Operation(
            summary = "中断会话(停止生成)",
            description = "中断指定会话正在进行的流式对话, 真正取消底层模型 HTTP 请求。"
    )
    @PostMapping("/stop")
    public Result<Boolean> stop(@RequestParam String sessionId) {
        log.info("中断会话请求: session={}", sessionId);
        boolean stopped = activeChatRegistry.stop(sessionId);
        return Result.success(stopped);
    }

    /**
     * 总上下文大小 (V3.4) —— 上下文占用百分比的分母, 也是"接近上限"告警的阈值依据。
     * <p>
     * 挂在对话接口下而不是系统设置接口下: 它只影响对话侧的展示与告警, 与部署级配置无关。
     */
    @Operation(summary = "获取总上下文设置 (V3.4)",
            description = "返回当前生效上限、用户自己设置的值(null=跟随部署默认)、部署默认值与允许范围。")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ContextLimitDtos.ContextLimitView.class)))
    @GetMapping("/context-limit")
    public Mono<Result<ContextLimitDtos.ContextLimitView>> getContextLimit() {
        return ownershipGuard.currentContext()
                .flatMap(ctx -> Mono.fromCallable(() -> Result.success(limitView(ctx.userId())))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    @Operation(summary = "设置总上下文 (V3.4)",
            description = "limit=null 表示恢复默认(清除个人设置, 重新跟随部署配置)。"
                    + "取值越界返回 400; 无登录用户时忽略写入并在返回体里标记 editable=false。")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ContextLimitDtos.ContextLimitView.class)))
    @ApiResponse(responseCode = "400", description = "取值超出允许范围")
    @PutMapping("/context-limit")
    public Mono<Result<ContextLimitDtos.ContextLimitView>> setContextLimit(
            @RequestBody(required = false) ContextLimitDtos.ContextLimitRequest req) {
        return ownershipGuard.currentContext()
                .flatMap(ctx -> Mono.fromCallable(() -> {
                    try {
                        contextLimitPreference.update(ctx.userId(), req == null ? null : req.getLimit());
                    } catch (IllegalArgumentException e) {
                        // 越界是用户输入问题(400), 不是服务端故障; 沿用项目错误码口径
                        return Result.<ContextLimitDtos.ContextLimitView>error(400, e.getMessage());
                    }
                    // 回传完整视图而不是 boolean: 界面同时要更新"生效值"和"是否有个人设置", 省一次 GET
                    return Result.success(limitView(ctx.userId()));
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    /** 组装总上下文视图(三值一次给全, 见 {@code ContextLimitDtos} 的类注释) */
    private ContextLimitDtos.ContextLimitView limitView(String userId) {
        ContextLimitDtos.ContextLimitView view = new ContextLimitDtos.ContextLimitView();
        view.setLimit(contextLimitPreference.effectiveLimit(userId));
        view.setUserValue(contextLimitPreference.userChoice(userId));
        view.setDefaultValue(contextLimitPreference.deploymentDefault());
        view.setMin(ContextLimitPreference.MIN_LIMIT);
        view.setMax(ContextLimitPreference.MAX_LIMIT);
        // 认证关闭时没有用户行可写 —— 让界面直接禁用输入框, 而不是让用户改完发现没保存
        view.setEditable(userId != null);
        // 档位清单由后端给: 部署默认值会变, 标签里还要标"哪一档是当前默认", 前端拼不出来
        view.setOptions(contextLimitPreference.options().stream().map(o -> {
            ContextLimitDtos.ContextLimitView.Option opt = new ContextLimitDtos.ContextLimitView.Option();
            opt.setValue(o.value());
            opt.setLabel(o.label());
            return opt;
        }).toList());
        return view;
    }
}
