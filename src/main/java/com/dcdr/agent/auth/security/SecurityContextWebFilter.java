package com.dcdr.agent.auth.security;

import com.dcdr.agent.auth.SecurityContext;
import com.dcdr.agent.auth.config.AuthProperties;
import com.dcdr.agent.auth.jwt.JwtUtil;
import com.dcdr.agent.common.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * V2.2 JWT 认证与上下文同步过滤器 (Spring Security 集成)。
 * <p>
 * 同时完成两件事, 替代原自研的 {@code JwtAuthenticationFilter}:
 * <ol>
 *   <li><b>同步解析 JWT</b>: 在 reactor-http-nio 线程同步解析 Token,
 *       将用户信息注入 ThreadLocal {@link SecurityContext},
 *       使现有业务代码({@code SecurityContext.getUserId()})无需改动。</li>
 *   <li><b>写入 Reactor Security Context</b>: 将 {@link Authentication} 写入 Reactor Context,
 *       使 Spring Security 的 {@code @PreAuthorize} 方法级权限注解可用。</li>
 * </ol>
 * <p>
 * <b>白名单</b>: {@link AuthProperties#getPermitPaths()} 中的路径前缀免鉴权。
 * <p>
 * <b>认证关闭</b>: {@code auth.enabled=false} 时直接放行, 兼容 V2.1 单用户场景。
 * <p>
 * 注意: 不加 {@code @Component}, 仅通过 {@link SecurityConfig#securityWebFilterChain} 的
 * {@code addFilterAt} 注册到 SecurityWebFilterChain, 避免被 Spring Boot 自动注册为独立 WebFilter 导致重复执行。
 */
@Slf4j
@RequiredArgsConstructor
public class SecurityContextWebFilter implements WebFilter {

    private final JwtUtil jwtUtil;
    private final AuthProperties authProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String BEARER_PREFIX = "Bearer ";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // 认证关闭: 直接放行 (V2.1 兼容模式)
        if (!authProperties.isEnabled()) {
            return chain.filter(exchange);
        }

        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        // 非 API 路径(静态资源/Swagger)直接放行
        if (!path.startsWith("/api/")) {
            return chain.filter(exchange);
        }
        // 白名单放行
        if (isPermitted(path)) {
            return chain.filter(exchange);
        }

        // 提取 Bearer Token
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            return unauthorized(exchange, 401, "缺少认证 Token", "MISSING_TOKEN");
        }
        String token = authHeader.substring(BEARER_PREFIX.length()).trim();

        // 同步解析 JWT (JWT parse 耗时 < 1ms, 不阻塞 reactor-http-nio 线程)
        Claims claims;
        try {
            claims = jwtUtil.parse(token);
        } catch (ExpiredJwtException e) {
            return unauthorized(exchange, 419, "Token 已过期", "TOKEN_EXPIRED");
        } catch (Exception e) {
            log.debug("Token 校验失败: {}", e.getMessage());
            return unauthorized(exchange, 401, "Token 无效", "INVALID_TOKEN");
        }

        // Refresh Token 不能直接访问业务 API
        if (!jwtUtil.isAccessToken(claims)) {
            return unauthorized(exchange, 401, "请使用 Access Token", "WRONG_TOKEN_TYPE");
        }

        // 提取用户信息
        String userId = claims.getSubject();
        String username = claims.get("username", String.class);
        String role = claims.get("role", String.class);

        // 构造 Spring Security GrantedAuthority: ROLE_ 前缀(角色) + 细粒度权限(chat:chat 等)
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(Permission.normalizeRole(role)));
        Set<String> perms = Permission.permissionsOf(role);
        for (String perm : perms) {
            authorities.add(new SimpleGrantedAuthority(perm));
        }

        // 1. 将用户信息写入 exchange attributes (Controller 通过 SecurityContext.fromExchange 获取)
        //    不使用 ThreadLocal, 因为 WebFlux DispatcherHandler 会切换线程池导致 ThreadLocal 丢失
        SecurityContext.setToExchange(exchange, new SecurityContext.CurrentUser(userId, username, role));

        // 2. 构造 Spring Security Authentication, 写入 Reactor Context (供 authorizeExchange 使用)
        Authentication authentication = new UsernamePasswordAuthenticationToken(username, null, authorities);

        return chain.filter(exchange)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
    }

    /** 判断 path 是否在白名单中 */
    private boolean isPermitted(String path) {
        List<String> permits = authProperties.getPermitPaths();
        if (permits == null || permits.isEmpty()) return false;
        for (String prefix : permits) {
            if (path.startsWith(prefix)) return true;
        }
        return false;
    }

    /** 返回 401/419 错误响应(JSON) */
    private Mono<Void> unauthorized(ServerWebExchange exchange, int code, String message, String error) {
        exchange.getResponse().setStatusCode(HttpStatus.valueOf(code == 419 ? 401 : code));
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body;
        try {
            body = objectMapper.writeValueAsString(Result.error(code, message, error));
        } catch (Exception e) {
            body = "{\"code\":" + code + ",\"message\":\"" + message + "\"}";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(bytes))
        );
    }
}
