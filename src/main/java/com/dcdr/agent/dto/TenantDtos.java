package com.dcdr.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * V3.0-rc F-36 多租户 DTO。
 */
public class TenantDtos {

    @Data
    @Schema(description = "创建/更新租户请求")
    public static class SaveTenantRequest {
        @Schema(description = "租户名称")
        private String name;
        @Schema(description = "套餐: free / pro / enterprise")
        private String plan;
        @Schema(description = "用户数上限")
        private Integer maxUsers;
        @Schema(description = "Agent 数上限")
        private Integer maxAgents;
        @Schema(description = "工作流数上限")
        private Integer maxWorkflows;
        @Schema(description = "月 token 上限")
        private Long maxTokensMonth;
        @Schema(description = "状态: active / suspended")
        private String status;
    }

    @Data
    @Schema(description = "租户视图")
    public static class TenantView {
        private String id;
        private String name;
        private String plan;
        private Integer maxUsers;
        private Integer maxAgents;
        private Integer maxWorkflows;
        private Long maxTokensMonth;
        private String status;
        private LocalDateTime createdAt;
    }

    @Data
    @Schema(description = "租户资源使用情况")
    public static class TenantUsage {
        private String tenantId;
        private String tenantName;
        @Schema(description = "当前用户数 / 上限")
        private Integer userCount;
        private Integer maxUsers;
        @Schema(description = "当前 Agent 数 / 上限")
        private Integer agentCount;
        private Integer maxAgents;
        @Schema(description = "当前工作流数 / 上限")
        private Integer workflowCount;
        private Integer maxWorkflows;
        @Schema(description = "本月已用 token / 上限")
        private Long monthTokens;
        private Long maxTokensMonth;
        @Schema(description = "本月 token 使用率 (0~1, 无限额或无用量时 null)")
        private Double tokenUsageRate;
    }

    @Data
    @Schema(description = "用户归属租户请求")
    public static class AssignUserRequest {
        @Schema(description = "用户 ID")
        private String userId;
        @Schema(description = "目标租户 ID (null = 移出租户, 变为平台直属用户)")
        private String tenantId;
    }
}
