package com.dcdr.agent.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * V2.2 认证相关 DTO 集合(集中放置, 减少文件数)。
 */
public class AuthDtos {

    private AuthDtos() {}

    /** 注册请求 */
    @Data
    @Schema(description = "用户注册请求")
    public static class RegisterRequest {
        @Schema(description = "用户名(3-64 字符, 字母数字下划线)", example = "alice", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "用户名不能为空")
        @Size(min = 3, max = 64, message = "用户名长度需在 3-64 字符之间")
        @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "用户名仅支持字母/数字/下划线")
        private String username;

        @Schema(description = "密码(8 位以上, 含大小写字母与数字)", example = "Passw0rd", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 64, message = "密码长度需在 8-64 字符之间")
        private String password;

        @Schema(description = "邮箱(可选)")
        private String email;
    }

    /** 登录请求 */
    @Data
    @Schema(description = "用户登录请求")
    public static class LoginRequest {
        @Schema(description = "用户名", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "用户名不能为空")
        private String username;

        @Schema(description = "密码", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "密码不能为空")
        private String password;
    }

    /** 刷新 Token 请求 */
    @Data
    @Schema(description = "刷新 Token 请求")
    public static class RefreshRequest {
        @Schema(description = "Refresh Token", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "refreshToken 不能为空")
        private String refreshToken;
    }

    /** 登录/注册响应 */
    @Data
    @Schema(description = "认证响应(含 Access/Refresh Token 与用户信息)")
    public static class AuthResponse {
        @Schema(description = "Access Token (24h), 用于 API 鉴权")
        private String accessToken;
        @Schema(description = "Refresh Token (7d), 用于续签")
        private String refreshToken;
        @Schema(description = "Token 类型", example = "Bearer")
        private String tokenType = "Bearer";
        @Schema(description = "Access Token 有效期(秒)")
        private long expiresIn;
        @Schema(description = "当前用户信息")
        private UserInfo user;
    }

    /** 用户信息(脱敏, 不含密码) */
    @Data
    @Schema(description = "用户信息(脱敏)")
    public static class UserInfo {
        private String id;
        private String username;
        private String email;
        private String role;
        private String status;
        private java.time.LocalDateTime createdAt;
    }

    /** 修改密码请求 */
    @Data
    @Schema(description = "修改密码请求")
    public static class ChangePasswordRequest {
        @Schema(description = "原密码", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        private String oldPassword;
        @Schema(description = "新密码(8 位以上)", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(min = 8, max = 64)
        private String newPassword;
    }
}
