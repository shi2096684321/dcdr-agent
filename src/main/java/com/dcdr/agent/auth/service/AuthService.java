package com.dcdr.agent.auth.service;

import com.dcdr.agent.audit.AuditService;
import com.dcdr.agent.auth.config.AuthProperties;
import com.dcdr.agent.auth.dto.AuthDtos;
import com.dcdr.agent.auth.jwt.JwtUtil;
import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.entity.UserEntity;
import com.dcdr.agent.repository.UserRepository;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V2.2 认证服务: 注册 / 登录 / 刷新 / 改密 / 当前用户。
 * <p>
 * 密码哈希采用 BCrypt(workFactor=10); 登录失败次数与锁定状态暂存内存
 * (单实例场景够用, 多实例部署需迁至 Redis)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final AuthProperties authProperties;
    private final AuditService auditService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(10);

    /** 登录失败计数: username -> FailureRecord */
    private final Map<String, FailureRecord> failureMap = new ConcurrentHashMap<>();

    /**
     * 用户注册。
     * <p>首位注册用户自动成为 admin(便于首次部署初始化管理员)。
     */
    @Transactional
    public AuthDtos.AuthResponse register(AuthDtos.RegisterRequest req) {
        // 用户名唯一校验
        if (userRepository.findByUsername(req.getUsername()).isPresent()) {
            throw new BusinessException(409, "用户名已被占用", "USERNAME_EXISTS");
        }
        // 邮箱唯一校验(若提供)
        if (req.getEmail() != null && !req.getEmail().isBlank()
                && userRepository.existsByEmail(req.getEmail())) {
            throw new BusinessException(409, "邮箱已被注册", "EMAIL_EXISTS");
        }

        UserEntity user = new UserEntity();
        user.setId("u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        user.setUsername(req.getUsername());
        user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        user.setEmail(req.getEmail());
        // 首位用户自动为 admin
        boolean firstUser = userRepository.count() == 0;
        user.setRole(firstUser ? "admin" : "user");
        user.setStatus("active");
        LocalDateTime now = LocalDateTime.now();
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userRepository.save(user);
        log.info("用户注册成功: {} (role={}, firstUser={})", user.getUsername(), user.getRole(), firstUser);

        // V2.2 审计: 记录注册事件(actor=注册用户本人)
        auditService.record("register", "user", user.getId(),
                Map.of("username", user.getUsername(), "role", user.getRole(), "firstUser", firstUser),
                user.getId());
        return buildAuthResponse(user);
    }

    /** 用户登录(含失败锁定) */
    public AuthDtos.AuthResponse login(AuthDtos.LoginRequest req) {
        String username = req.getUsername();
        // 锁定校验
        FailureRecord record = failureMap.get(username);
        if (record != null && record.isLocked(authProperties.getLoginLock().getLockTtl())) {
            long remain = authProperties.getLoginLock().getLockTtl().toMinutes()
                    - Duration.between(record.lastFailAt, LocalDateTime.now()).toMinutes();
            throw new BusinessException(423, "登录失败次数过多, 请 " + Math.max(1, remain) + " 分钟后再试", "ACCOUNT_LOCKED");
        }

        UserEntity user = userRepository.findByUsername(username)
                .orElseThrow(() -> {
                    recordFailure(username);
                    return new BusinessException(401, "用户名或密码错误", "BAD_CREDENTIALS");
                });

        if (!"active".equals(user.getStatus())) {
            throw new BusinessException(403, "账号已被禁用", "ACCOUNT_DISABLED");
        }
        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            recordFailure(username);
            log.warn("登录密码错误: {}", username);
            throw new BusinessException(401, "用户名或密码错误", "BAD_CREDENTIALS");
        }

        // 登录成功: 清空失败计数
        failureMap.remove(username);
        log.info("用户登录成功: {} (role={})", username, user.getRole());
        // V2.2 审计: 记录登录成功事件(同步落库, 因为是关键安全事件)
        auditService.recordSync("login", "user", user.getId(),
                Map.of("username", user.getUsername(), "role", user.getRole()), user.getId(), null);
        return buildAuthResponse(user);
    }

    /** 使用 Refresh Token 续签 */
    public AuthDtos.AuthResponse refresh(AuthDtos.RefreshRequest req) {
        Claims claims;
        try {
            claims = jwtUtil.parse(req.getRefreshToken());
        } catch (Exception e) {
            throw new BusinessException(401, "Refresh Token 无效或已过期", "INVALID_REFRESH_TOKEN");
        }
        if (!jwtUtil.isRefreshToken(claims)) {
            throw new BusinessException(401, "请提供 Refresh Token", "WRONG_TOKEN_TYPE");
        }
        String userId = claims.getSubject();
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(401, "用户不存在", "USER_NOT_FOUND"));
        if (!"active".equals(user.getStatus())) {
            throw new BusinessException(403, "账号已被禁用", "ACCOUNT_DISABLED");
        }
        return buildAuthResponse(user);
    }

    /** 获取当前登录用户信息 */
    public AuthDtos.UserInfo getCurrentUser(String userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(404, "用户不存在", "USER_NOT_FOUND"));
        return toUserInfo(user);
    }

    /** 修改密码 */
    @Transactional
    public void changePassword(String userId, AuthDtos.ChangePasswordRequest req) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(404, "用户不存在", "USER_NOT_FOUND"));
        if (!passwordEncoder.matches(req.getOldPassword(), user.getPasswordHash())) {
            throw new BusinessException(400, "原密码错误", "BAD_OLD_PASSWORD");
        }
        if (passwordEncoder.matches(req.getNewPassword(), user.getPasswordHash())) {
            throw new BusinessException(400, "新密码不能与原密码相同", "SAME_PASSWORD");
        }
        user.setPasswordHash(passwordEncoder.encode(req.getNewPassword()));
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
        log.info("用户 {} 修改密码成功", user.getUsername());
        // V2.2 审计: 记录改密事件(actor=改密用户本人)
        auditService.record("change_password", "user", user.getId(),
                Map.of("username", user.getUsername()), userId);
    }

    // ===== 内部方法 =====

    private AuthDtos.AuthResponse buildAuthResponse(UserEntity user) {
        AuthDtos.AuthResponse resp = new AuthDtos.AuthResponse();
        resp.setAccessToken(jwtUtil.issueAccessToken(user.getId(), user.getUsername(), user.getRole()));
        resp.setRefreshToken(jwtUtil.issueRefreshToken(user.getId(), user.getUsername(), user.getRole()));
        resp.setExpiresIn(authProperties.getJwt().getAccessTtl().getSeconds());
        resp.setUser(toUserInfo(user));
        return resp;
    }

    private AuthDtos.UserInfo toUserInfo(UserEntity user) {
        AuthDtos.UserInfo info = new AuthDtos.UserInfo();
        info.setId(user.getId());
        info.setUsername(user.getUsername());
        info.setEmail(user.getEmail());
        info.setRole(user.getRole());
        info.setStatus(user.getStatus());
        info.setCreatedAt(user.getCreatedAt());
        return info;
    }

    private void recordFailure(String username) {
        FailureRecord record = failureMap.computeIfAbsent(username, k -> new FailureRecord());
        record.failures++;
        record.lastFailAt = LocalDateTime.now();
        log.debug("登录失败计数: {} -> {}", username, record.failures);
    }

    /** 每小时清理过期的失败记录(避免内存膨胀) */
    @Scheduled(fixedDelay = 60 * 60 * 1000L)
    public void cleanupFailureRecords() {
        Duration lockTtl = authProperties.getLoginLock().getLockTtl();
        LocalDateTime threshold = LocalDateTime.now().minus(lockTtl);
        failureMap.entrySet().removeIf(e -> e.getValue().lastFailAt.isBefore(threshold));
    }

    /** 登录失败记录 */
    private static class FailureRecord {
        int failures;
        LocalDateTime lastFailAt;
        boolean isLocked(Duration lockTtl) {
            return failures >= 5 && lastFailAt != null
                    && lastFailAt.plus(lockTtl).isAfter(LocalDateTime.now());
        }
    }
}
