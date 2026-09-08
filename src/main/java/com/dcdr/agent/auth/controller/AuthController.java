package com.dcdr.agent.auth.controller;

import com.dcdr.agent.auth.SecurityContext;
import com.dcdr.agent.auth.dto.AuthDtos;
import com.dcdr.agent.auth.service.AuthService;
import com.dcdr.agent.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;

/**
 * V2.2 认证接口 (F-22-001/002/003)。
 * <p>
 * 端点清单(与 V2.0 规划文档 §10.1 对齐):
 * <ul>
 *   <li>POST /api/auth/register   用户注册</li>
 *   <li>POST /api/auth/login      用户登录</li>
 *   <li>POST /api/auth/refresh    刷新 Token</li>
 *   <li>GET  /api/auth/me         获取当前用户(需登录)</li>
 *   <li>PUT  /api/auth/password   修改密码(需登录)</li>
 *   <li>POST /api/auth/logout     登出(前端丢弃 Token 即可, 后端无状态)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "用户认证", description = "注册、登录、Token 刷新、当前用户(V2.2)")
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "用户注册", description = "首位注册用户自动成为 admin")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = AuthDtos.AuthResponse.class)))
    @ApiResponse(responseCode = "409", description = "用户名/邮箱已被占用")
    @PostMapping("/register")
    public Result<AuthDtos.AuthResponse> register(@Valid @RequestBody AuthDtos.RegisterRequest req) {
        return Result.success(authService.register(req));
    }

    @Operation(summary = "用户登录", description = "返回 Access/Refresh Token; 连续失败 5 次锁定 30 分钟")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = AuthDtos.AuthResponse.class)))
    @ApiResponse(responseCode = "401", description = "用户名或密码错误")
    @ApiResponse(responseCode = "423", description = "账号锁定")
    @PostMapping("/login")
    public Result<AuthDtos.AuthResponse> login(@Valid @RequestBody AuthDtos.LoginRequest req) {
        return Result.success(authService.login(req));
    }

    @Operation(summary = "刷新 Token", description = "使用 Refresh Token 换取新的 Access/Refresh Token")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = AuthDtos.AuthResponse.class)))
    @ApiResponse(responseCode = "401", description = "Refresh Token 无效")
    @PostMapping("/refresh")
    public Result<AuthDtos.AuthResponse> refresh(@Valid @RequestBody AuthDtos.RefreshRequest req) {
        return Result.success(authService.refresh(req));
    }

    @Operation(summary = "获取当前登录用户", description = "需携带 Access Token")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = AuthDtos.UserInfo.class)))
    @GetMapping("/me")
    public Result<AuthDtos.UserInfo> me(ServerWebExchange exchange) {
        String userId = SecurityContext.getUserId(exchange);
        if (userId == null) {
            return Result.error(401, "未登录", "UNAUTHORIZED");
        }
        return Result.success(authService.getCurrentUser(userId));
    }

    @Operation(summary = "修改密码", description = "需登录; 修改后旧 Token 仍有效至过期, 如需立即使其失效前端需重新登录")
    @PutMapping("/password")
    public Result<Void> changePassword(@Valid @RequestBody AuthDtos.ChangePasswordRequest req,
                                       ServerWebExchange exchange) {
        String userId = SecurityContext.getUserId(exchange);
        if (userId == null) {
            return Result.error(401, "未登录", "UNAUTHORIZED");
        }
        authService.changePassword(userId, req);
        return Result.success();
    }

    @Operation(summary = "登出", description = "作废当前用户已签发 Access Token, 后续携带旧 Token 访问将被拒绝; 前端随后可丢弃本地 Token")
    @PostMapping("/logout")
    public Result<Void> logout(ServerWebExchange exchange) {
        String userId = SecurityContext.getUserId(exchange);
        if (userId != null) {
            authService.logout(userId);
        }
        return Result.success();
    }
}
