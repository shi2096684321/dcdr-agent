package com.dcdr.agent.auth.jwt;

import com.dcdr.agent.auth.config.AuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * V2.2 JWT 工具类: 签发 / 解析 / 校验。
 * <p>
 * Token Claim 设计:
 * <ul>
 *   <li>{@code sub} - 用户 ID</li>
 *   <li>{@code username} - 用户名</li>
 *   <li>{@code role} - 角色</li>
 *   <li>{@code type} - access / refresh (区分访问令牌与刷新令牌)</li>
 *   <li>{@code iat}/{@code exp} - 标准签发/过期时间</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtUtil {

    private final AuthProperties properties;
    private SecretKey signingKey;

    @PostConstruct
    public void init() {
        byte[] keyBytes = properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8);
        // HMAC-SHA256 要求密钥 >= 256 bit (32 字节); 不足则补齐避免 WeakKeyException
        if (keyBytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(keyBytes, 0, padded, 0, keyBytes.length);
            keyBytes = padded;
            log.warn("JWT 密钥长度不足 32 字节, 已自动补齐(生产环境请配置足够长度的密钥)");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    /** 签发 Access Token (用于 API 鉴权) */
    public String issueAccessToken(String userId, String username, String role) {
        return issueToken(userId, username, role, "access", properties.getJwt().getAccessTtl());
    }

    /** 签发 Refresh Token (用于续签, 不能直接访问 API) */
    public String issueRefreshToken(String userId, String username, String role) {
        return issueToken(userId, username, role, "refresh", properties.getJwt().getRefreshTtl());
    }

    private String issueToken(String userId, String username, String role, String type, Duration ttl) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(properties.getJwt().getIssuer())
                .subject(userId)
                .claim("username", username)
                .claim("role", role)
                .claim("type", type)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(signingKey)
                .compact();
    }

    /**
     * 解析并校验 Token。
     *
     * @return Claims 解析成功; 过期抛 {@link ExpiredJwtException}, 其他异常抛 {@link JwtException}
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** 校验 Token 是否合法且未过期; 不抛异常, 失败返回 false */
    public boolean isValid(String token) {
        try {
            parse(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /** 校验是否为 Access Token 类型(避免 Refresh Token 被滥用直接访问 API) */
    public boolean isAccessToken(Claims claims) {
        return "access".equals(claims.get("type", String.class));
    }

    /** 校验是否为 Refresh Token 类型 */
    public boolean isRefreshToken(Claims claims) {
        return "refresh".equals(claims.get("type", String.class));
    }
}
