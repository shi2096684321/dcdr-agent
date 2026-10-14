package com.dcdr.agent.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 智能体定义实体 (V2.0 新增, V2.2 自定义 Agent, V2.3 增加 temperature/avatar/is_template)
 */
@Data
@Entity
@EntityListeners(OwnershipFillListener.class)
@Schema(description = "智能体定义实体 (V2.0 新增, V2.2 自定义 Agent, V2.3 增加 temperature/avatar/is_template)")
@Table(name = "agent_definitions", indexes = {
        @Index(name = "idx_agent_user", columnList = "userId"),
        @Index(name = "idx_agent_template", columnList = "isTemplate"),
        @Index(name = "idx_agent_category", columnList = "categoryId")
})
public class AgentDefinitionEntity implements OwnedEntity {

    @Schema(description = "主键 ID (字符串型 UUID)")
    @Id
    @Column(length = 64)
    private String id;

    @Schema(description = "所属用户 ID")
    @Column(length = 64)
    private String userId;

    @Schema(description = "智能体名称")
    @Column(nullable = false)
    private String name;

    @Schema(description = "智能体描述")
    @Column(columnDefinition = "TEXT")
    private String description;

    @Schema(description = "头像 URL (V2.3)")
    @Column(length = 512)
    private String avatar;

    // ===== V3.1 专家元数据 (模板市场 → 专家市场) =====
    // 这四列的作用是"让专家可被检索与推荐": 原先市场只有名称/描述, 只能按时间与克隆数排序,
    // 既无法按领域分类, 也没有"该怎么开口问"的引导。

    @Schema(description = "职业头衔 (专家卡片标题, 如 资深法务)")
    @Column(length = 128)
    private String profession;

    @Schema(description = "行业分类 (市场分类筛选)")
    @Column(length = 64)
    private String categoryId;

    @Schema(description = "擅长领域标签, JSON 数组字符串")
    @Column(columnDefinition = "TEXT")
    private String tags;

    @Schema(description = "推荐问法, JSON 数组字符串 (首条兼作开场引导)")
    @Column(columnDefinition = "TEXT")
    private String quickPrompts;

    @Schema(description = "系统提示词 (定义智能体行为)")
    @Column(columnDefinition = "TEXT")
    private String systemPrompt;

    @Schema(description = "使用的模型名称")
    @Column(length = 128)
    private String model;

    @Schema(description = "启用的工具列表, JSON 字符串")
    @Column(columnDefinition = "TEXT")
    private String tools;

    @Schema(description = "关联的知识库 ID 列表, JSON 字符串")
    @Column(columnDefinition = "TEXT")
    private String kbIds;

    @Schema(description = "温度参数 (V2.3, 0~2)")
    @Column(precision = 3, scale = 2)
    private BigDecimal temperature;

    @Schema(description = "是否公开 (公开可被其他用户使用)", defaultValue = "false")
    @Column(nullable = false)
    private boolean isPublic = false;

    @Schema(description = "是否为预设模板 (V2.3, 模板对所有用户可见且不可删除)", defaultValue = "false")
    @Column(nullable = false)
    private boolean isTemplate = false;

    @Schema(description = "创建时间")
    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Schema(description = "最后更新时间")
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
