package com.dcdr.agent.notify;

import lombok.Builder;
import lombok.Data;
import lombok.ToString;

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
     * 消息所属用户 (V3.3)。非空时, {@link NotifierRegistry} 会按该用户自己的推送配置
     * 填充下面的凭据字段; 为空则只用部署级配置。
     */
    private String userId;

    /**
     * 以下三项是<b>服务端内部</b>凭据: 由 {@link NotifierRegistry} 在发送前从用户配置解密填入,
     * 非空时优先于部署级 {@code agent.notify.*}; 不得写日志、不得序列化给前端。
     * <p>
     * {@code @ToString.Exclude} 不是可选项: 本类是 {@code @Data}, 默认 {@code toString()}
     * 会把这三个字段原样打出来 —— 一句 {@code log.debug("{}", message)} 就能把用户的
     * 机器人 key 写进日志文件。"不得写日志"这条约定必须由代码保证, 而不是靠人记得。
     */
    @ToString.Exclude
    private String webhookUrl;
    /** 钉钉加签密钥 (用户级) */
    @ToString.Exclude
    private String secret;
    /** 钉钉自定义关键词 (用户级) */
    private String keyword;

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
