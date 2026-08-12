package com.dcdr.agent.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * V2.2 管理后台相关 DTO 集中定义。
 */
public class AdminDtos {

    private AdminDtos() {}

    /** 管理后台用户视图(脱敏, 不含密码) */
    @Data
    @Schema(description = "管理后台用户视图")
    public static class AdminUserView {
        private String id;
        private String username;
        private String email;
        private String role;
        private String status;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    /** 管理员更新用户请求 */
    @Data
    @Schema(description = "管理员更新用户请求")
    public static class UpdateUserRequest {
        @Schema(description = "角色: admin / user / guest")
        private String role;
        @Schema(description = "状态: active / disabled")
        private String status;
        @Schema(description = "邮箱(可选)")
        private String email;
    }

    /** 系统统计概览 */
    @Data
    @Schema(description = "系统统计概览")
    public static class SystemStats {
        @Schema(description = "用户总数")
        private long userCount;
        @Schema(description = "启用用户数")
        private long activeUserCount;
        @Schema(description = "管理员数")
        private long adminCount;
        @Schema(description = "会话总数")
        private long sessionCount;
        @Schema(description = "知识库总数")
        private long knowledgeBaseCount;
        @Schema(description = "文档总数")
        private long documentCount;
        @Schema(description = "今日登录次数")
        private long todayLoginCount;
        @Schema(description = "今日对话次数(action=chat)")
        private long todayChatCount;
        @Schema(description = "审计日志总数")
        private long auditLogCount;
    }

    /** 管理员重置用户密码请求 */
    @Data
    @Schema(description = "管理员重置密码请求")
    public static class ResetPasswordRequest {
        @Schema(description = "新密码(8 位以上)", requiredMode = Schema.RequiredMode.REQUIRED)
        private String newPassword;
    }
}
