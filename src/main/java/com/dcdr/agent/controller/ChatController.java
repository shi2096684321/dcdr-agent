package com.dcdr.agent.controller;

import com.dcdr.agent.agent.ActiveChatRegistry;
import com.dcdr.agent.agent.AgentOrchestrator;
import com.dcdr.agent.agent.memory.ChatMemory;
import com.dcdr.agent.agent.memory.Message;
import com.dcdr.agent.agent.router.IntentRouter;
import com.dcdr.agent.agent.session.SessionManager;
import com.dcdr.agent.agent.workflow.WorkflowEngine;
import com.dcdr.agent.auth.SecurityContext;
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
    private final com.dcdr.agent.repository.LlmCallLogRepository llmCallLogRepository;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

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
                    "- `status`: 路由/处理中提示",
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
        // LLM 调用链: 本轮请求的全部模型调用(路由/各生成轮)+工具步, 结束输出汇总(grep 'llm-trace')
        final LlmCallTrace trace = LlmCallTrace.begin();
        registerLogSink(trace, userId, request.getSessionId(), request.getMessage());

        // 用户已显式选择 Agent / 无消息 → 跳过路由, 尊重显式选择
        if (shouldSkipRouting(request)) {
            return orchestrator.chat(request, userId, trace)
                    .map(TO_SSE)
                    .doFinally(sig -> trace.summary(sig.name()));
        }

        return Mono.fromCallable(() -> intentRouter.route(request.getMessage(), userId, request.getSessionId(), trace))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(route -> applyRouteStream(route, request, userId, trace))
                .map(TO_SSE)
                .doFinally(sig -> trace.summary(sig.name()))
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
        // LLM 调用链(与流式路径一致): 路由 + 生成调用, 结束输出汇总(grep 'llm-trace')
        final LlmCallTrace trace = LlmCallTrace.begin();
        registerLogSink(trace, userId, request.getSessionId(), request.getMessage());

        if (shouldSkipRouting(request)) {
            return Mono.fromCallable(() -> orchestrator.chatSync(request, userId, trace))
                    .subscribeOn(Schedulers.boundedElastic())
                    .map(Result::success)
                    .doFinally(sig -> trace.summary(sig.name()));
        }
        return Mono.fromCallable(() -> {
                    RoutingDtos.RouteResult route = intentRouter.route(request.getMessage(), userId, request.getSessionId(), trace);
                    return applyRouteSync(route, request, userId, trace);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::success)
                .doFinally(sig -> trace.summary(sig.name()));
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
            SessionDTO sess = sessionManager.createSession("新对话", request.getModel(), null, userId);
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
     * 注册调用链日志落库回调: 请求结束后(无论正常/取消/出错)把本轮 LLM 调用链快照
     * 持久化到独立表 llm_call_logs, 供前端"调用链日志"独立页面分页查看(不进对话内容)。
     */
    private void registerLogSink(LlmCallTrace trace, String userId, String sessionId, String userMessage) {
        trace.onSummary(summary -> {
            try {
                com.dcdr.agent.entity.LlmCallLogEntity entity = new com.dcdr.agent.entity.LlmCallLogEntity();
                entity.setId(java.util.UUID.randomUUID().toString());
                entity.setTurnId(summary.turnId());
                entity.setUserId(userId);
                entity.setSessionId(sessionId);
                entity.setUserMessage(userMessage == null ? "" :
                        (userMessage.length() > 200 ? userMessage.substring(0, 200) : userMessage));
                entity.setEndSignal(summary.endSignal());
                entity.setLlmCalls(summary.llmCalls());
                entity.setToolSteps(summary.toolSteps());
                entity.setTotalInTokens(summary.totalIn());
                entity.setTotalOutTokens(summary.totalOut());
                entity.setTotalDurationMs(summary.totalMs());
                entity.setChain(summary.chain());
                entity.setStepsJson(objectMapper.writeValueAsString(summary.steps()));
                entity.setCreatedAt(java.time.LocalDateTime.now());
                llmCallLogRepository.save(entity);
            } catch (Exception e) {
                log.error("保存调用链日志失败: turn={}", summary.turnId(), e);
            }
        });
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
}
