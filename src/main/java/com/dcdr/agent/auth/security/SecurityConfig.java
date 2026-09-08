package com.dcdr.agent.auth.security;

import com.dcdr.agent.auth.config.AuthProperties;
import com.dcdr.agent.auth.jwt.JwtUtil;
import com.dcdr.agent.common.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * V2.2 Spring Security WebFlux 安全配置 (文档 §8 技术选型: Spring Security + JWT)。
 * <p>
 * 替代原自研的 {@code JwtAuthenticationFilter} + {@code RbacAuthorizationFilter} 两个 WebFilter,
 * 改用 Spring Security 标准的 {@code SecurityWebFilterChain} 统一管理认证与授权。
 * <p>
 * <b>认证链路</b>:
 * <pre>
 *   请求 → SecurityContextWebFilter (同步解析 JWT → ThreadLocal + Reactor Context)
 *        → authorizeExchange (路径级权限校验, 基于 Reactor Context)
 *        → Controller
 * </pre>
 * <p>
 * <b>授权模型</b>: RBAC 三层 (User → Role → Permission), 权限编码 {@code 资源:操作} (如 kb:write),
 * 路径级用 {@code hasAuthority('kb:write')} 控制 (文档 F-23 权限编码)。
 * <p>
 * 注意: 不使用 {@code @PreAuthorize} 方法级注解, 因为它要求 Controller 方法返回 Mono/Flux;
 * 改用 {@code authorizeExchange} 路径级授权, 兼容现有同步返回类型的 Controller。
 */
@Slf4j
@Configuration
@EnableWebFluxSecurity
@RequiredArgsConstructor
public class    SecurityConfig {

    private final AuthProperties authProperties;
    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AccessTokenBlacklist accessTokenBlacklist;

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        // 认证关闭: 不拦截任何请求, 兼容 V2.1 单用户场景
        if (!authProperties.isEnabled()) {
            return http.csrf(spec -> spec.disable())
                    .httpBasic(spec -> spec.disable())
                    .formLogin(spec -> spec.disable())
                    .authorizeExchange(ex -> ex.anyExchange().permitAll())
                    .build();
        }

        // JWT 认证过滤器 (非 @Component, 避免被 Spring Boot 自动注册为独立 WebFilter 导致重复执行)
        SecurityContextWebFilter jwtAuthFilter = new SecurityContextWebFilter(jwtUtil, authProperties, accessTokenBlacklist);

        // 白名单路径 (从 AuthProperties 读取)
        String[] permitPaths = authProperties.getPermitPaths().toArray(new String[0]);

        http
                // 关闭默认表单登录/HTTP Basic (JWT 无状态)
                .httpBasic(spec -> spec.disable())
                .formLogin(spec -> spec.disable())
                // CSRF: JWT API 无需 CSRF 保护; 开启会导致 POST 请求被拦截
                .csrf(spec -> spec.disable())
                // CORS: 允许前端 dev server 跨域
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // ===== 路径级权限控制 (F-23 RBAC) =====
                .authorizeExchange(exchange -> exchange
                        // 白名单: 认证相关 + 模型列表
                        .pathMatchers(permitPaths).permitAll()
                        // 对话: chat:chat
                        .pathMatchers("/api/chat/**").hasAuthority(Permission.CHAT_CHAT)
                        // 会话读取 (GET): session:read
                        .pathMatchers(HttpMethod.GET, "/api/sessions/**").hasAuthority(Permission.SESSION_READ)
                        // 会话写入 (POST/PUT/DELETE/PATCH): session:write
                        .pathMatchers(HttpMethod.POST, "/api/sessions/**").hasAuthority(Permission.SESSION_WRITE)
                        .pathMatchers(HttpMethod.PUT, "/api/sessions/**").hasAuthority(Permission.SESSION_WRITE)
                        .pathMatchers(HttpMethod.DELETE, "/api/sessions/**").hasAuthority(Permission.SESSION_WRITE)
                        .pathMatchers(HttpMethod.PATCH, "/api/sessions/**").hasAuthority(Permission.SESSION_WRITE)
                        // 知识库检索 (POST search): kb:read (需在通用 POST 规则之前)
                        .pathMatchers(HttpMethod.POST, "/api/knowledge-bases/*/search").hasAuthority(Permission.KB_READ)
                        // 知识库读取 (GET): kb:read
                        .pathMatchers(HttpMethod.GET, "/api/knowledge-bases/**").hasAuthority(Permission.KB_READ)
                        // 知识库写入 (POST/PUT/DELETE): kb:write
                        .pathMatchers(HttpMethod.POST, "/api/knowledge-bases/**").hasAuthority(Permission.KB_WRITE)
                        .pathMatchers(HttpMethod.PUT, "/api/knowledge-bases/**").hasAuthority(Permission.KB_WRITE)
                        .pathMatchers(HttpMethod.DELETE, "/api/knowledge-bases/**").hasAuthority(Permission.KB_WRITE)
                        // V2.3 自定义 Agent 读取 (GET): agent:read
                        .pathMatchers(HttpMethod.GET, "/api/agents/**").hasAuthority(Permission.AGENT_READ)
                        // V2.3 自定义 Agent 写入 (POST/PUT/DELETE): agent:write
                        .pathMatchers(HttpMethod.POST, "/api/agents/**").hasAuthority(Permission.AGENT_WRITE)
                        .pathMatchers(HttpMethod.PUT, "/api/agents/**").hasAuthority(Permission.AGENT_WRITE)
                        .pathMatchers(HttpMethod.DELETE, "/api/agents/**").hasAuthority(Permission.AGENT_WRITE)
                        // V2.3 工作流编排 (CRUD + 执行 + V3.0 执行容错): workflow:execute
                        .pathMatchers("/api/workflows/**").hasAuthority(Permission.WORKFLOW_EXECUTE)
                        // V3.0 F-31 Webhook 触发端点 (公开, 靠 token 鉴权; 必须在管理规则之前)
                        .pathMatchers(HttpMethod.POST, "/api/webhooks/trigger/**").permitAll()
                        // V3.0 F-31 Webhook 管理 (CRUD): workflow:execute
                        .pathMatchers("/api/webhooks/**").hasAuthority(Permission.WORKFLOW_EXECUTE)
                        // V2.3 定时任务管理 (CRUD + 触发): schedule:manage
                        .pathMatchers("/api/scheduled-tasks/**").hasAuthority(Permission.SCHEDULE_MANAGE)
                        // V3.0-rc F-36 租户管理: user:manage (仅管理员)
                        .pathMatchers("/api/tenants/**").hasAuthority(Permission.USER_MANAGE)
                        // V3.0-rc F-37 插件管理: user:manage (仅管理员)
                        .pathMatchers("/api/plugins/**").hasAuthority(Permission.USER_MANAGE)
                        // 管理后台: user:manage
                        .pathMatchers("/api/admin/**").hasAuthority(Permission.USER_MANAGE)
                        // 其余 /api/** 端点: 仅需登录
                        .pathMatchers("/api/**").authenticated()
                        // 非 API 路径(静态资源/Swagger): 放行
                        .anyExchange().permitAll())
                // JWT 认证过滤器: 同步解析 + ThreadLocal + Reactor Context
                .addFilterAt(jwtAuthFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                // 401 未认证 (理论上不会触发, SecurityContextWebFilter 已处理; 此为兜底)
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler(jsonAccessDeniedHandler()));

        return http.build();
    }

    /** CORS 配置: 允许前端 dev server (5173/5174) 跨域访问 */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("http://localhost:*", "http://127.0.0.1:*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /** 403 权限不足: 返回 JSON 格式错误 (而非默认 HTML) */
    @Bean
    public ServerAccessDeniedHandler jsonAccessDeniedHandler() {
        return (exchange, denied) -> {
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            String body;
            try {
                body = objectMapper.writeValueAsString(Result.error(403, "权限不足", "FORBIDDEN"));
            } catch (Exception e) {
                body = "{\"code\":403,\"message\":\"权限不足\"}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            return exchange.getResponse().writeWith(
                    Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
        };
    }
}
