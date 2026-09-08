package com.dcdr.agent.auth.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * V3.0 F-40.9 安全加固: 统一设置安全响应头。
 * <p>
 * 作为普通 {@link WebFilter} 注册(由 Spring Boot 自动应用), 在每个 HTTP 响应上补充:
 * <ul>
 *   <li>{@code X-Content-Type-Options: nosniff} — 禁止 MIME 嗅探, 防样式/脚本注入</li>
 *   <li>{@code X-Frame-Options: DENY} — 禁止被 iframe 引用, 防点击劫持(SELF 场景可改 SAMEORIGIN)</li>
 *   <li>{@code Referrer-Policy: no-referrer} — 不携带来源信息</li>
 *   <li>{@code X-Permitted-Cross-Domain-Policies: none} — 限制 ACrobat/Flash 跨域读取</li>
 * </ul>
 * 简单幂等: 直接 set(覆盖同名头为固定安全值, 不叠加)。认证关闭分支(单用户)同样生效。
 */
@Slf4j
@Component
@Order(-500)
public class SecurityHeadersWebFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        HttpHeaders headers = exchange.getResponse().getHeaders();
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("X-Permitted-Cross-Domain-Policies", "none");
        return chain.filter(exchange);
    }
}