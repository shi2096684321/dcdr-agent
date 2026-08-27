package com.dcdr.agent.config;

import com.dcdr.agent.auth.SecurityContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V3.0-rc F-40.3 API 限流 (文档 §14.3)。
 * <p>
 * 固定窗口计数, 用户 + IP 双维度:
 * <ul>
 *   <li>登录用户: {@code user:<userId>}, 默认 60 req/min (agent.v3.rate-limit.user-requests-per-minute)</li>
 *   <li>匿名/兜底: {@code ip:<clientIp>}, 默认 120 req/min (ip-requests-per-minute)</li>
 * </ul>
 * 仅拦截 {@code /api/**} (actuator/静态资源不限); 超限返回 429 + Retry-After。
 * <p>
 * 实现为高优先级 WebFilter Bean (非 @Component, 避免与 SecurityWebFilterChain 双重注册):
 * userId 从 {@code SecurityContextWebFilter} 填充的 exchange attributes 读取
 * (未登录为 null, 走 IP 维度)。窗口翻转用 CAS 保持无锁。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class RateLimitWebFilter {

    private final V3Properties v3Properties;

    /** 窗口大小 1 分钟 */
    private static final long WINDOW_MS = 60_000L;

    /** 限流计数器: key -> 窗口 (volatile 计数, 翻转重置) */
    private final Map<String, Window> counters = new ConcurrentHashMap<>();

    static class Window {
        volatile long windowStart = System.currentTimeMillis();
        volatile int count = 0;
    }

    @Bean
    @Order(-50) // 在 SecurityWebFilterChain(-100) 之后执行, 可读取已填充的 userId attribute
    public WebFilter apiRateLimitFilter() {
        return (exchange, chain) -> {
            if (!v3Properties.getRateLimit().isEnabled()
                    || !exchange.getRequest().getPath().value().startsWith("/api/")) {
                return chain.filter(exchange);
            }
            String userId = SecurityContext.getUserId(exchange);
            String key = userId != null
                    ? "user:" + userId
                    : "ip:" + resolveClientIp(exchange);
            int limit = userId != null
                    ? v3Properties.getRateLimit().getUserRequestsPerMinute()
                    : v3Properties.getRateLimit().getIpRequestsPerMinute();
            if (limit <= 0) {
                return chain.filter(exchange);
            }
            if (!tryAcquire(key, limit)) {
                log.warn("限流触发: {} ({} req/min 超限)", key, limit);
                return reject(exchange);
            }
            return chain.filter(exchange);
        };
    }

    /** 固定窗口计数: 窗口过期翻转重置; 命中返回 false 表示超限 */
    private boolean tryAcquire(String key, int limit) {
        long now = System.currentTimeMillis();
        Window w = counters.computeIfAbsent(key, k -> new Window());
        if (now - w.windowStart >= WINDOW_MS) {
            synchronized (w) {
                if (now - w.windowStart >= WINDOW_MS) {
                    w.windowStart = now;
                    w.count = 0;
                }
            }
        }
        return ++w.count <= limit;
    }

    private Mono<Void> reject(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().set("Retry-After", "60");
        byte[] body = "{\"code\":429,\"message\":\"请求过于频繁, 请稍后再试\",\"error\":\"RATE_LIMITED\"}"
                .getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    private String resolveClientIp(ServerWebExchange exchange) {
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        var remote = exchange.getRequest().getRemoteAddress();
        return remote != null ? remote.getAddress().getHostAddress() : "unknown";
    }
}
