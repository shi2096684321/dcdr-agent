package com.dcdr.agent.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户级推送配置 (V3.4)：每个用户自己维护通知渠道的 Webhook。
 * <p>
 * 为什么需要它：{@code agent.notify.*} 是<b>部署级</b>配置，只能由运维通过环境变量注入 ——
 * 一套部署下所有用户的定时任务结果都推到同一个群，既没有隔离，也没法各自收自己的任务。
 * <p>
 * 安全约束（与 {@code UserModelConfigEntity} 同一套口径）：
 * <ul>
 *   <li>Webhook 地址里带机器人 key/token，属凭据：落库前 AES-GCM 加密，对外只返回掩码；</li>
 *   <li>地址由用户输入并被服务端发起请求，必须做 SSRF 防护：仅允许 https + 官方域名白名单；</li>
 *   <li>按 {@code userId + channel} 唯一，天然按用户隔离，无跨用户读取路径。</li>
 * </ul>
 */
@Data
@Entity
@Schema(description = "用户级推送配置 (V3.4)")
@Table(name = "user_notify_configs", uniqueConstraints = {
        @UniqueConstraint(name = "uk_unc_user_channel", columnNames = {"user_id", "channel"})
}, indexes = {
        @Index(name = "idx_unc_user", columnList = "user_id")
})
public class UserNotifyConfigEntity {

    @Schema(description = "主键 ID")
    @Id
    @Column(length = 64)
    private String id;

    @Schema(description = "归属用户 ID")
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** 渠道 code, 见 {@code NotifyChannel#code()} */
    @Schema(description = "渠道 code", example = "wecom_bot")
    @Column(nullable = false, length = 32)
    private String channel;

    /** AES-GCM 密文；空表示该渠道未配置 Webhook（回退全局配置） */
    @Schema(description = "Webhook 地址 (AES-GCM 密文, 对外只回显掩码)")
    @Column(name = "webhook_url_encrypted", length = 4096)
    private String webhookUrlEncrypted;

    /** 钉钉加签密钥 (AES-GCM 密文)；其它渠道为空 */
    @Schema(description = "加签密钥 (AES-GCM 密文, 对外不回显)")
    @Column(name = "secret_encrypted", length = 4096)
    private String secretEncrypted;

    /** 钉钉自定义关键词（非凭据，明文存即可） */
    @Schema(description = "自定义关键词 (钉钉关键词模式用)")
    @Column(length = 128)
    private String keyword;

    @Schema(description = "是否启用该渠道配置", defaultValue = "true")
    @Column(nullable = false)
    private boolean enabled = true;

    @Schema(description = "创建时间")
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Schema(description = "最后更新时间")
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
