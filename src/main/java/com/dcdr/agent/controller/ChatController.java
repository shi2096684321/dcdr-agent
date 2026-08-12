package com.dcdr.agent.controller;

import com.dcdr.agent.agent.ActiveChatRegistry;
import com.dcdr.agent.agent.AgentOrchestrator;
import com.dcdr.agent.auth.SecurityContext;
import com.dcdr.agent.common.Result;
import com.dcdr.agent.dto.ChatChunk;
import com.dcdr.agent.dto.ChatImageRequest;
import com.dcdr.agent.dto.ChatRequest;
import com.dcdr.agent.dto.ChatResponse;
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

/**
 * 对话接口
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@Tag(name = "对话", description = "AI 对话接口，支持流式 (SSE) 与同步两种模式")
public class ChatController {

    private final AgentOrchestrator orchestrator;
    private final ActiveChatRegistry activeChatRegistry;

    /**
     * 流式对话 (SSE)
     */
    @Operation(
            summary = "流式对话 (SSE)",
            description = "通过 Server-Sent Events 逐块推送对话内容，支持工具调用过程的实时推送。\n\n" +
                    "块类型 (ChatChunk.type):\n" +
                    "- `chunk`: 文本内容片段\n" +
                    "- `tool_call`: 工具调用开始\n" +
                    "- `tool_result`: 工具执行结果\n" +
                    "- `done`: 结束块，携带 token 统计\n" +
                    "- `error`: 错误信息\n" +
                    "- `interrupted`: 用户主动中断\n" +
                    "- `context_warning`: 上下文接近上限，建议开启新对话",
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
        return orchestrator.chat(request, SecurityContext.getUserId(exchange))
                .map(chunk -> ServerSentEvent.<ChatChunk>builder().data(chunk).build());
    }

    /**
     * 对话内文生图 (SSE)
     * <p>
     * 按钮触发的文生图: 即时流式返回生成的图片, 并落库用户消息与助手图片消息。
     */
    @Operation(
            summary = "对话内文生图 (SSE)",
            description = "根据提示词生成图片, 通过 SSE 逐块推送。\n\n" +
                    "块类型 (ChatChunk.type):\n" +
                    "- `status`: 生成中提示\n" +
                    "- `image`: 图片结果(携带 imageUrl)\n" +
                    "- `done`: 结束块\n" +
                    "- `error`: 错误信息",
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
                .map(chunk -> ServerSentEvent.<ChatChunk>builder().data(chunk).build());
    }

    /**
     * 同步对话
     */
    @Operation(
            summary = "同步对话",
            description = "阻塞式返回完整对话结果，适用于不需要流式输出的场景。",
            responses = {
                    @ApiResponse(responseCode = "200", description = "成功",
                            content = @Content(schema = @Schema(implementation = ChatResponse.class)))
            }
    )
    @PostMapping
    public Result<ChatResponse> chat(@RequestBody ChatRequest request,
                                     ServerWebExchange exchange) {
        log.info("同步对话: session={}", request.getSessionId());
        return Result.success(orchestrator.chatSync(request, SecurityContext.getUserId(exchange)));
    }

    /**
     * 中断会话(停止生成)
     * <p>
     * 前端在流式对话进行中调用, 取消上游大模型调用并下发 interrupted 信号。
     * 返回 true 表示确有进行中的对话被中断, false 表示该会话当前没有进行中的对话。
     */
    @Operation(
            summary = "中断会话(停止生成)",
            description = "中断指定会话正在进行的流式对话, 真正取消底层模型 HTTP 请求。\n\n" +
                    "适用于前端「停止生成」按钮。若客户端已断开连接, 后端会自动取消, 无需调用本接口。"
    )
    @PostMapping("/stop")
    public Result<Boolean> stop(@RequestParam String sessionId) {
        log.info("中断会话请求: session={}", sessionId);
        boolean stopped = activeChatRegistry.stop(sessionId);
        return Result.success(stopped);
    }
}
