package com.dcdr.agent.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

/**
 * 会话实体 (对话模式)
 * <p>
 * {@code @DynamicUpdate}(V3.4): 这个实体上有多条<b>互不相干</b>的窄写路径 —— 每轮对话收尾时
 * {@code touch}(只改 updatedAt)、{@code saveContextUsage}(只改 contextUsage)、
 * 首条消息的 {@code autoTitleFromFirstMessage}(只改标题) —— 它们走各自独立的异步事务、几乎同时发生。
 * 默认的静态 UPDATE 会写全列, 于是"后提交的那次"会把别人刚写进去的列覆盖回自己读到的旧值
 * (典型症状: 刚看到的上下文明细刷新后消失、会话列表排序不更新)。只写变更列即可消除这类丢更新。
 */
@Data
@Entity
@DynamicUpdate
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

    /**
     * 最近一轮上下文占用快照(JSON, V3.4) —— 结构见 {@code com.dcdr.agent.dto.ContextUsage}。
     * <p>
     * <b>为什么落库而不是每次现算</b>: 真实的占用只有"组装完这一轮上下文"的那一刻才知道
     * (平台提示词 + 记忆召回 + RAG + 技能目录 + 本轮工具定义), 事后现算等于把整条准备链路再跑一遍,
     * 而其中若干步(记忆召回、路由)有副作用和成本。存快照的代价只有每轮一次 UPDATE,
     * 换来切会话、刷新页面后仍能看到"上次那一轮用了多少"。
     * <p>
     * 存的是<b>快照</b>不是实时值: 用户改了总上下文之后, 分母由读取侧按当前设置重算
     * (见 {@code ContextUsageService#fromJson}), 所以这里不必跟着改。
     */
    @Schema(description = "最近一轮上下文占用快照 (V3.4, JSON)")
    @Column(columnDefinition = "TEXT")
    private String contextUsage;

    /**
     * 历史对话的压缩摘要(V3.4) —— 由 {@code SummaryCompactionStrategy} 维护。
     * <p>
     * <b>为什么不另建表</b>: 一个会话只有"当前这一份摘要"(每次压缩整体替换), 天然是会话的一个属性;
     * 建历史表意味着要处理"哪一版生效"和清理, 换不来任何东西。
     * <p>
     * 原文不会被删: 摘要只影响"发给模型什么", 界面里的完整历史照旧从 messages 表读。
     * 这也意味着摘要是<b>可丢弃的缓存</b> —— 校验不过(见 {@link #contextSummarySeq})时整份作废即可。
     */
    @Schema(description = "历史对话压缩摘要 (V3.4)")
    @Column(columnDefinition = "TEXT")
    private String contextSummary;

    /**
     * 摘要已覆盖到的最后一条消息 seq(V3.4)。
     * <p>
     * 这是压缩的<b>边界证明</b>: 组装上下文时只丢掉 seq &lt;= 它的消息。用户撤回/删除历史后,
     * 这个 seq 对应的那条消息可能已经不存在了 —— 那就说明摘要描述的是已被删掉的内容,
     * 必须整份作废, 否则模型会"记得"用户明确撤回过的事。
     */
    @Schema(description = "摘要覆盖到的最后一条消息 seq (V3.4)")
    @Column(name = "context_summary_seq")
    private Integer contextSummarySeq;

    @Schema(description = "创建时间")
    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Schema(description = "最后更新时间")
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
