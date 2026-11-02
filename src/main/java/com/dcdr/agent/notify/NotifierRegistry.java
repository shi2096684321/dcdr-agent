package com.dcdr.agent.notify;

import com.dcdr.agent.service.UserNotifyConfigService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
    /** V3.3: 用户级推送配置 (按 userId + 渠道 取解密后的 Webhook) */
    private final UserNotifyConfigService userNotifyConfigService;

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
        if (message == null || message.getTaskId() == null) {
            return NotifyResult.skipped(null, "通知消息为空");
        }
        // 用户配置只查一次: 渠道选择与凭据必须来自同一份快照, 否则可能出现
        // "按渠道 A 选的路由, 拿到的却是渠道 B 的凭据"这种自相矛盾的组合
        Map<NotifyChannel, UserNotifyConfigService.Credential> mine = loadUserCredentials(message.getUserId());
        NotifyChannel channel = resolveChannel(message.getChannel(), mine);
        if (channel == null) {
            // 配错渠道名属于配置事故, 必须报出来, 不能静默退化成默认渠道
            return NotifyResult.failed(null, "无法识别的通知渠道: " + message.getChannel());
        }
        Notifier notifier = byChannel.get(channel);
        if (notifier == null) {
            return NotifyResult.failed(channel.code(), "渠道暂无实现: " + channel.code());
        }
        // V3.3: 用户自己配了 Webhook 就优先用它 —— 一套部署下不同用户的任务该进各自的群。
        // 用户配置视为对该用户的"授权": 即使部署级总开关关闭也放行, 否则个人配置永远不会生效。
        CredentialSource source = applyUserCredential(message, channel, mine);
        if (source == CredentialSource.DISABLED) {
            // 用户明明配过、只是把开关关了, 报"未配置"会让他反复重填 —— 必须说清真正原因
            return NotifyResult.failed(channel.code(),
                    "该渠道的个人推送已停用, 且部署未配置默认机器人");
        }
        if (!props.isEnabled() && source != CredentialSource.USER) {
            return NotifyResult.skipped(null,
                    "通知总开关未启用 (agent.notify.enabled=false), 且当前用户未配置个人推送渠道");
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

    /** 本次推送最终用的是谁的凭据 */
    private enum CredentialSource {
        /** 用用户自己的 Webhook */
        USER,
        /** 无个人配置, 回退部署级 agent.notify.* */
        FALLBACK,
        /** 用户配了但关掉了, 且部署也没有默认机器人 —— 明确失败, 不要报成"未配置" */
        DISABLED
    }

    private Map<NotifyChannel, UserNotifyConfigService.Credential> loadUserCredentials(String userId) {
        if (userId == null || userId.isBlank()) return Map.of();
        try {
            return userNotifyConfigService.credentials(userId);
        } catch (Exception e) {
            // 查库/解密失败只降级, 不能让推送整体崩掉
            log.warn("读取用户推送配置失败, 回退部署级配置 (user={}): {}", userId, e.getMessage());
            return Map.of();
        }
    }

    /**
     * 把用户自己的推送凭据填进消息 (渠道实现发现非空时优先使用)。
     * <p>区分 {@link CredentialSource#DISABLED} 的原因: 用户配了却关掉开关时,
     * 若笼统回退, 最终报错是"未配置 Webhook", 会把人引向完全错误的排查方向。
     */
    private CredentialSource applyUserCredential(NotifyMessage message, NotifyChannel channel,
                                                 Map<NotifyChannel, UserNotifyConfigService.Credential> mine) {
        UserNotifyConfigService.Credential cred = mine.get(channel);
        if (cred == null || !cred.configured()) return CredentialSource.FALLBACK;
        if (!cred.enabled()) {
            return globalConfigured(channel) ? CredentialSource.FALLBACK : CredentialSource.DISABLED;
        }
        message.setWebhookUrl(cred.webhookUrl());
        message.setSecret(cred.secret());
        if (cred.keyword() != null && !cred.keyword().isBlank()) {
            message.setKeyword(cred.keyword());
        }
        return CredentialSource.USER;
    }

    /**
     * 渠道优先级: 任务指定 → 用户已配置的渠道 → 部署默认。
     * <p>中间这一步很关键: 用户只配了钉钉、部署默认却是企业微信时, 若不看用户配置,
     * 每次推送都会打到"未配置企业微信"上, 而用户明明已经配好了钉钉。
     * 无法识别返回 {@code null}。
     */
    private NotifyChannel resolveChannel(String taskChannel,
                                         Map<NotifyChannel, UserNotifyConfigService.Credential> mine) {
        if (taskChannel != null && !taskChannel.isBlank()) {
            return NotifyChannel.fromCode(taskChannel);
        }
        List<NotifyChannel> usable = new ArrayList<>();
        for (Map.Entry<NotifyChannel, UserNotifyConfigService.Credential> e : mine.entrySet()) {
            if (e.getValue().configured() && e.getValue().enabled()) usable.add(e.getKey());
        }
        if (usable.size() == 1) return usable.get(0);
        if (!usable.isEmpty()) {
            NotifyChannel dft = props.getChannel();
            return usable.contains(dft) ? dft : usable.get(0);
        }
        return props.getChannel();
    }

    /** 部署级是否配了该渠道的机器人地址 */
    private boolean globalConfigured(NotifyChannel channel) {
        return switch (channel) {
            case WECOM_BOT -> hasText(props.getWecom().getWebhookUrl());
            case DINGTALK_BOT -> hasText(props.getDingtalk().getWebhookUrl());
        };
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
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
