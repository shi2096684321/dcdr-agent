package com.dcdr.agent.auth.ownership;

/**
 * 一次请求的鉴权上下文 (V3.1 F-41 统一归属框架)。
 * <p>
 * 由 {@link OwnershipGuard#context} 从 {@code SecurityContext}(exchange attributes) 与
 * {@code AuthProperties} 组装, 供所有 {@link ResourceOwnershipProvider#canAccess} 共用,
 * 使「admin 放行」「匿名兼容」等横切口径只实现一次。
 *
 * @param userId      当前登录用户 ID; {@code null} 表示未登录/匿名
 * @param admin       是否为管理员角色
 * @param authEnabled 认证开关({@code agent.auth.enabled}); 关闭时为 V2.1 单用户兼容模式
 */
public record AuthContext(String userId, boolean admin, boolean authEnabled) {

    /**
     * 匿名请求是否放行。
     * <p>
     * <b>策略(V3.1 起)</b>: 仅在认证整体关闭(V2.1 单用户部署兼容)时放行;
     * 认证开启却拿不到 userId 属异常状态(白名单路径或令牌解析异常), 一律拒绝,
     * 避免「误关认证 → 数据失去隔离」这一类配置事故。
     */
    public boolean anonymousAllowed() {
        return !authEnabled;
    }
}
