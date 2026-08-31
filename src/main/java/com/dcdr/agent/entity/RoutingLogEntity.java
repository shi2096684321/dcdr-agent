package com.dcdr.agent.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * V3.0 智能路由记录实体 (F-30, 文档 §4.5)。
 * <p>
 * 每次 IntentRouter 判断后落库一条记录, 用于分析路由准确率与调优置信度阈值。
 */
@Data
@Entity
@Schema(description = "V3.0 智能路由记录 (F-30)")
@Table(name = "routing_logs", indexes = {
        @Index(name = "idx_routing_user", columnList = "userId"),
        @Index(name = "idx_routing_time", columnList = "createdAt")
})
public class RoutingLogEntity {

    @Schema(description = "主键 ID")
    @Id
    @Column(length = 64)
    private String id;

    @Schema(description = "用户 ID")
    @Column(length = 64)
    private String userId;

    @Schema(description = "会话 ID")
    @Column(length = 64)
    private String sessionId;

    @Schema(description = "用户原始消息")
    @Column(columnDefinition = "TEXT", nullable = false)
    private String userMessage;

    @Schema(description = "路由类型: workflow / agent / chat")
    @Column(nullable = false)
    private String routeType;

    @Schema(description = "命中的目标 ID (工作流/Agent ID, chat 时为空)")
    @Column(length = 64)
    private String targetId;

    @Schema(description = "置信度 0~1")
    @Column(precision = 3, scale = 2)
    private BigDecimal confidence;

    @Schema(description = "判断理由")
    @Column(columnDefinition = "TEXT")
    private String reason;

    @Schema(description = "路由 LLM 调用消耗的输入 token")
    @Column(nullable = false)
    private Integer inputTokens = 0;

    @Schema(description = "路由 LLM 调用消耗的输出 token")
    @Column(nullable = false)
    private Integer outputTokens = 0;

    @Schema(description = "路由 LLM 调用消耗的总 token")
    @Column(nullable = false)
    private Integer totalTokens = 0;

    @Schema(description = "创建时间")
    @Column(nullable = false)
    private LocalDateTime createdAt;
}
