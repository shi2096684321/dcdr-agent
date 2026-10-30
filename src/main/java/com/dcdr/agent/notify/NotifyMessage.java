package com.dcdr.agent.notify;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 待推送的通知内容 (渠道无关)。
 * <p>
 * 刻意<b>不</b>引用 {@code ScheduledTaskEntity} —— 推送能力后续还要给工作流、告警复用,
 * 绑定具体实体会让 notify 包反向依赖 schedule 包, 变成循环耦合。
 */
@Data
@Builder
public class NotifyMessage {

    /** 任务标识 (节流按它归并, 必填) */
    private String taskId;

    /** 任务名称 (展示用) */
    private String taskName;

    /** 执行状态: success / failed */
    private String status;

    /** Agent 输出正文 (可能很长, 由渠道实现按字节截断) */
    private String content;

    /** 失败原因 (status=failed 时有值) */
    private String error;

    /** 本次执行耗时(ms) */
    private long durationMs;

    /** 执行完成时间 */
    private LocalDateTime runAt;

    /** 任务级渠道覆盖; 为空时用 {@code NotifyProperties#channel} */
    private String channel;

    /**
     * 完整结果的访问地址（免登录详情页）。
     * <p>为空时模板里的 {@code {{runLink}}} 渲染成空 —— 通常是没配
     * {@code agent.notify.share-base-url}，此时只推概览。
     */
    private String runUrl;

    public boolean isFailed() {
        return "failed".equalsIgnoreCase(status);
    }
}
