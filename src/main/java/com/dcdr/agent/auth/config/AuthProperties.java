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
            // 上传文件匿名访问: 用 Ant 通配 /** 放行所有子路径(图片 URL 如 /api/files/uploads/2026/08/31/xxx.png)。
            // 需与 SecurityConfig.authorizeExchange 的 Ant pathMatchers 语义一致;
            // isPermitted 已支持把结尾 "/**" 还原为前缀做 startsWith, 两处匹配口径统一。
            "/api/files/uploads/**"
    ));

    /** 登录失败锁定策略 */
    private LoginLock loginLock = new LoginLock();

    /** 跨域(CORS)配置 —— 全站唯一一处来源, 不再另建 CorsWebFilter Bean */
    private Cors cors = new Cors();

    @Data
    public static class Jwt {

        /**
         * 随仓发布的默认密钥 —— <b>公开可知, 仅供占位</b>。
         * <p>单独提为常量: {@code SecretGuard} 要按它判定"是否仍是默认值", 而这里又是它的初始化值 ——
         * 两处各写一份字面量的话, 改了这边忘了那边就会让校验静默失效。
         */
        public static final String DEFAULT_SECRET =
                "dcdr-agent-v22-default-secret-key-change-me-in-production-please-32bytes";

        /** HMAC-SHA256 密钥; 生产环境务必通过环境变量 JWT_SECRET 注入(缺省会被 SecretGuard 拒绝启动) */
        private String secret = DEFAULT_SECRET;

        /**
         * 是否允许使用内置默认密钥(默认否)。
         * <p>开启后启动不再被拦, 但 Token 可被任何人伪造 —— 只应在"仅本机、不对外暴露"时打开。
         * 之所以做成配置而不是判断 profile: 是否生产环境无法从代码可靠推断, 判断权交给部署方。
         */
        private boolean allowDefaultSecret = false;

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

    /**
     * CORS 白名单。
     * <p>
     * <b>为什么必须可配</b>: 前端经隧道 / 自建域名 / 内网 IP 访问时, 浏览器发出的 POST
     * (登录、改会话、发消息都是 POST) 会带上 {@code Origin: https://<域名>}; 若该来源不在白名单里,
     * Spring 的 CORS 过滤器会在进入 Controller 之前直接判为非法跨域并回 <b>403 Invalid CORS request</b>,
     * 而同一页面的 GET 请求不带 Origin、一切正常 —— 极易被误判成"账号或 Token 有问题"。
     * <p>
     * 语义同 Spring 的 {@code allowedOriginPatterns}: 支持 {@code *} 通配(如 {@code https://*.ts.net}),
     * 且可与 {@code allowCredentials=true} 共存; 若写成单个 {@code *} 并携带凭证, Spring 会直接拒绝请求。
     */
    @Data
    public static class Cors {

        /** 允许的 Origin 模式列表; 逗号分隔即可从环境变量整体覆盖 */
        private List<String> allowedOriginPatterns = new ArrayList<>(List.of(
                "http://localhost:*",
                "http://127.0.0.1:*"));
    }
}
