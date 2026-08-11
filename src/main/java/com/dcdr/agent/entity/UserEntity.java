package com.dcdr.agent.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体 (V2.0 新增, V2.2 认证/隔离使用)
 */
@Data
@Entity
@Table(name = "users", indexes = {
        @Index(name = "idx_users_username", columnList = "username", unique = true)
})
public class UserEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(nullable = false)
    private String passwordHash;

    @Column(length = 255)
    private String email;

    /** 角色: user / admin */
    @Column(nullable = false, length = 32)
    private String role = "user";

    /** 状态: active / disabled */
    @Column(nullable = false, length = 16)
    private String status = "active";

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
