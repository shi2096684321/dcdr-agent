package com.dcdr.agent.config;

import com.dcdr.agent.auth.SecurityContext;
import com.dcdr.agent.cluster.RateLimitStore;
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
import java.time.Duration;

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
 * (未登录为 null, 走 IP 维度)。
 * <p>
 * <b>V3.1 F-46</b>: 计数状态从"内联的进程内 Map"改为委托 {@link RateLimitStore} ——
 * 目的是让多实例部署能把计数搬到集中式存储(进程内计数在 N 个实例下会把总限额放大 N 倍)。
 * 默认 {@code backend=memory} 时仍是进程内实现, 行为与迁移前一致; 且过滤器的热路径全程非阻塞,
 * 集中式实现也用响应式客户端(不占住 reactor 事件循环线程)。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class RateLimitWebFilter {

    private final V3Properties v3Properties;
    private final RateLimitStore rateLimitStore;

    /** 窗口大小 1 分钟 */
    private static final Duration WINDOW = Duration.ofMinutes(1);

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
            return rateLimitStore.tryAcquire(key, limit, WINDOW)
                    // 防御(review 发现): 两个实现按约定都会发一个元素, 但若某天出现空流,
                    // flatMap 不会执行 → 请求既不放行也不响应, 客户端表现为**永久挂起**。
                    // 空流按放行处理(fail-open), 与限流"保护性能力"的定位一致。
                    .defaultIfEmpty(true)
                    .flatMap(allowed -> Boolean.TRUE.equals(allowed)
                            ? chain.filter(exchange)
                            : reject(exchange, key, limit));
        };
    }

    private Mono<Void> reject(ServerWebExchange exchange, String key, int limit) {
        log.warn("限流触发: {} ({} req/min 超限)", key, limit);
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
