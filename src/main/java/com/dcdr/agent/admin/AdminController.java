package com.dcdr.agent.admin;

import com.dcdr.agent.common.Result;
import com.dcdr.agent.entity.AuditLogEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * V2.2 管理后台接口 (F-29)。
 * <p>
 * 路径级权限: {@code /api/admin/**} 需 {@code user:manage} 权限 (仅 admin 角色拥有),
 * 由 Spring Security {@code SecurityWebFilterChain.authorizeExchange} 强制校验。
 * <ul>
 *   <li>GET    /api/admin/users                 用户列表(分页)</li>
 *   <li>GET    /api/admin/users/{id}            用户详情</li>
 *   <li>PUT    /api/admin/users/{id}            更新用户角色/状态</li>
 *   <li>POST   /api/admin/users/{id}/reset-password   重置用户密码</li>
 *   <li>GET    /api/admin/stats                 系统统计概览</li>
 *   <li>GET    /api/admin/audit-logs            审计日志分页查询</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Tag(name = "管理后台", description = "用户管理、系统统计、审计日志查询(V2.2, 仅 admin)")
public class AdminController {

    private final AdminService adminService;

    // ===== 用户管理 =====

    @Operation(summary = "用户列表", description = "分页返回全部用户(脱敏, 不含密码)")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = AdminDtos.AdminUserView.class)))
    @GetMapping("/users")
    public Result<Page<AdminDtos.AdminUserView>> listUsers(
            @Parameter(description = "页码(0 起)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "每页条数") @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return Result.success(adminService.listUsers(pageable));
    }

    @Operation(summary = "用户详情")
    @GetMapping("/users/{id}")
    public Result<AdminDtos.AdminUserView> getUser(@PathVariable String id) {
        return Result.success(adminService.getUser(id));
    }

    @Operation(summary = "更新用户", description = "更新用户角色(admin/user/guest)与状态(active/disabled)")
    @PutMapping("/users/{id}")
    public Result<AdminDtos.AdminUserView> updateUser(@PathVariable String id,
                                                       @RequestBody AdminDtos.UpdateUserRequest req) {
        return Result.success(adminService.updateUser(id, req));
    }

    @Operation(summary = "重置用户密码", description = "管理员重置任意用户密码, 不需原密码")
    @PostMapping("/users/{id}/reset-password")
    public Result<Void> resetPassword(@PathVariable String id, @RequestBody AdminDtos.ResetPasswordRequest req) {
        adminService.resetPassword(id, req);
        return Result.success();
    }

    // ===== 系统统计 =====

    @Operation(summary = "系统统计概览", description = "用户数/会话数/知识库数/今日登录等聚合指标")
    @GetMapping("/stats")
    public Result<AdminDtos.SystemStats> getStats() {
        return Result.success(adminService.getStats());
    }

    // ===== 审计日志查询 =====

    @Operation(summary = "审计日志查询", description = "多条件分页查询: userId/action/时间范围")
    @GetMapping("/audit-logs")
    public Result<Page<AuditLogEntity>> queryAuditLogs(
            @Parameter(description = "用户 ID(可选)") @RequestParam(required = false) String userId,
            @Parameter(description = "操作动作(如 login/register)") @RequestParam(required = false) String action,
            @Parameter(description = "起始时间(yyyy-MM-dd'T'HH:mm:ss)") @RequestParam(required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @Parameter(description = "结束时间(yyyy-MM-dd'T'HH:mm:ss)") @RequestParam(required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return Result.success(adminService.queryAuditLogs(userId, action, from, to, pageable));
    }
}
