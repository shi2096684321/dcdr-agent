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

    @Schema(description = "创建时间")
    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Schema(description = "最后更新时间")
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
