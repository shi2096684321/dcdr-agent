package com.dcdr.agent.auth.jwt;

import com.dcdr.agent.auth.config.AuthProperties;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JWT 签名密钥的行为契约 (V3.1)。
 *
 * <p>测两件事, 都指向同一个后果:
 * <ol>
 *   <li><b>仍用随仓默认密钥时必须拒绝启动</b> —— 签名密钥是公开可知的, 任何人都能据此
 *       伪造任意用户(含 admin)的 Token, 不需要知道任何密码;</li>
 *   <li><b>换密钥后旧 Token 立即失效</b> —— 这既是安全属性本身, 也解释了升级后
 *       为什么所有人都要重新登录。</li>
 * </ol>
 */
class JwtUtilTest {

    private static AuthProperties props(String secret) {
        AuthProperties p = new AuthProperties();
        p.getJwt().setSecret(secret);
        return p;
    }

    @Test
    @DisplayName("仍是内置默认密钥时拒绝启动")
    void rejectsShippedDefaultSecret() {
        // AuthProperties 的字段默认值就是随仓发布的那个(公开可知)
        JwtUtil jwt = new JwtUtil(new AuthProperties());

        assertThrows(IllegalStateException.class, jwt::init);
    }

    @Test
    @DisplayName("注入真实密钥后: 签发 / 校验 / 解析闭环可用")
    void worksWithInjectedSecret() {
        JwtUtil jwt = new JwtUtil(props("a-real-random-secret-0123456789abcdef0123456789"));
        jwt.init();

        String token = jwt.issueAccessToken("u1", "alice", "admin");

        assertTrue(jwt.isValid(token));
        Claims claims = jwt.parse(token);
        assertEquals("u1", claims.getSubject());
        assertEquals("alice", claims.get("username"));
        assertEquals("admin", claims.get("role"));
        assertTrue(jwt.isAccessToken(claims), "access 与 refresh 必须可区分, 否则刷新令牌能直接当访问令牌用");
    }

    @Test
    @DisplayName("更换签名密钥后旧 Token 立即失效(升级后需要重新登录的根因)")
    void tokensDoNotSurviveSecretRotation() {
        JwtUtil before = new JwtUtil(props("a-real-random-secret-0123456789abcdef0123456789"));
        before.init();
        String token = before.issueAccessToken("u1", "alice", "admin");

        JwtUtil after = new JwtUtil(props("b-real-random-secret-0123456789abcdef0123456789"));
        after.init();

        assertFalse(after.isValid(token), "换了签名密钥旧 Token 仍然有效的话, 等于没换");
    }

    @Test
    @DisplayName("显式放行时可用内置默认密钥启动(仅本机开发)")
    void optOutAllowsDefaultSecret() {
        AuthProperties p = new AuthProperties();
        p.getJwt().setAllowDefaultSecret(true);
        JwtUtil jwt = new JwtUtil(p);

        assertDoesNotThrow(jwt::init);
        assertTrue(jwt.isValid(jwt.issueAccessToken("u1", "alice", "user")));
    }
}
