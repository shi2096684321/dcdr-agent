package com.dcdr.agent.service;

import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.common.util.ApiKeyCipher;
import com.dcdr.agent.dto.NotifyConfigDtos;
import com.dcdr.agent.entity.UserNotifyConfigEntity;
import com.dcdr.agent.notify.NotifyChannel;
import com.dcdr.agent.notify.NotifyProperties;
import com.dcdr.agent.notify.NotifyUrlGuard;
import com.dcdr.agent.repository.UserNotifyConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 用户级推送配置 (V3.3)。
 * <p>
 * <b>安全边界</b>（这段配置会把服务端变成"给任意地址发 POST"的代理，是最典型的 SSRF 入口）：
 * <ol>
 *   <li><b>域名白名单</b>：只允许各渠道官方域名，且必须是 https、不能带 userinfo、端口只能是 443。
 *       仅靠"看起来像 URL"是拦不住的 —— {@code https://qyapi.weixin.qq.com@evil.com/} 这类地址
 *       真正的 host 是 evil.com，必须用 {@link URI#getHost()} 判断；</li>
 *   <li><b>凭据加密落库</b>：Webhook 地址里带机器人 key/token，复用 {@link ApiKeyCipher} 的
 *       AES-GCM 加密，对外只返回掩码；</li>
 *   <li><b>按用户隔离</b>：所有读写都以 {@code userId + channel} 为条件，不存在按 ID 越权读取的路径。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserNotifyConfigService {

    private final UserNotifyConfigRepository repository;
    private final ApiKeyCipher cipher;
    private final NotifyProperties notifyProperties;

    /** 内部使用的解密后凭据 (仅在服务端流转, 不序列化给前端) */
    public record Credential(String webhookUrl, String secret, String keyword,
                             boolean configured, boolean enabled) {}

    // ===== 查询 =====

    /** 当前用户的全部渠道配置视图 (未配置的渠道也会返回, 便于前端展示"去配置") */
    @Transactional(readOnly = true)
    public List<NotifyConfigDtos.NotifyConfigView> list(String userId) {
        List<UserNotifyConfigEntity> mine = repository.findByUserId(userId);
        List<NotifyConfigDtos.NotifyConfigView> views = new ArrayList<>(NotifyChannel.values().length);
        for (NotifyChannel channel : NotifyChannel.values()) {
            UserNotifyConfigEntity e = mine.stream()
                    .filter(x -> channel.code().equals(x.getChannel()))
                    .findFirst().orElse(null);
            views.add(toView(channel, e));
        }
        return views;
    }

    /**
     * 一次取出该用户在各渠道上的解密凭据。
     * <p>
     * 为什么返回 {@code Map} 而不是"按渠道逐个查": 一次推送既要知道"发哪个渠道"、
     * 又要在同一渠道上取凭据, 分两次查就是两次 DB 往返; 这里代价固定为一次。
     * <p>
     * 解密失败（主密钥轮换过）的条目直接跳过, 按"未配置"处理 ——
     * 一条坏数据不该让整轮推送报错, 更要紧的是让用户看到"需要重新填"而不是一个假成功。
     */
    @Transactional(readOnly = true)
    public Map<NotifyChannel, Credential> credentials(String userId) {
        if (userId == null || userId.isBlank()) return Map.of();
        Map<NotifyChannel, Credential> map = new EnumMap<>(NotifyChannel.class);
        for (UserNotifyConfigEntity e : repository.findByUserId(userId)) {
            NotifyChannel channel = NotifyChannel.fromCode(e.getChannel());
            if (channel == null) continue;
            try {
                String url = decryptOrNull(e.getWebhookUrlEncrypted());
                String secret = decryptOrNull(e.getSecretEncrypted());
                map.put(channel, new Credential(url, secret, e.getKeyword(),
                        url != null && !url.isBlank(), e.isEnabled()));
            } catch (Exception ex) {
                log.warn("用户推送配置解密失败, 按未配置处理 (user={}, channel={}): {}",
                        userId, channel.code(), ex.getMessage());
            }
        }
        return map;
    }

    // ===== 写入 =====

    @Transactional
    public NotifyConfigDtos.NotifyConfigView save(String userId, NotifyConfigDtos.SaveNotifyConfigRequest req) {
        NotifyChannel channel = NotifyChannel.fromCode(req.getChannel());
        if (channel == null) {
            throw new BusinessException(400, "无法识别的通知渠道: " + req.getChannel());
        }
        String keyword = trimToNull(req.getKeyword());
        if (keyword != null && keyword.length() > 64) {
            throw new BusinessException(400, "关键词长度不能超过 64 字");
        }
        String rawUrl = trimToNull(req.getWebhookUrl());
        String rawSecret = trimToNull(req.getSecret());
        if (rawUrl != null) {
            String deny = NotifyUrlGuard.check(channel, rawUrl);
            if (deny != null) throw new BusinessException(400, deny);
        }
        if (rawSecret != null && rawSecret.length() > 256) {
            throw new BusinessException(400, "加签密钥长度异常");
        }

        LocalDateTime now = LocalDateTime.now();
        UserNotifyConfigEntity e = repository.findByUserIdAndChannel(userId, channel.code()).orElse(null);
        boolean created = false;
        if (e == null) {
            e = new UserNotifyConfigEntity();
            e.setId(UUID.randomUUID().toString());
            e.setUserId(userId);
            e.setChannel(channel.code());
            e.setCreatedAt(now);
            created = true;
        }

        // 留空 = 保留原值: 前端永远拿不到明文, 若不允许"留空保留"就变成每次保存都得重填一遍 key
        if (rawUrl != null) e.setWebhookUrlEncrypted(cipher.encrypt(rawUrl));
        if (!channel.supportsSign()) {
            // 企业微信没有加签/关键词概念, 避免脏数据误导排查
            e.setSecretEncrypted(null);
            e.setKeyword(null);
        } else {
            if (rawSecret != null) e.setSecretEncrypted(cipher.encrypt(rawSecret));
            if (req.getKeyword() != null) e.setKeyword(keyword);
        }
        if (req.getEnabled() != null) e.setEnabled(req.getEnabled());
        e.setUpdatedAt(now);
        repository.save(e);

        // 只记"哪个用户配了哪个渠道", 绝不把地址写进日志
        log.info("用户 {} {}推送配置: channel={}, enabled={}, hasWebhook={}",
                userId, created ? "新增" : "更新", channel.code(), e.isEnabled(),
                e.getWebhookUrlEncrypted() != null);
        return toView(channel, e);
    }

    @Transactional
    public void delete(String userId, String channelCode) {
        NotifyChannel channel = NotifyChannel.fromCode(channelCode);
        if (channel == null) {
            throw new BusinessException(400, "无法识别的通知渠道: " + channelCode);
        }
        repository.findByUserIdAndChannel(userId, channel.code())
                .ifPresent(repository::delete);
        log.info("用户 {} 删除推送配置: channel={}", userId, channel.code());
    }

    // ===== 映射 =====

    private NotifyConfigDtos.NotifyConfigView toView(NotifyChannel channel, UserNotifyConfigEntity e) {
        NotifyConfigDtos.NotifyConfigView v = new NotifyConfigDtos.NotifyConfigView();
        v.setChannel(channel.code());
        v.setChannelLabel(channel.label());
        v.setSupportsSecret(channel.supportsSign());
        v.setWebhookExample(channel.exampleWebhook());
        v.setConfigured(e != null && e.getWebhookUrlEncrypted() != null && !e.getWebhookUrlEncrypted().isBlank());
        // 未配置时返回 false: 开关默认打开会让用户误以为"推送已经启用", 实际什么都没配
        v.setEnabled(e != null && e.isEnabled());
        v.setHasSecret(e != null && e.getSecretEncrypted() != null && !e.getSecretEncrypted().isBlank());
        v.setKeyword(e == null ? null : e.getKeyword());
        v.setUpdatedAt(e == null ? null : e.getUpdatedAt());
        v.setGlobalFallbackAvailable(globalFallback(channel));
        if (v.isConfigured()) {
            try {
                v.setWebhookMasked(maskWebhook(decryptOrNull(e.getWebhookUrlEncrypted())));
            } catch (Exception ex) {
                // 主密钥轮换后旧密文解不开: 告诉用户"需要重新填写", 不要显示一个假的掩码
                v.setWebhookMasked("(密文已失效, 请重新填写)");
                v.setConfigured(false);
            }
        }
        return v;
    }

    /** 部署级 (env) 是否配了该渠道, 用于前端提示"当前走全局默认群" */
    private boolean globalFallback(NotifyChannel channel) {
        return switch (channel) {
            case WECOM_BOT -> hasText(notifyProperties.getWecom().getWebhookUrl());
            case DINGTALK_BOT -> hasText(notifyProperties.getDingtalk().getWebhookUrl());
        };
    }

    /**
     * 掩码化 Webhook：保留 scheme/host/path，查询参数只留末 4 位。
     * <p>例: {@code https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=****9ec0}
     */
    static String maskWebhook(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI uri = URI.create(url);
            String base = uri.getScheme() + "://" + uri.getHost()
                    + (uri.getPort() > 0 ? ":" + uri.getPort() : "") + uri.getPath();
            String query = uri.getRawQuery();
            if (query == null || query.isBlank()) return base;
            StringBuilder sb = new StringBuilder(base).append('?');
            String[] parts = query.split("&");
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                int eq = part.indexOf('=');
                if (i > 0) sb.append('&');
                if (eq < 0) {
                    sb.append(part);
                } else {
                    String value = part.substring(eq + 1);
                    sb.append(part, 0, eq).append("=****")
                            .append(value.length() > 4 ? value.substring(value.length() - 4) : "");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "****";
        }
    }

    private String decryptOrNull(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) return null;
        return cipher.decrypt(ciphertext);
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
