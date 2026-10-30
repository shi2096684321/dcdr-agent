package com.dcdr.agent.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 定时任务单次执行记录 (V3.3 F-50)。
 * <p>
 * <b>为什么要有它</b>: {@code scheduled_tasks} 上只保留 lastStatus / lastError，
 * 完整输出在执行结束后就丢了 —— 于是推送里"查看详情"的链接无处可指。
 * 抓取类任务（读几篇文章、汇总成长文）尤其需要：IM 里只放概览，全文落在这里，
 * 通过一个免登录的 share token 打开。
 * <p>
 * <b>为什么不复用消息表</b>: {@code runOnce} 执行完会立刻删除临时会话，
 * 消息随会话一起消失，且消息表没有"分享 token / 过期时间"这类语义。
 */
@Data
@Entity
@Schema(description = "定时任务单次执行记录")
@Table(name = "scheduled_task_runs", indexes = {
        @Index(name = "idx_run_task", columnList = "task_id"),
        @Index(name = "idx_run_token", columnList = "share_token", unique = true),
        @Index(name = "idx_run_created", columnList = "created_at")
})
public class ScheduledTaskRunEntity {

    @Schema(description = "主键 ID")
    @Id
    @Column(length = 64)
    private String id;

    @Schema(description = "所属定时任务 ID")
    @Column(name = "task_id", length = 64, nullable = false)
    private String taskId;

    @Schema(description = "任务所属用户（详情页归属判定用）")
    @Column(name = "user_id", length = 64)
    private String userId;

    @Schema(description = "执行状态: success / failed")
    @Column(length = 16)
    private String status;

    /** 完整输出。抓取类任务可能有几万字，用 TEXT 而非 VARCHAR */
    @Schema(description = "Agent 完整输出")
    @Column(columnDefinition = "TEXT")
    private String output;

    @Schema(description = "失败原因")
    @Column(columnDefinition = "TEXT")
    private String error;

    @Schema(description = "执行耗时(ms)")
    @Column(name = "duration_ms")
    private long durationMs;

    @Schema(description = "开始时间")
    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Schema(description = "结束时间")
    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    /**
     * 免登录访问凭证。
     * <p>随机 UUID 去掉横线（32 位十六进制），不可猜测；页面不需要登录态，
     * 因为手机上点开 IM 里的链接时通常没有 JWT。
     */
    @Schema(description = "分享 token（免登录访问详情页）")
    @Column(name = "share_token", length = 64, unique = true)
    private String shareToken;

    @Schema(description = "分享链接过期时间")
    @Column(name = "share_expires_at")
    private LocalDateTime shareExpiresAt;

    @Schema(description = "创建时间")
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
