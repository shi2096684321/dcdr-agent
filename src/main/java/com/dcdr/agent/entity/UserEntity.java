package com.dcdr.agent.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体 (V2.0 新增, V2.2 认证/隔离使用)
 */
@Data
@Entity
@Schema(description = "用户实体 (V2.0 新增, V2.2 认证/隔离使用)")
@Table(name = "users", indexes = {
        @Index(name = "idx_users_username", columnList = "username", unique = true)
})
public class UserEntity {

    @Schema(description = "主键 ID (字符串型 UUID)")
    @Id
    @Column(length = 64)
    private String id;

    @Schema(description = "用户名 (唯一)")
    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Schema(description = "密码哈希值 (BCrypt 等不可逆加密存储)")
    @Column(nullable = false)
    private String passwordHash;

    @Schema(description = "邮箱地址")
    @Column(length = 255)
    private String email;

    @Schema(description = "角色: user / admin", defaultValue = "user")
    @Column(nullable = false, length = 32)
    private String role = "user";

    @Schema(description = "状态: active / disabled", defaultValue = "active")
    @Column(nullable = false, length = 16)
    private String status = "active";

    /** V3.0-rc F-36: 归属租户 ID (null = 平台直属用户, 不参与租户配额) */
    @Schema(description = "归属租户 ID (V3.0-rc F-36, 可空)")
    @Column(name = "tenant_id", length = 64)
    private String tenantId;

    /**
     * 是否允许 AI 自动抽取长期记忆 (V3.1 P1) —— {@code null} 表示<b>用户未表态</b>,
     * 跟随部署配置 {@code agent.memory.auto-extract.user-default}。
     * <p>
     * <b>刻意用可空 Boolean 而不是 boolean</b>, 因为要区分"用户明确关闭"与"从没表过态":
     * 若用 boolean(默认 false), 部署方日后把默认值改为开启时, 这些从未表态的用户会被当成
     * "明确拒绝"而永远拿不到新默认 —— 用户的选择只该在用户真的选择过时才被记下来。
     * <p>
     * 语义分工: 配置项 {@code enabled} 是部署方的<b>能力闸门</b>(费用), 本列是用户的<b>同意</b>。
     * 两者都开才真正生效 (见 {@code AutoExtractPreference#active})。
     */
    @Schema(description = "是否允许 AI 自动抽取记忆 (null=未表态, 跟随部署默认)")
    @Column(name = "auto_memory_enabled")
    private Boolean autoMemoryEnabled;

    @Schema(description = "创建时间")
    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Schema(description = "最后更新时间")
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
