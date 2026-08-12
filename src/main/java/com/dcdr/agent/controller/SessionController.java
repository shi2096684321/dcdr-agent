package com.dcdr.agent.controller;

import com.dcdr.agent.agent.memory.ChatMemory;
import com.dcdr.agent.agent.session.SessionManager;
import com.dcdr.agent.auth.SecurityContext;
import com.dcdr.agent.common.Result;
import com.dcdr.agent.dto.MessageDTO;
import com.dcdr.agent.dto.SessionDTO;
import com.dcdr.agent.service.ConversationExportService;
import com.dcdr.agent.service.ConversationExportService.Format;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 会话接口 (对话模式)。
 * <p>
 * V2.2: 接入用户认证后, 所有查询/操作按 {@link SecurityContext#getUserId()} 隔离,
 * 未登录用户(auth.enabled=false 或白名单)退化为全局可见(V2.1 兼容)。
 * <p>
 * V2.2 权限: 读操作(list/get/messages/export)需 {@code session:read},
 * 写操作(create/delete/rename/update/deleteMessages)需 {@code session:write}。
 */
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
@Tag(name = "会话管理", description = "对话模式的会话管理：创建、查询、删除及消息历史")
public class SessionController {

    private final SessionManager sessionManager;
    private final ChatMemory memory;
    private final ConversationExportService exportService;

    @Operation(summary = "获取会话列表", description = "返回当前用户的所有会话(认证启用时按 userId 隔离; 未登录返回全部)")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SessionDTO.class)))
    @GetMapping
    public Result<List<SessionDTO>> list(ServerWebExchange exchange) {
        return Result.success(sessionManager.listSessions(SecurityContext.getUserId(exchange)));
    }

    @Operation(summary = "创建会话", description = "创建新的对话会话; V2.2 关联当前登录用户")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SessionDTO.class)))
    @PostMapping
    public Result<SessionDTO> create(@RequestBody Map<String, String> body,
                                     ServerWebExchange exchange) {
        String title = body.get("title");
        String model = body.get("model");
        String systemPrompt = body.get("systemPrompt");
        return Result.success(sessionManager.createSession(title, model, systemPrompt, SecurityContext.getUserId(exchange)));
    }

    @Operation(summary = "获取会话详情")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SessionDTO.class)))
    @ApiResponse(responseCode = "404", description = "会话不存在")
    @GetMapping("/{id}")
    public Result<SessionDTO> get(@Parameter(description = "会话 ID", required = true) @PathVariable String id,
                                  ServerWebExchange exchange) {
        SessionDTO session = sessionManager.getSession(id, SecurityContext.getUserId(exchange));
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

    @Operation(summary = "更新会话级 System Prompt",
            description = "更新后同时同步落库字段与消息历史中的 system 消息，保证后续对话立即生效。传空字符串可清除。")
    @ApiResponse(responseCode = "200", description = "更新成功")
    @ApiResponse(responseCode = "400", description = "systemPrompt 过长")
    @ApiResponse(responseCode = "404", description = "会话不存在")
    @PatchMapping("/{id}/system-prompt")
    public Result<Void> updateSystemPrompt(@Parameter(description = "会话 ID", required = true) @PathVariable String id,
                                           @RequestBody Map<String, String> body) {
        if (sessionManager.getSession(id) == null) {
            return Result.error(404, "会话不存在");
        }
        try {
            sessionManager.updateSystemPrompt(id, body.get("systemPrompt"));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
        return Result.success();
    }

    @Operation(summary = "获取会话消息历史", description = "返回指定会话的全部消息记录")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = MessageDTO.class)))
    @GetMapping("/{id}/messages")
    public Result<List<MessageDTO>> messages(@Parameter(description = "会话 ID", required = true) @PathVariable String id) {
        return Result.success(sessionManager.getMessages(id));
    }

    @Operation(summary = "删除会话中的部分消息", description = "删除 seq >= fromSeq 的消息，用于编辑历史或重试失败时截断上下文")
    @ApiResponse(responseCode = "200", description = "删除成功")
    @DeleteMapping("/{id}/messages")
    public Result<Void> deleteMessages(@Parameter(description = "会话 ID", required = true) @PathVariable String id,
                                       @Parameter(description = "从此序号(含)开始删除", required = true) @RequestParam int fromSeq) {
        if (sessionManager.getSession(id) == null) {
            return Result.error(404, "会话不存在");
        }
        memory.deleteFrom(id, fromSeq);
        return Result.success();
    }

    @Operation(summary = "导出对话", description = "导出会话为 Markdown 或 JSON 文件下载; V2.1 F-27")
    @ApiResponse(responseCode = "200", description = "返回文件流, Content-Disposition 触发浏览器下载")
    @ApiResponse(responseCode = "404", description = "会话不存在")
    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(
            @Parameter(description = "会话 ID", required = true) @PathVariable String id,
            @Parameter(description = "导出格式: markdown | json (默认 markdown)") @RequestParam(defaultValue = "markdown") String format) {
        Format fmt = Format.from(format);
        ConversationExportService.ExportResult result = exportService.export(id, fmt);
        String disposition = "attachment; filename=\"" + result.filename()
                + "\"; filename*=UTF-8''" + java.net.URLEncoder.encode(result.filename(), StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, result.contentType() + "; charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .body(result.content());
    }
}
