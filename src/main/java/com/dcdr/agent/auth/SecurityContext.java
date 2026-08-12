package com.dcdr.agent.auth;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.web.server.ServerWebExchange;

/**
 * V2.2 当前登录用户上下文。
 * <p>
 * 用户信息由 {@code SecurityContextWebFilter} 写入 {@link ServerWebExchange} 的 attributes,
 * 通过 {@link #fromExchange(ServerWebExchange)} 获取。
 * <p>
 * <b>注意</b>: 不再依赖 ThreadLocal, 因为 WebFlux 的 DispatcherHandler 会在线程池间切换,
 * 导致 ThreadLocal 在 Controller 方法执行时丢失。Exchange attributes 在整个请求生命周期中稳定。
 */
public class SecurityContext {

    /** exchange attribute key */
    public static final String EXCHANGE_KEY = "SECURITY_CURRENT_USER";

    /** 匿名用户 ID(认证关闭或未登录时的占位值, 兼容 V2.1 单用户场景) */
    public static final String ANONYMOUS_USER_ID = null;

    @Data
    @AllArgsConstructor
    public static class CurrentUser {
        /** 用户 ID */
        private String userId;
        /** 用户名 */
        private String username;
        /** 角色: admin / user / guest */
        private String role;
    }

    /** 写入 exchange attribute (由 SecurityContextWebFilter 调用) */
    public static void setToExchange(ServerWebExchange exchange, CurrentUser user) {
        exchange.getAttributes().put(EXCHANGE_KEY, user);
    }

    /** 从 exchange 获取当前用户 */
    public static CurrentUser fromExchange(ServerWebExchange exchange) {
        if (exchange == null) return null;
        return exchange.getAttribute(EXCHANGE_KEY);
    }

    /** 从 exchange 获取用户 ID */
    public static String getUserId(ServerWebExchange exchange) {
        CurrentUser u = fromExchange(exchange);
        return u == null ? null : u.getUserId();
    }

    /** 从 exchange 获取用户 ID, 未登录返回 defaultValue */
    public static String getUserIdOrDefault(ServerWebExchange exchange, String defaultValue) {
        String id = getUserId(exchange);
        return id == null ? defaultValue : id;
    }

    /** 从 exchange 判断是否管理员 */
    public static boolean isAdmin(ServerWebExchange exchange) {
        CurrentUser u = fromExchange(exchange);
        return u != null && "admin".equals(u.getRole());
    }
}
