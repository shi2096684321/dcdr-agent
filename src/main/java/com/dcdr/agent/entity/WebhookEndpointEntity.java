package com.dcdr.agent.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * V3.0 Webhook 端点实体 (F-31 触发层扩展, 文档 §5.3)。
 * <p>
 * 每条记录绑定一个工作流, 外部系统通过 {@code POST /api/webhooks/{token}} 触发该工作流。
 */
@Data
@Entity
@EntityListeners(OwnershipFillListener.class)
@Schema(description = "V3.0 Webhook 端点 (F-31)")
@Table(name = "webhook_endpoints", indexes = {
        @Index(name = "idx_webhook_user", columnList = "userId")
})
public class WebhookEndpointEntity implements OwnedEntity {

    @Schema(description = "主键 ID")
    @Id
    @Column(length = 64)
    private String id;

    @Schema(description = "所属用户 ID")
    @Column(length = 64)
    private String userId;

    @Schema(description = "绑定的工作流 ID")
    @Column(length = 64, nullable = false)
    private String workflowId;

    @Schema(description = "端点名称")
    @Column(length = 255)
    private String name;

    @Schema(description = "随机 token, 用于 URL 路径")
    @Column(length = 128, nullable = false, unique = true)
    private String token;

    @Schema(description = "可选 secret, 用于签名验证")
    @Column(length = 128)
    private String secret;

    @Schema(description = "是否启用")
    @Column(nullable = false)
    private boolean enabled = true;

    @Schema(description = "最后触发时间")
    private LocalDateTime lastTriggered;

    @Schema(description = "累计触发次数")
    @Column(nullable = false)
    private int triggerCount = 0;

    @Schema(description = "创建时间")
    @Column(nullable = false)
    private LocalDateTime createdAt;
}
