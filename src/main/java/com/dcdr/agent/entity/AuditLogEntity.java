package com.dcdr.agent.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计日志实体 (V2.0 新增, V2.2 安全审计)
 */
@Data
@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_user", columnList = "userId"),
        @Index(name = "idx_audit_time", columnList = "createdAt")
})
public class AuditLogEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(length = 64)
    private String userId;

    @Column(nullable = false, length = 128)
    private String action;

    @Column(length = 64)
    private String resourceType;

    @Column(length = 64)
    private String resourceId;

    @Column(columnDefinition = "TEXT")
    private String detail;

    @Column(length = 64)
    private String ip;

    @Column(nullable = false)
    private LocalDateTime createdAt;
}
