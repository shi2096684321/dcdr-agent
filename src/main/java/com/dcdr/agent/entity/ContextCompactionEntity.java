package com.dcdr.agent.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 上下文压缩事件(V3.5) —— 一次"压没压 / 压了什么 / 为什么降级"的记录。
 *
 * <p><b>与 {@code sessions.context_summary} 的分工</b>: 那两列是<b>当前生效的摘要</b>(只有一版,
 * 覆盖即替换), 本表是<b>发生过的压缩事件</b>(只追加)。前者回答"模型现在记得什么",
 * 后者回答"它是什么时候、因为什么变成这样的" —— 用户报"会话突然忘事"时,
 * 需要的是后者的时间线, 而覆盖式的摘要字段天然留不下这个。
 *
 * <p><b>为什么不并进 {@code llm_call_logs}</b>: 降级、摘要作废、用户清除这些事件根本没有
 * 模型调用; 硬塞进去会让"调用链"页面出现没有 token、没有耗时的行, 也会污染按来源统计的指标。
 */
@Data
@Entity
@Schema(description = "上下文压缩事件(一次压缩/降级/作废一条)")
@Table(name = "context_compactions", indexes = {
        @Index(name = "idx_ctxcomp_session_time", columnList = "sessionId, createdAt"),
        @Index(name = "idx_ctxcomp_user_time", columnList = "userId, createdAt")
})
public class ContextCompactionEntity {

    @Schema(description = "主键 ID")
    @Id
    @Column(length = 64)
    private String id;

    /** 会话删除时置空(保留事件用于统计), 与 {@code llm_call_logs} 同一口径 */
    @Schema(description = "会话 ID; 会话删除后为 null")
    @Column(length = 64)
    private String sessionId;

    @Schema(description = "用户 ID")
    @Column(length = 64)
    private String userId;

    /** 取值见 {@code CompactionEvent}: compacted / degraded / invalidated / cleared / edited */
    @Schema(description = "事件类型: compacted / degraded / invalidated / cleared / edited")
    @Column(length = 24)
    private String eventType;

    /** 降级/作废/清除的一句话说明; 成功压缩时为空 */
    @Schema(description = "事件说明(降级原因 / 作废原因 / 清除与改写字数)")
    @Column(length = 512)
    private String reason;

    @Schema(description = "本轮触发阈值(token)")
    @Column
    private Integer triggerTokens;

    @Schema(description = "压缩前估算 token")
    @Column
    private Integer beforeTokens;

    @Schema(description = "压缩后估算 token(成功时才有)")
    @Column
    private Integer afterTokens;

    @Schema(description = "被压进摘要的消息条数")
    @Column
    private Integer summarizedCount;

    @Schema(description = "保留原文的消息条数")
    @Column
    private Integer keptCount;

    @Schema(description = "摘要字数")
    @Column
    private Integer summaryChars;

    @Schema(description = "摘要覆盖到的最后一条消息 seq(边界)")
    @Column
    private Integer boundarySeq;

    @Schema(description = "摘要使用的 provider 名")
    @Column(length = 64)
    private String model;

    @Schema(description = "摘要模型调用耗时(毫秒)")
    @Column
    private Long durationMs;

    @Schema(description = "创建时间")
    @Column(nullable = false)
    private LocalDateTime createdAt;
}
