package com.example.agent.controller;

import com.example.agent.agent.session.SessionManager;
import com.example.agent.common.Result;
import com.example.agent.dto.MessageDTO;
import com.example.agent.dto.SessionDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 会话接口 (对话模式)
 */
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
@Tag(name = "会话管理", description = "对话模式的会话管理：创建、查询、删除及消息历史")
public class SessionController {

    private final SessionManager sessionManager;

    @Operation(summary = "获取会话列表", description = "返回当前所有会话（不含消息内容）")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SessionDTO.class)))
    @GetMapping
    public Result<List<SessionDTO>> list() {
        return Result.success(sessionManager.listSessions());
    }

    @Operation(summary = "创建会话", description = "创建新的对话会话")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SessionDTO.class)))
    @PostMapping
    public Result<SessionDTO> create(@RequestBody Map<String, String> body) {
        String title = body.get("title");
        String model = body.get("model");
        String systemPrompt = body.get("systemPrompt");
        return Result.success(sessionManager.createSession(title, model, systemPrompt));
    }

    @Operation(summary = "获取会话详情")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SessionDTO.class)))
    @ApiResponse(responseCode = "404", description = "会话不存在")
    @GetMapping("/{id}")
    public Result<SessionDTO> get(@Parameter(description = "会话 ID", required = true) @PathVariable String id) {
        SessionDTO session = sessionManager.getSession(id);
        if (session == null) return Result.error(404, "会话不存在");
        return Result.success(session);
    }

    @Operation(summary = "删除会话")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "会话 ID", required = true) @PathVariable String id) {
        sessionManager.deleteSession(id);
        return Result.success();
    }

    @Operation(summary = "重命名会话", description = "修改会话标题，覆盖自动生成的标题")
    @ApiResponse(responseCode = "200", description = "重命名成功")
    @PutMapping("/{id}/title")
    public Result<Void> rename(@Parameter(description = "会话 ID", required = true) @PathVariable String id,
                               @RequestBody Map<String, String> body) {
        String title = body.get("title");
        if (title == null || title.isBlank()) return Result.error(400, "标题不能为空");
        sessionManager.updateTitle(id, title);
        return Result.success();
    }

    @Operation(summary = "获取会话消息历史", description = "返回指定会话的全部消息记录")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = MessageDTO.class)))
    @GetMapping("/{id}/messages")
    public Result<List<MessageDTO>> messages(@Parameter(description = "会话 ID", required = true) @PathVariable String id) {
        return Result.success(sessionManager.getMessages(id));
    }
}
