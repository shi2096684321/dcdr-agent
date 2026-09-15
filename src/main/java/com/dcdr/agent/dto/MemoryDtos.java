package com.dcdr.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 长期记忆 DTO (V3.1)。
 * <p>
 * 记忆对用户必须可见、可编辑、可删除 —— 这是隐私合规的底线, 因此 DTO 覆盖完整的
 * 读改删字段, 而非只暴露内部结构。
 */
public class MemoryDtos {

    private MemoryDtos() {}

    /** 新增/更新记忆请求 (更新时仅非空字段生效) */
    @Data
    @Schema(description = "记忆保存请求")
    public static class SaveRequest {

        @Schema(description = "记忆内容 (结论性信息, 非原始日志)", requiredMode = Schema.RequiredMode.REQUIRED)
        private String content;

        @Schema(description = "记忆类型: PROFILE / PREFERENCE / FEEDBACK / PROJECT / REFERENCE",
                allowableValues = {"PROFILE", "PREFERENCE", "FEEDBACK", "PROJECT", "REFERENCE"})
        private String type;

        @Schema(description = "作用域: USER=跨 Agent 通用 / AGENT=该 Agent 专属 (默认 USER)",
                allowableValues = {"USER", "AGENT"})
        private String scope;

        @Schema(description = "所属 Agent ID (scope=AGENT 时必填)")
        private String agentId;

        @Schema(description = "条件触发键: 命中当轮提问时才注入 (常驻类型可留空)")
        private String topic;

        @Schema(description = "标签列表")
        private List<String> tags;

        @Schema(description = "重要度 0.0~1.0 (默认 0.5, 越高越优先注入)")
        private Double importance;

        @Schema(description = "是否常驻注入 (每轮必带, 默认 false)")
        private Boolean pinned;

        @Schema(description = "到期时间 (可选, 为空表示永久有效)")
        private LocalDateTime expiresAt;

        @Schema(description = "来源会话 ID (可选, 用于溯源)")
        private String sourceSessionId;

        @Schema(description = "来源消息序号 (可选, 用于溯源)")
        private Integer sourceMsgSeq;
    }

    /** 记忆视图 */
    @Data
    @Schema(description = "长期记忆")
    public static class MemoryView {
        private String id;
        @Schema(description = "作用域: USER / AGENT")
        private String scope;
        @Schema(description = "作用域中文名")
        private String scopeLabel;
        @Schema(description = "所属 Agent ID (用户级记忆为空)")
        private String agentId;
        @Schema(description = "所属 Agent 名称 (冗余展示用, Agent 已删除时为空)")
        private String agentName;
        private String type;
        @Schema(description = "类型中文名")
        private String typeLabel;
        private String topic;
        private String content;
        private List<String> tags;
        private double importance;
        private boolean pinned;
        @Schema(description = "状态: ACTIVE / ARCHIVED / DELETED")
        private String status;
        private String statusLabel;
        private String sourceSessionId;
        private Integer sourceMsgSeq;
        private int hitCount;
        private LocalDateTime lastHitAt;
        private LocalDateTime expiresAt;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    /** 记忆概览: 供记忆管理页展示规模与开关状态 */
    @Data
    @Schema(description = "长期记忆概览")
    public static class MemorySummary {
        @Schema(description = "活跃记忆总数")
        private long active;
        @Schema(description = "用户级记忆数 (跨 Agent 通用)")
        private long userLevel;
        @Schema(description = "Agent 级记忆数")
        private long agentLevel;
        @Schema(description = "召回策略说明 (当前版本无向量检索)")
        private String retrieval;
    }
}
