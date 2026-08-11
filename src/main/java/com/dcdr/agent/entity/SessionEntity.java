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
@Schema(description = "会话实体 (对话模式)")
@Table(name = "sessions")
public class SessionEntity {

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

    @Schema(description = "系统提示词 (覆盖默认行为)")
    @Column(columnDefinition = "TEXT")
    private String systemPrompt;

    @Schema(description = "所属用户 ID (V2.0 新增, V2.2 用户认证隔离使用; 暂为可空以兼容存量数据)")
    @Column(length = 64)
    private String userId;

    @Schema(description = "创建时间")
    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Schema(description = "最后更新时间")
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
