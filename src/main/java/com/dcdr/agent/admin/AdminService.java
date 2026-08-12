package com.dcdr.agent.admin;

import com.dcdr.agent.audit.AuditService;
import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.entity.AuditLogEntity;
import com.dcdr.agent.entity.UserEntity;
import com.dcdr.agent.repository.AuditLogRepository;
import com.dcdr.agent.repository.DocumentRepository;
import com.dcdr.agent.repository.KnowledgeBaseRepository;
import com.dcdr.agent.repository.SessionRepository;
import com.dcdr.agent.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * V2.2 管理后台服务 (F-29)。
 * <p>
 * 提供用户管理(列表/角色/状态/重置密码)与系统统计(用户数/会话数/知识库数/审计查询)。
 * 仅 admin 角色可访问(由 Spring Security {@code @PreAuthorize("hasAuthority('user:manage')")} 保证)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminService {

    private final UserRepository userRepository;
    private final SessionRepository sessionRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DocumentRepository documentRepository;
    private final AuditLogRepository auditLogRepository;
    private final AuditService auditService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(10);

    /** 用户列表(分页, 脱敏) */
    public Page<AdminDtos.AdminUserView> listUsers(Pageable pageable) {
        return userRepository.findAll(pageable).map(this::toView);
    }

    /** 获取用户详情 */
    public AdminDtos.AdminUserView getUser(String userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(404, "用户不存在", "USER_NOT_FOUND"));
        return toView(user);
    }

    /** 更新用户角色/状态(管理员操作) */
    @Transactional
    public AdminDtos.AdminUserView updateUser(String userId, AdminDtos.UpdateUserRequest req) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(404, "用户不存在", "USER_NOT_FOUND"));
        String oldRole = user.getRole();
        String oldStatus = user.getStatus();
        if (req.getRole() != null && !req.getRole().isBlank()) {
            if (!"admin".equals(req.getRole()) && !"user".equals(req.getRole()) && !"guest".equals(req.getRole())) {
                throw new BusinessException(400, "角色非法, 仅支持 admin/user/guest", "INVALID_ROLE");
            }
            user.setRole(req.getRole());
        }
        if (req.getStatus() != null && !req.getStatus().isBlank()) {
            if (!"active".equals(req.getStatus()) && !"disabled".equals(req.getStatus())) {
                throw new BusinessException(400, "状态非法, 仅支持 active/disabled", "INVALID_STATUS");
            }
            user.setStatus(req.getStatus());
        }
        if (req.getEmail() != null) {
            user.setEmail(req.getEmail().isBlank() ? null : req.getEmail());
        }
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
        log.info("管理员更新用户: {} (role: {}->{}, status: {}->{})",
                user.getUsername(), oldRole, user.getRole(), oldStatus, user.getStatus());
        auditService.record("admin_update_user", "user", user.getId(),
                Map.of("username", user.getUsername(), "oldRole", oldRole, "newRole", user.getRole(),
                        "oldStatus", oldStatus, "newStatus", user.getStatus()), null);
        return toView(user);
    }

    /** 管理员重置用户密码 */
    @Transactional
    public void resetPassword(String userId, AdminDtos.ResetPasswordRequest req) {
        if (req.getNewPassword() == null || req.getNewPassword().length() < 8) {
            throw new BusinessException(400, "新密码至少 8 位", "INVALID_PASSWORD");
        }
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(404, "用户不存在", "USER_NOT_FOUND"));
        user.setPasswordHash(passwordEncoder.encode(req.getNewPassword()));
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
        log.info("管理员重置用户密码: {}", user.getUsername());
        auditService.record("admin_reset_password", "user", user.getId(),
                Map.of("username", user.getUsername()), null);
    }

    /** 系统统计概览 */
    public AdminDtos.SystemStats getStats() {
        AdminDtos.SystemStats stats = new AdminDtos.SystemStats();
        stats.setUserCount(userRepository.count());
        stats.setActiveUserCount(userRepository.countByStatus("active"));
        stats.setAdminCount(userRepository.countByRole("admin"));
        stats.setSessionCount(sessionRepository.count());
        stats.setKnowledgeBaseCount(knowledgeBaseRepository.count());
        stats.setDocumentCount(documentRepository.count());
        LocalDateTime todayStart = LocalDateTime.now().toLocalDate().atStartOfDay();
        LocalDateTime now = LocalDateTime.now();
        stats.setTodayLoginCount(auditLogRepository.countByActionAndCreatedAtBetween("login", todayStart, now));
        stats.setTodayChatCount(auditLogRepository.countByActionAndCreatedAtBetween("chat", todayStart, now));
        stats.setAuditLogCount(auditLogRepository.count());
        return stats;
    }

    /** 审计日志分页查询 */
    public Page<AuditLogEntity> queryAuditLogs(String userId, String action,
                                                LocalDateTime from, LocalDateTime to, Pageable pageable) {
        return auditService.query(userId, action, from, to, pageable);
    }

    // ===== 内部方法 =====

    private AdminDtos.AdminUserView toView(UserEntity user) {
        AdminDtos.AdminUserView view = new AdminDtos.AdminUserView();
        view.setId(user.getId());
        view.setUsername(user.getUsername());
        view.setEmail(user.getEmail());
        view.setRole(user.getRole());
        view.setStatus(user.getStatus());
        view.setCreatedAt(user.getCreatedAt());
        view.setUpdatedAt(user.getUpdatedAt());
        return view;
    }
}
