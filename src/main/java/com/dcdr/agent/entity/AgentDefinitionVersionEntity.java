package com.dcdr.agent.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * V3.1 F-44.1 Agent 定义版本快照实体 (表 {@code agent_definition_versions})。
 * <p>
 * 与 {@link WorkflowVersionEntity} 同构, 均为「全量快照」而非增量补丁:
 * 每次创建 / 更新 / 回滚都写一条快照, {@code version} 在同一 Agent 内递增,
 * 因此任一版本都可独立还原、可两两做结构化 diff。
 * <p>
 * 字段与 {@link AgentDefinitionEntity} 的定义列一一对应(不含 userId/时间戳):
 * 只有保存了"当时的定义内容"才能支持 diff 与回滚。
 * <p>
 * <b>不实现 {@link OwnedEntity}</b>: 归属字段是 {@code createdBy}(操作者) 而非 {@code userId},
 * 与 {@code WorkflowVersionEntity} 保持一致 —— 版本是定义实体的子资源, 归属由父资源判定
 * (见 {@code AgentOwnershipProvider})。
 */
@Data
@Entity
@Schema(description = "V3.1 F-44.1 Agent 定义版本快照")
@Table(name = "agent_definition_versions", uniqueConstraints = {
        @UniqueConstraint(name = "uk_agentver_agent_version", columnNames = {"agentId", "version"})
}, indexes = {
        @Index(name = "idx_agentver_agent", columnList = "agentId")
})
public class AgentDefinitionVersionEntity {

    @Schema(description = "主键 ID")
    @Id
    @Column(length = 64)
    private String id;

    @Schema(description = "Agent ID")
    @Column(name = "agent_id", length = 64, nullable = false)
    private String agentId;

    @Schema(description = "版本号 (同一 Agent 内递增)")
    @Column(nullable = false)
    private int version;

    @Schema(description = "版本快照: 名称")
    @Column(nullable = false)
    private String name;

    @Schema(description = "版本快照: 描述")
    @Column(columnDefinition = "TEXT")
    private String description;

    @Schema(description = "版本快照: 头像 URL")
    @Column(length = 512)
    private String avatar;

    @Schema(description = "版本快照: 角色设定 (对应定义表的 system_prompt)")
    @Column(name = "system_prompt", columnDefinition = "TEXT")
    private String systemPrompt;

    @Schema(description = "版本快照: 模型名称")
    @Column(length = 128)
    private String model;

    @Schema(description = "版本快照: 工具列表, JSON 字符串")
    @Column(columnDefinition = "TEXT")
    private String tools;

    @Schema(description = "版本快照: 知识库 ID 列表, JSON 字符串")
    @Column(name = "kb_ids", columnDefinition = "TEXT")
    private String kbIds;

    @Schema(description = "版本快照: 温度参数")
    @Column(precision = 3, scale = 2)
    private BigDecimal temperature;

    @Schema(description = "版本快照: 是否公开")
    @Column(name = "is_public", nullable = false)
    private boolean isPublic = false;

    @Schema(description = "版本快照: 是否预设模板")
    @Column(name = "is_template", nullable = false)
    private boolean isTemplate = false;

    @Schema(description = "变更说明")
    @Column(name = "change_note", length = 255)
    private String changeNote;

    @Schema(description = "创建人 (快照时的操作者)")
    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Schema(description = "创建时间")
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
