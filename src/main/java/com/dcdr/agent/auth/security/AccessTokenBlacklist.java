package com.dcdr.agent.auth.security;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * V2.2 F-40.9 安全加固: Access Token 黑名单(登出 / 换发即失效)。
 * <p>
 * 无状态 JWT 本身无法在签发后撤回, 本组件以"按用户记录的作废时刻"实现撤回语义:
 * <ul>
 *   <li>签发 Access Token 时写入毫秒级签发时刻 {@code iatMs}(见 JwtUtil);</li>
 *   <li>{@link #revokeAll(userId)} 记录该用户的作废边界 = 当前时刻(登出 / 换发时调用);</li>
 *   <li>校验时(见 {@link SecurityContextWebFilter})若 token 的 {@code iatMs} 不晚于边界,
 *       则认为该 Access Token 已被作废, 拒绝访问。</li>
 * </ul>
 * 效果:
 * <ul>
 *   <li>登出 → 该用户此前签发的一切 Access Token 立即失效(需重新登录);</li>
 *   <li>用 Refresh Token 换发 → 刷新前签发的 Access Token 立即失效(防止旧令牌继续被使用)。</li>
 * </ul>
 * <p>
 * 进程内实现(单实例够用); 多实例部署时需迁移至 Redis 共享(见 docs/13-部署与高可用.md)。
 * 仅保留用户->边界映射, 天然随用户量受限, 无需主动清理; token 自然按 access-ttl 过期。
 */
@Component
public class AccessTokenBlacklist {

    /** userId -> 作废边界(epoch 毫秒); 该时刻及更早签发的 Access Token 一律失效 */
    private final ConcurrentMap<String, Long> revokedSince = new ConcurrentHashMap<>();

    /** 作废该用户此前签发的所有 Access Token */
    public void revokeAll(String userId) {
        if (userId == null || userId.isBlank()) return;
        revokedSince.put(userId, System.currentTimeMillis());
    }

    /**
     * 判断某 Access Token(userId + 签发时刻 iatMs)是否已被作废。
     *
     * @param iatMs token 的毫秒签发时刻(JWT 自定义 claim {@code iatMs}, null 视为允许)
     */
    public boolean isRevoked(String userId, Long iatMs) {
        if (userId == null || iatMs == null) return false;
        Long since = revokedSince.get(userId);
        return since != null && iatMs <= since;
    }
}