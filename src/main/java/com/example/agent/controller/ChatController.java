package com.example.agent.controller;

import com.example.agent.agent.AgentOrchestrator;
import com.example.agent.common.Result;
import com.example.agent.dto.ChatChunk;
import com.example.agent.dto.ChatRequest;
import com.example.agent.dto.ChatResponse;
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
                    "- `error`: 错误信息",
            responses = {
                    @ApiResponse(responseCode = "200", description = "SSE 流，逐条推送 ChatChunk",
                            content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                                    schema = @Schema(implementation = ChatChunk.class)))
            }
    )
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatChunk>> chatStream(@RequestBody ChatRequest request) {
        log.info("流式对话: session={}", request.getSessionId());
        return orchestrator.chat(request)
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
    public Result<ChatResponse> chat(@RequestBody ChatRequest request) {
        log.info("同步对话: session={}", request.getSessionId());
        return Result.success(orchestrator.chatSync(request));
    }
}
