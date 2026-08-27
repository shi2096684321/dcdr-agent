package com.dcdr.agent.auth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * V2.2 用户认证配置属性。
 * <p>
 * 对应 application.yml 中 {@code agent.auth.*} 配置项。
 */
@Data
@ConfigurationProperties(prefix = "agent.auth")
public class AuthProperties {

    /** 是否启用认证; 关闭时所有接口免鉴权(V2.1 兼容模式) */
    private boolean enabled = true;

    /** JWT 配置 */
    private Jwt jwt = new Jwt();

    /** 鉴权白名单(无需 Token 即可访问的路径前缀) */
    private List<String> permitPaths = new ArrayList<>(List.of(
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/refresh",
//            "/api/models",
            "/v3/api-docs",
            "/swagger-ui",
            "/h2-console",
            "/api/files/uploads"
    ));

    /** 登录失败锁定策略 */
    private LoginLock loginLock = new LoginLock();

    @Data
    public static class Jwt {
        /** HMAC-SHA256 密钥; 生产环境务必通过环境变量/外部配置注入, 避免硬编码泄露 */
        private String secret = "dcdr-agent-v22-default-secret-key-change-me-in-production-please-32bytes";
        /** Access Token 有效期 */
        private Duration accessTtl = Duration.ofHours(24);
        /** Refresh Token 有效期 */
        private Duration refreshTtl = Duration.ofDays(7);
        /** Token 签发者 */
        private String issuer = "dcdr-agent";
    }

    @Data
    public static class LoginLock {
        /** 触发锁定的连续失败次数 */
        private int maxFailures = 5;
        /** 锁定时长 */
        private Duration lockTtl = Duration.ofMinutes(30);
    }
}
