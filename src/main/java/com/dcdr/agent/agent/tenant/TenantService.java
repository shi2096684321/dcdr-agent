package com.dcdr.agent.agent.tenant;

import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.config.V3Properties;
import com.dcdr.agent.dto.TenantDtos;
import com.dcdr.agent.entity.TenantEntity;
import com.dcdr.agent.entity.UserEntity;
import com.dcdr.agent.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * V3.0-rc F-36 多租户管理。
 * <p>
 * 租户是资源配额的载体 (文档 §10): 用户经 {@code users.tenant_id} 归属租户,
 * 创建 Agent/工作流时按租户上限拦截; 月 token 配额在 {@code checkResourceQuota}
 * 与用量统计中体现。tenantId 为 null 的平台直属用户不受租户配额约束。
 * <p>
 * 隔离策略沿用 V2.2 数据隔离 (查询按 userId 过滤), 租户通过
 * {@code userId → tenant_id} 间接隔离 (文档 §10.3), 不改变现有查询链路。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantService {

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final AgentDefinitionRepository agentRepository;
    private final WorkflowDefinitionRepository workflowRepository;
    private final LlmUsageLogRepository usageRepository;
    private final V3Properties v3Properties;

    /** 创建租户 (管理员) */
    @Transactional
    public TenantDtos.TenantView create(TenantDtos.SaveTenantRequest req) {
        if (req.getName() == null || req.getName().isBlank()) {
            throw new BusinessException(400, "租户名称不能为空");
        }
        if (!tenantRepository.findByName(req.getName()).isEmpty()) {
            throw new BusinessException(409, "同名租户已存在: " + req.getName());
        }
        TenantEntity e = new TenantEntity();
        e.setId("tnt_" + UUID.randomUUID().toString().replace("-", ""));
        applyRequest(e, req);
        e.setCreatedAt(LocalDateTime.now());
        tenantRepository.save(e);
        log.info("创建租户: {} (name={}, plan={})", e.getId(), e.getName(), e.getPlan());
        return toView(e);
    }

    /** 更新租户套餐/限额 (管理员) */
    @Transactional
    public TenantDtos.TenantView update(String id, TenantDtos.SaveTenantRequest req) {
        TenantEntity e = load(id);
        applyRequest(e, req);
        tenantRepository.save(e);
        return toView(e);
    }

    public TenantDtos.TenantView get(String id) {
        return toView(load(id));
    }

    public List<TenantDtos.TenantView> list() {
        return tenantRepository.findAll().stream().map(this::toView).toList();
    }

    /** 用户归属租户: tenantId=null 表示移出租户 (变回平台直属用户) */
    @Transactional
    public void assignUser(TenantDtos.AssignUserRequest req) {
        if (req.getUserId() == null || req.getUserId().isBlank()) {
            throw new BusinessException(400, "userId 不能为空");
        }
        UserEntity user = userRepository.findById(req.getUserId())
                .orElseThrow(() -> new BusinessException(404, "用户不存在: " + req.getUserId()));
        if (req.getTenantId() != null) {
            TenantEntity tenant = load(req.getTenantId());
            if (!"active".equals(tenant.getStatus())) {
                throw new BusinessException(409, "租户已停用, 无法加入: " + tenant.getName());
            }
            // 用户数配额检查
            List<String> members = memberIds(req.getTenantId());
            if (members.size() >= tenant.getMaxUsers()) {
                throw new BusinessException(429, "租户用户数已达上限 (" + members.size() + "/" + tenant.getMaxUsers() + ")");
            }
        }
        user.setTenantId(req.getTenantId());
        userRepository.save(user);
        log.info("用户 {} 归属租户 -> {}", req.getUserId(), req.getTenantId());
    }

    /** 租户资源使用情况 (配额面板) */
    public TenantDtos.TenantUsage usage(String id) {
        TenantEntity t = load(id);
        List<String> members = memberIds(id);
        TenantDtos.TenantUsage u = new TenantDtos.TenantUsage();
        u.setTenantId(t.getId());
        u.setTenantName(t.getName());
        u.setUserCount(members.size());
        u.setMaxUsers(t.getMaxUsers());
        u.setAgentCount((int) agentRepository.countByUserIds(members));
        u.setMaxAgents(t.getMaxAgents());
        u.setWorkflowCount((int) workflowRepository.countByUserIds(members));
        u.setMaxWorkflows(t.getMaxWorkflows());
        LocalDate firstOfMonth = LocalDate.now().withDayOfMonth(1);
        u.setMonthTokens(usageRepository.sumTokensByUserIdsSince(members, firstOfMonth.atStartOfDay()));
        u.setMaxTokensMonth(t.getMaxTokensMonth());
        u.setTokenUsageRate(t.getMaxTokensMonth() > 0 && u.getMonthTokens() > 0
                ? (double) u.getMonthTokens() / t.getMaxTokensMonth() : null);
        return u;
    }

    /**
     * 租户资源配额检查 (创建 Agent / 工作流前调用)。
     * <ul>
     *   <li>多租户未启用 / 用户无租户 → 直通</li>
     *   <li>资源类型: agent / workflow; 超上限抛 429</li>
     * </ul>
     */
    public void checkResourceQuota(String userId, String resourceType) {
        if (!v3Properties.getTenant().isEnabled() || userId == null) return;
        UserEntity user = userRepository.findById(userId).orElse(null);
        if (user == null || user.getTenantId() == null) return;
        TenantEntity tenant = tenantRepository.findById(user.getTenantId()).orElse(null);
        if (tenant == null) return;
        if (!"active".equals(tenant.getStatus())) {
            throw new BusinessException(409, "所属租户已停用, 无法创建资源");
        }
        List<String> members = memberIds(tenant.getId());
        switch (resourceType) {
            case "agent" -> {
                long count = agentRepository.countByUserIds(members);
                if (count >= tenant.getMaxAgents()) {
                    throw new BusinessException(429, "租户 Agent 数已达上限 ("
                            + count + "/" + tenant.getMaxAgents() + "), 请联系管理员升级套餐");
                }
            }
            case "workflow" -> {
                long count = workflowRepository.countByUserIds(members);
                if (count >= tenant.getMaxWorkflows()) {
                    throw new BusinessException(429, "租户工作流数已达上限 ("
                            + count + "/" + tenant.getMaxWorkflows() + "), 请联系管理员升级套餐");
                }
            }
            default -> { }
        }
    }

    private List<String> memberIds(String tenantId) {
        return userRepository.findByTenantId(tenantId).stream().map(UserEntity::getId).toList();
    }

    private TenantEntity load(String id) {
        return tenantRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "租户不存在: " + id));
    }

    private void applyRequest(TenantEntity e, TenantDtos.SaveTenantRequest req) {
        if (req.getName() != null && !req.getName().isBlank()) e.setName(req.getName());
        if (req.getPlan() != null) e.setPlan(req.getPlan());
        if (req.getMaxUsers() != null) e.setMaxUsers(req.getMaxUsers());
        if (req.getMaxAgents() != null) e.setMaxAgents(req.getMaxAgents());
        if (req.getMaxWorkflows() != null) e.setMaxWorkflows(req.getMaxWorkflows());
        if (req.getMaxTokensMonth() != null) e.setMaxTokensMonth(req.getMaxTokensMonth());
        if (req.getStatus() != null) e.setStatus(req.getStatus());
    }

    private TenantDtos.TenantView toView(TenantEntity e) {
        TenantDtos.TenantView v = new TenantDtos.TenantView();
        v.setId(e.getId());
        v.setName(e.getName());
        v.setPlan(e.getPlan());
        v.setMaxUsers(e.getMaxUsers());
        v.setMaxAgents(e.getMaxAgents());
        v.setMaxWorkflows(e.getMaxWorkflows());
        v.setMaxTokensMonth(e.getMaxTokensMonth());
        v.setStatus(e.getStatus());
        v.setCreatedAt(e.getCreatedAt());
        return v;
    }
}
