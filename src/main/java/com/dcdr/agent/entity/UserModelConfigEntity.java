package com.dcdr.agent.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户自定义模型配置 (V3.1 新增)。
 * <p>
 * 允许用户在前端自行配置 OpenAI 兼容的 LLM (base-url + apiKey + model),
 * 实现"平台内置模型(yaml/env 管理员配) + 用户自定义模型(DB, 用户自己填)"双轨。
 * <p>
 * 安全约束:
 * <ul>
 *   <li>apiKey 存储为 AES-GCM 密文 (见 {@code ApiKeyCipher}), 不进日志、不回显前端;</li>
 *   <li>按 userId 隔离, 查询/修改均需携带归属用户。</li>
 * </ul>
 */
@Data
@Entity
@EntityListeners(OwnershipFillListener.class)
@Schema(description = "用户自定义模型配置 (V3.1 新增)")
@Table(name = "user_model_configs", indexes = {
        @Index(name = "idx_umc_user", columnList = "userId")
})
public class UserModelConfigEntity implements OwnedEntity {

    @Schema(description = "主键 ID (UUID)")
    @Id
    @Column(length = 64)
    private String id;

    @Schema(description = "归属用户 ID")
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** 展示名称(用户起的别名, 参与模型下拉展示) */
    @Schema(description = "展示名称", example = "我的 DeepSeek")
    @Column(length = 128)
    private String name;

    /** 厂商名: openai-compatible 等 (信息展示用) */
    @Schema(description = "厂商/类型标识", example = "openai-compatible")
    @Column(length = 64)
    private String provider;

    /** OpenAI 兼容端点, 框架在其后追加 /chat/completions */
    @Schema(description = "OpenAI 兼容端点 base-url", example = "https://api.openai.com/v1")
    @Column(nullable = false, length = 512)
    private String baseUrl;

    /** 模型名 */
    @Schema(description = "模型名", example = "gpt-4o")
    @Column(nullable = false, length = 128)
    private String model;

    /** AES-GCM 密文 */
    @Schema(description = "apiKey (AES-GCM 密文, 对外不回显)")
    @Column(name = "api_key_encrypted", nullable = false, length = 1024)
    private String apiKeyEncrypted;

    @Schema(description = "采样温度 (可空)")
    private Double temperature;

    @Schema(description = "最大输出 token (可空)")
    @Column(name = "max_tokens")
    private Integer maxTokens;

    @Schema(description = "是否启用 (禁用则路由时不可用)", defaultValue = "true")
    @Column(nullable = false)
    private boolean enabled = true;

    @Schema(description = "创建时间")
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Schema(description = "最后更新时间")
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}