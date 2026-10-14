package com.dcdr.agent.dto;

import com.dcdr.agent.agent.memory.longterm.AutoExtractChoice;
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

    /** 设置「AI 自动记忆」用户级开关的请求 */
    @Data
    @Schema(description = "自动记忆开关设置")
    public static class AutoExtractSettingRequest {
        /**
         * {@code true}/{@code false} = 用户明确表态; 传 {@code null} 或不传 = <b>恢复默认</b>,
         * 即不再表态、重新跟随部署配置。
         */
        @Schema(description = "是否允许 AI 自动抽取记忆; null = 恢复默认(跟随部署配置)")
        private Boolean enabled;
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
        /**
         * 写入来源: {@code MANUAL} / {@code REQUESTED}(对话中用户要求) / {@code AUTO}(AI 自动提炼)。
         * <p>区分它是为了回答用户最直接的两个疑问: "我让 AI 记住的那条到底记下来没有"、
         * "这条我没写过的记忆是哪来的"。界面只对 REQUESTED / AUTO 打标, MANUAL 不标(那是默认情形)。
         */
        @Schema(description = "写入来源: MANUAL / REQUESTED(对话中要求) / AUTO(自动抽取)")
        private String sourceKind;
        @Schema(description = "写入来源中文名")
        private String sourceKindLabel;
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
        /**
         * 对本用户而言, 「AI 自动记忆」最终是否生效 = 部署允许 且 用户已同意。
         * <p>
         * 之所以要让前端知道: 它一开, 记忆就可能<b>不再全是用户亲手写的</b>。
         * 用户有权知道"这条是系统自动记的", 否则会对着一条自己没写过的记忆困惑
         * (参考 CodeBuddy 的记忆文档, 它讲了自动识别却没说在哪查看和管理 —— 这里补上)。
         */
        @Schema(description = "对本用户是否已生效 (部署允许 且 用户同意)")
        private boolean autoExtractEnabled;

        /**
         * 部署方是否开启了这一能力 —— 决定开关<b>能不能点</b>。
         * 单独下发是为了让界面能说清"为什么开不了": 是部署没开, 还是你自己没开。
         */
        @Schema(description = "部署方是否开启该能力 (false 时开关禁用)")
        private boolean autoExtractAllowed;

        /**
         * 用户自己的选择: {@code on} / {@code off} / {@code default}(未表态, 跟随部署默认)。
         * <p>
         * 用字符串而不是可空 Boolean: 万一序列化配置过滤 null 字段, 界面就分不清
         * "未表态"与"字段没下发", 会把跟随默认误显示成用户已选的固定值。
         * <p>取值与默认值都取自 {@link AutoExtractChoice}(唯一定义处), 这里只负责序列化。
         */
        @Schema(description = "用户选择: on / off / default(未表态)")
        private String autoExtractChoice = AutoExtractChoice.DEFAULT.value();

        /** {@link #autoExtractChoice} 的中文名, 由后端下发 —— 界面不再自己维护"取值 → 人话"的映射 */
        @Schema(description = "用户选择的中文名")
        private String autoExtractChoiceLabel = AutoExtractChoice.DEFAULT.label();
    }
}
