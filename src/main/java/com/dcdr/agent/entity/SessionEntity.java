package com.dcdr.agent.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话实体 (对话模式)
 */
@Data
@Entity
@EntityListeners(OwnershipFillListener.class)
@Schema(description = "会话实体 (对话模式)")
@Table(name = "sessions", indexes = {
        @Index(name = "idx_session_agent", columnList = "agentId")
})
public class SessionEntity implements OwnedEntity {

    @Schema(description = "主键 ID (字符串型 UUID)")
    @Id
    @Column(length = 64)
    private String id;

    @Schema(description = "会话标题")
    @Column(nullable = false)
    private String title;

    @Schema(description = "标题是否为自动默认标题（首个用户问题到达后会被替换为问题内容并置为 false）")
    @Column(nullable = false, columnDefinition = "boolean default true")
    private Boolean titleAuto = true;

    @Schema(description = "当前会话使用的模型名称")
    private String model;

    @Schema(description = "本会话使用的自定义 Agent ID (V3.1; 为空表示未指定/通用助手)。"
            + "用于 Agent 级长期记忆的归属, 以及历史消息的 Agent 归因")
    @Column(length = 64)
    private String agentId;

    @Schema(description = "系统提示词 (覆盖默认行为)")
    @Column(columnDefinition = "TEXT")
    private String systemPrompt;

    @Schema(description = "绑定的工作区 ID (V3.3 F-49; 为空表示本会话未授权任何目录)。"
            + "绑定在会话而非用户上: 同一用户不同会话可以操作不同目录, 避免权限隐式扩散")
    @Column(length = 64)
    private String workspaceId;

    @Schema(description = "所属用户 ID (V2.0 新增, V2.2 用户认证隔离使用; 暂为可空以兼容存量数据)")
    @Column(length = 64)
    private String userId;

    @Schema(description = "会话来源 (V3.3): user=用户自己开的对话; schedule=定时任务自动执行; workflow=工作流步骤自动执行。"
            + "对话列表默认只查 user, 自动执行产生的会话归到自动化下的「任务对话」")
    @Column(length = 32, nullable = false, columnDefinition = "varchar(32) default 'user'")
    private String source = "user";

    @Schema(description = "来源聚合 ID (V3.3): 定时任务 ID 或工作流 ID。"
            + "自动会话按它分组收敛保留数量 —— 单个任务跑几百次不该留下几百个会话")
    @Column(length = 64)
    private String sourceRef;

    @Schema(description = "创建时间")
    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Schema(description = "最后更新时间")
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
