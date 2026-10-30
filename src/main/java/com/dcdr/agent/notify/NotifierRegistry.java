package com.dcdr.agent.notify;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通知分发器: 选择渠道、执行节流、调用具体实现。
 * <p>
 * <b>节流为什么必须有</b>: 定时任务可以配成每分钟跑一次, 而企业微信同一机器人硬限
 * 20 条/分钟, 超出直接 {@code errcode=45009} 拒绝。更实际的问题是刷屏 —— 微信被几十条
 * 重复告警淹没后, 用户会直接把整个功能关掉。
 * <p>
 * 节流只作用于<b>成功</b>消息; 失败消息永远放行。漏一条失败告警的代价远大于多收几条。
 * <p>
 * 状态是进程内的: 多实例下每个实例各算各的。这是可接受的 —— 定时任务有 {@code ClusterLock}
 * 保证同一时刻只有一个实例真正执行, 推送也只从执行的那个实例发出。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotifierRegistry {

    private final NotifyProperties props;
    private final List<Notifier> notifiers;

    /** 渠道 → 实现 */
    private final Map<NotifyChannel, Notifier> byChannel = new EnumMap<>(NotifyChannel.class);

    /** taskId → 上次成功推送时间戳 (仅成功消息参与节流) */
    private final Map<String, Long> lastSentAt = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        for (Notifier n : notifiers) {
            byChannel.put(n.channel(), n);
        }
        log.info("通知渠道注册完成: enabled={}, channels={}", props.isEnabled(), byChannel.keySet());
        if (props.isEnabled() && byChannel.isEmpty()) {
            log.warn("通知已启用但没有任何可用渠道实现, 推送将被跳过");
        }
    }

    /**
     * 分发一条通知 (受节流约束)。永不抛异常 —— 任何问题都以 {@link NotifyResult} 返回。
     */
    public NotifyResult dispatch(NotifyMessage message) {
        return doDispatch(message, true);
    }

    /**
     * 跳过节流直接发送。
     * <p>
     * 只给「测试推送」用: 用户刚配好 Webhook 时需要立即验证连通性, 若走节流,
     * 连点两次第二次会被静默跳过, 反而以为配置又坏了。也不写节流时间戳,
     * 避免一次测试把接下来窗口内的真实推送全部挤掉。
     */
    public NotifyResult dispatchNow(NotifyMessage message) {
        return doDispatch(message, false);
    }

    private NotifyResult doDispatch(NotifyMessage message, boolean throttled) {
        if (!props.isEnabled()) {
            return NotifyResult.skipped(null, "通知总开关未启用 (agent.notify.enabled=false)");
        }
        if (message == null || message.getTaskId() == null) {
            return NotifyResult.skipped(null, "通知消息为空");
        }
        NotifyChannel channel = resolveChannel(message.getChannel());
        if (channel == null) {
            // 配错渠道名属于配置事故, 必须报出来, 不能静默退化成默认渠道
            return NotifyResult.failed(null, "无法识别的通知渠道: " + message.getChannel());
        }
        Notifier notifier = byChannel.get(channel);
        if (notifier == null) {
            return NotifyResult.failed(channel.code(), "渠道暂无实现: " + channel.code());
        }

        boolean failedMessage = message.isFailed();
        long window = props.getThrottleWindowMs();
        long now = System.currentTimeMillis();
        if (throttled && !failedMessage && window > 0) {
            Long last = lastSentAt.get(message.getTaskId());
            if (last != null && now - last < window) {
                return NotifyResult.skipped(channel.code(),
                        "节流中: 同一任务 " + humanize(window) + " 内只推送一条");
            }
        }

        NotifyResult result = notifier.send(message);
        if (throttled && result.isSuccess() && !failedMessage) {
            lastSentAt.put(message.getTaskId(), now);
            evictIfNeeded(now, window);
        }
        return result;
    }

    /** 已注册的渠道 (供前端/文档展示) */
    public Set<NotifyChannel> availableChannels() {
        return Collections.unmodifiableSet(byChannel.keySet());
    }

    /** 取渠道实现；未注册返回 {@code null}（预览等只读场景需要它给出的长度上限） */
    public Notifier notifierOf(NotifyChannel channel) {
        return channel == null ? null : byChannel.get(channel);
    }

    /** 清空节流状态 (手动触发后想立刻收到推送时用; 目前未对外暴露) */
    public void resetThrottle(String taskId) {
        if (taskId == null) lastSentAt.clear();
        else lastSentAt.remove(taskId);
    }

    // ===== 内部 =====

    /** 任务级渠道优先, 留空回退全局默认; 无法识别返回 null */
    private NotifyChannel resolveChannel(String taskChannel) {
        if (taskChannel != null && !taskChannel.isBlank()) {
            return NotifyChannel.fromCode(taskChannel);
        }
        return props.getChannel();
    }

    /** 防止长期运行后 Map 无限增长 (任务删了但节流记录还在) */
    private void evictIfNeeded(long now, long window) {
        if (lastSentAt.size() <= 5000) return;
        long cutoff = now - Math.max(window, 3_600_000L);
        lastSentAt.entrySet().removeIf(e -> e.getValue() < cutoff);
    }

    private static String humanize(long ms) {
        if (ms < 60_000) return (ms / 1000) + " 秒";
        if (ms < 3_600_000) return (ms / 60_000) + " 分钟";
        return (ms / 3_600_000) + " 小时";
    }
}
