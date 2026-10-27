package com.dcdr.agent.controller;

import com.dcdr.agent.auth.ownership.OwnershipGuard;
import com.dcdr.agent.common.Result;
import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.entity.SessionEntity;
import com.dcdr.agent.repository.SessionRepository;
import com.dcdr.agent.workspace.SessionRewindService;
import com.dcdr.agent.workspace.WorkspaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 会话与工作区的绑定 + 撤回接口(V3.3 F-49)。
 * <p>
 * 独立成 Controller 而不塞进 {@code SessionController}: 那块是老代码, 改动面越小越好,
 * 而且这里的权限语义是 session:write(绑定) + 工作区归属校验(使用)两层。
 */
@RestController
@RequestMapping("/api/sessions/{id}")
@RequiredArgsConstructor
@Tag(name = "会话工作区", description = "会话绑定工作区、查看可撤回点、撤回")
public class SessionWorkspaceController {

    private final SessionRepository sessionRepository;
    private final WorkspaceService workspaceService;
    private final SessionRewindService rewindService;
    private final OwnershipGuard ownershipGuard;

    @Operation(summary = "绑定工作区", description = "把某个工作区授权给本会话使用; 传空 workspaceId 表示解绑")
    @ApiResponse(responseCode = "404", description = "会话或工作区不存在/无权访问")
    @PutMapping("/workspace")
    public Mono<Result<Map<String, String>>> bind(
            @Parameter(description = "会话 ID", required = true) @PathVariable String id,
            @RequestBody Map<String, String> body) {
        return ownershipGuard.currentContext().map(ctx -> {
            SessionEntity session = requireSession(id, ctx.userId(), ctx.admin());
            String workspaceId = body.get("workspaceId");
            if (workspaceId != null && !workspaceId.isBlank()) {
                // 只允许绑定自己的(或 admin 绑定任意)工作区, 且要求工作区本身存在
                workspaceService.requireOwned(workspaceId, ctx.userId(), ctx.admin());
            } else {
                workspaceId = null;
            }
            session.setWorkspaceId(workspaceId);
            session.setUpdatedAt(LocalDateTime.now());
            sessionRepository.save(session);
            return Result.success(Map.of("sessionId", id,
                    "workspaceId", workspaceId == null ? "" : workspaceId));
        });
    }

    @Operation(summary = "解绑工作区", description = "解除本会话与工作区的关联, 不删除工作区, 也不动磁盘文件")
    @DeleteMapping("/workspace")
    public Mono<Result<Void>> unbind(
            @Parameter(description = "会话 ID", required = true) @PathVariable String id) {
        return ownershipGuard.currentContext().map(ctx -> {
            SessionEntity session = requireSession(id, ctx.userId(), ctx.admin());
            session.setWorkspaceId(null);
            session.setUpdatedAt(LocalDateTime.now());
            sessionRepository.save(session);
            return Result.<Void>success();
        });
    }

    @Operation(summary = "查看可撤回点", description = "返回会话内的用户消息列表, 供前端在消息旁显示「撤回此轮」")
    @GetMapping("/checkpoints")
    public Mono<Result<List<SessionRewindService.Checkpoint>>> checkpoints(
            @Parameter(description = "会话 ID", required = true) @PathVariable String id) {
        return ownershipGuard.currentContext().map(ctx ->
                Result.success(rewindService.checkpoints(id, ctx.userId(), ctx.admin())));
    }

    @Operation(summary = "撤回",
            description = "撤回该消息及其之后的一切。scope=FILES 只回滚文件, scope=ALL 同时删除消息。"
                    + "撤回前被外部改过的文件会被跳过并单独报告, 不会覆盖你的改动")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SessionRewindService.Report.class)))
    @PostMapping("/rewind")
    public Mono<Result<SessionRewindService.Report>> rewind(
            @Parameter(description = "会话 ID", required = true) @PathVariable String id,
            @RequestBody Map<String, String> body) {
        return ownershipGuard.currentContext().map(ctx -> {
            requireSession(id, ctx.userId(), ctx.admin());
            String messageId = body.get("messageId");
            if (messageId == null || messageId.isBlank()) {
                throw new BusinessException(400, "请提供 messageId(要撤回的那条用户消息)");
            }
            SessionRewindService.Scope scope = "FILES".equalsIgnoreCase(body.get("scope"))
                    ? SessionRewindService.Scope.FILES
                    : SessionRewindService.Scope.ALL;
            return Result.success(rewindService.rewind(id, messageId, scope));
        });
    }

    @Operation(summary = "撤销单次文件操作",
            description = "把某一次工具调用造成的文件改动单独回滚, 不动对话与其它操作。"
                    + "opId 由工具结果携带, 前端从该次工具调用项上发起。文件在撤销前被外部改过则跳过并报告")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SessionRewindService.Report.class)))
    @PostMapping("/journal/{opId}/undo")
    public Mono<Result<SessionRewindService.Report>> undoOperation(
            @Parameter(description = "会话 ID", required = true) @PathVariable String id,
            @Parameter(description = "操作 ID, 形如 op_20261026_174912_331", required = true) @PathVariable String opId) {
        return ownershipGuard.currentContext().map(ctx -> {
            requireSession(id, ctx.userId(), ctx.admin());
            return Result.success(rewindService.undoOperation(id, opId, ctx.userId(), ctx.admin()));
        });
    }

    private SessionEntity requireSession(String id, String userId, boolean isAdmin) {
        SessionEntity session = sessionRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "会话不存在: " + id));
        if (!isAdmin && userId != null && !userId.equals(session.getUserId())) {
            throw new BusinessException(404, "会话不存在: " + id);
        }
        return session;
    }
}