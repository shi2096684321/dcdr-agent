package com.dcdr.agent.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 长期记忆实体 (V3.1)。
 * <p>
 * 存放跨会话沉淀的<b>结论性信息</b> (用户画像/偏好/纠正/项目/资源指引), 与会话内的短期消息记忆
 * ({@code messages} 表) 分开存储 —— 两者生命周期与消费方式完全不同, 混在一张表会让
 * "会话历史"与"关于用户的知识"互相污染。
 * <p>
 * 作用域见 {@code MemoryScope}: {@code agentId} 为空即用户级记忆。
 * <p>
 * 当前版本 (P0) 不含向量列: 召回走「常驻 + 条件触发」。待单用户条目规模突破阈值后,
 * 再通过增量脚本补 {@code embedding} 列与向量召回 (P2), 届时无需改动本实体的其余字段。
 */
@Data
@Entity
@EntityListeners(OwnershipFillListener.class)
@Schema(description = "长期记忆实体 (V3.1)")
@Table(name = "long_term_memories", indexes = {
        @Index(name = "idx_memory_user", columnList = "userId, scope, status"),
        @Index(name = "idx_memory_agent", columnList = "agentId"),
        @Index(name = "idx_memory_created", columnList = "userId, createdAt")
})
public class MemoryEntity implements OwnedEntity {

    @Schema(description = "主键 ID (字符串型 UUID)")
    @Id
    @Column(length = 64)
    private String id;

    @Schema(description = "归属用户 ID (主干, 必填)")
    @Column(length = 64)
    private String userId;

    @Schema(description = "所属 Agent ID; 为空表示用户级通用记忆")
    @Column(length = 64)
    private String agentId;

    /** 作用域: USER / AGENT, 取值见 {@code MemoryScope} */
    @Schema(description = "作用域: USER / AGENT")
    @Column(length = 16, nullable = false)
    private String scope;

    /** 类型: PROFILE / PREFERENCE / FEEDBACK / PROJECT / REFERENCE, 取值见 {@code MemoryType} */
    @Schema(description = "记忆类型")
    @Column(length = 24, nullable = false)
    private String type;

    @Schema(description = "条件触发键: 命中当轮提问关键词时才注入 (常驻类型可空)")
    @Column(length = 64)
    private String topic;

    @Schema(description = "记忆内容 (结论性信息, 非原始日志)")
    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Schema(description = "标签, 逗号分隔")
    @Column(length = 512)
    private String tags;

    @Schema(description = "重要度 0.00~1.00, 决定注入优先级")
    @Column(precision = 3, scale = 2, nullable = false)
    private BigDecimal importance = new BigDecimal("0.50");

    @Schema(description = "是否常驻注入 (每轮必带)")
    @Column(nullable = false)
    private boolean pinned = false;

    @Schema(description = "来源会话 ID (溯源)")
    @Column(length = 64)
    private String sourceSessionId;

    @Schema(description = "来源消息序号 (溯源)")
    private Integer sourceMsgSeq;

    @Schema(description = "被召回次数 (淘汰依据)")
    @Column(nullable = false)
    private int hitCount = 0;

    @Schema(description = "最近召回时间")
    private LocalDateTime lastHitAt;

    @Schema(description = "到期时间; 为空表示永久有效")
    private LocalDateTime expiresAt;

    /** 状态: ACTIVE / ARCHIVED / DELETED, 取值见 {@code MemoryStatus} */
    @Schema(description = "状态: ACTIVE / ARCHIVED / DELETED")
    @Column(length = 16, nullable = false)
    private String status = "ACTIVE";

    @Schema(description = "创建时间")
    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Schema(description = "最后更新时间")
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
