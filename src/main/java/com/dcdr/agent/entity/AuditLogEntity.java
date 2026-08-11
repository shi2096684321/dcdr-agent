package com.dcdr.agent.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计日志实体 (V2.0 新增, V2.2 安全审计)
 */
@Data
@Entity
@Schema(description = "审计日志实体 (V2.0 新增, V2.2 安全审计)")
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_user", columnList = "userId"),
        @Index(name = "idx_audit_time", columnList = "createdAt")
})
public class AuditLogEntity {

    @Schema(description = "主键 ID (字符串型 UUID)")
    @Id
    @Column(length = 64)
    private String id;

    @Schema(description = "操作用户 ID")
    @Column(length = 64)
    private String userId;

    @Schema(description = "操作动作 (如 login/create/update/delete)")
    @Column(nullable = false, length = 128)
    private String action;

    @Schema(description = "资源类型 (如 session/knowledge_base)")
    @Column(length = 64)
    private String resourceType;

    @Schema(description = "资源 ID")
    @Column(length = 64)
    private String resourceId;

    @Schema(description = "操作详情 (JSON 字符串)")
    @Column(columnDefinition = "TEXT")
    private String detail;

    @Schema(description = "操作来源 IP 地址")
    @Column(length = 64)
    private String ip;

    @Schema(description = "操作时间")
    @Column(nullable = false)
    private LocalDateTime createdAt;
}
