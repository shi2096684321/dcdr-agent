package com.dcdr.agent.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租户实体 (V3.0-rc F-36 多租户 SaaS 化)。
 * <p>
 * 租户是资源配额的载体: 用户通过 {@code users.tenant_id} 归属租户,
 * 租户级限额 (用户数 / Agent 数 / 工作流数 / 月 token) 在创建资源时由
 * {@code TenantService.checkResourceQuota} 拦截。
 * tenantId 为 null 的用户是平台直属用户, 不参与租户配额。
 */
@Data
@Entity
@Schema(description = "租户实体 (V3.0-rc F-36)")
@Table(name = "tenants")
public class TenantEntity {

    @Schema(description = "主键 ID (tnt_ 前缀)")
    @Id
    @Column(length = 64)
    private String id;

    @Schema(description = "租户名称")
    @Column(nullable = false)
    private String name;

    @Schema(description = "套餐: free / pro / enterprise")
    @Column(nullable = false, length = 32)
    private String plan = "free";

    @Schema(description = "用户数上限")
    @Column(name = "max_users", nullable = false)
    private int maxUsers = 5;

    @Schema(description = "Agent 数上限")
    @Column(name = "max_agents", nullable = false)
    private int maxAgents = 10;

    @Schema(description = "工作流数上限")
    @Column(name = "max_workflows", nullable = false)
    private int maxWorkflows = 20;

    @Schema(description = "月 token 上限")
    @Column(name = "max_tokens_month", nullable = false)
    private long maxTokensMonth = 1_000_000L;

    @Schema(description = "状态: active / suspended")
    @Column(nullable = false, length = 16)
    private String status = "active";

    @Schema(description = "创建时间")
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
