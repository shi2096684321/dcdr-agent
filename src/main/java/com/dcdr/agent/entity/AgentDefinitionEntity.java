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
@Schema(description = "智能体定义实体 (V2.0 新增, V2.2 自定义 Agent, V2.3 增加 temperature/avatar/is_template)")
@Table(name = "agent_definitions", indexes = {
        @Index(name = "idx_agent_user", columnList = "userId"),
        @Index(name = "idx_agent_template", columnList = "isTemplate")
})
public class AgentDefinitionEntity {

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
