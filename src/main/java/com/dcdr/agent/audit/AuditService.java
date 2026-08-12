package com.dcdr.agent.audit;

import com.dcdr.agent.entity.AuditLogEntity;
import com.dcdr.agent.repository.AuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * V2.2 审计日志服务 (F-28)。
 * <p>
 * 记录用户操作(登录/CRUD/对话等)、API 调用、系统事件, 用于:
 * <ul>
 *   <li>Token 消耗统计(按用户/模型/日期)</li>
 *   <li>异常排查</li>
 *   <li>使用分析</li>
 * </ul>
 * <p>
 * <b>异步落库</b>: 所有记录方法都标注 {@link Async}, 不阻塞业务主流程;
 * 若审计落库失败仅记录日志, 不影响业务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 记录操作日志(异步, auditExecutor 线程池) */
    @Async("auditExecutor")
    @Transactional
    public void record(String action, String resourceType, String resourceId, Object detail, String userId) {
        try {
            AuditLogEntity log = build(action, resourceType, resourceId, detail, userId);
            auditLogRepository.save(log);
        } catch (Exception e) {
            // 审计失败不影响主业务, 仅记录日志
            AuditService.log.warn("审计日志落库失败: action={}, resource={}/{}, err={}",
                    action, resourceType, resourceId, e.getMessage());
        }
    }

    /** 同步记录(仅用于必须立即落库的场景, 如登录审计) */
    @Transactional
    public void recordSync(String action, String resourceType, String resourceId, Object detail, String userId, String ip) {
        try {
            AuditLogEntity entity = build(action, resourceType, resourceId, detail, userId);
            if (ip != null) entity.setIp(ip);
            auditLogRepository.save(entity);
        } catch (Exception e) {
            AuditService.log.warn("审计日志落库失败(同步): action={}, err={}", action, e.getMessage());
        }
    }

    /** 分页查询审计日志(管理后台用) */
    public Page<AuditLogEntity> query(String userId, String action, LocalDateTime from, LocalDateTime to, Pageable pageable) {
        return auditLogRepository.queryByConditions(userId, action, from, to, pageable);
    }

    /** 统计指定时间范围内的操作次数 */
    public long countByAction(String action, LocalDateTime from, LocalDateTime to) {
        return auditLogRepository.countByActionAndCreatedAtBetween(action, from, to);
    }

    // ===== 内部方法 =====

    private AuditLogEntity build(String action, String resourceType, String resourceId, Object detail, String userId) {
        AuditLogEntity entity = new AuditLogEntity();
        entity.setId(generateId());
        entity.setAction(action);
        entity.setResourceType(resourceType);
        entity.setResourceId(resourceId);
        if (detail != null) {
            try {
                entity.setDetail(detail instanceof String s ? s : objectMapper.writeValueAsString(detail));
            } catch (Exception e) {
                entity.setDetail(String.valueOf(detail));
            }
        }
        // 由调用方显式传入 userId(异步线程无法读取请求上下文)
        if (userId != null) {
            entity.setUserId(userId);
        }
        entity.setCreatedAt(LocalDateTime.now());
        return entity;
    }

    private static String generateId() {
        return "audit_" + System.currentTimeMillis()
                + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000, 0xfffffff));
    }
}
