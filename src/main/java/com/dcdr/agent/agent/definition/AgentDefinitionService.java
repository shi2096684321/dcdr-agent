package com.dcdr.agent.agent.definition;

import com.dcdr.agent.agent.tenant.TenantService;
import com.dcdr.agent.auth.ownership.OwnershipGuard;
import com.dcdr.agent.auth.ownership.ResourceType;
import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.dto.AgentDtos;
import com.dcdr.agent.entity.AgentDefinitionEntity;
import com.dcdr.agent.repository.AgentDefinitionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * V2.3 自定义 Agent 管理 Service (F-24)。
 * <p>
 * 提供 Agent 的 CRUD 与预设模板库:
 * <ul>
 *   <li>用户可创建/更新/删除自己的 Agent</li>
 *   <li>预设模板 (isTemplate=true) 对所有用户可见且不可删除, 仅可克隆</li>
 *   <li>公开 Agent (isPublic=true) 可被其他用户在对话中选用</li>
 * </ul>
 * Agent 配置项: name / avatar / systemPrompt / model / tools / kbIds / temperature。
 * 对话时通过 {@code ChatRequest.agentId} 传入, 由 {@code AgentOrchestrator} 加载并应用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentDefinitionService {

    private final AgentDefinitionRepository repository;
    private final TenantService tenantService;
    /** V3.1 F-41: 归属判定统一收敛到 OwnershipGuard (可见性口径见 AgentOwnershipProvider) */
    private final OwnershipGuard ownershipGuard;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 列出 Agent: 管理员返回全部(含他人私有); 普通用户返回自己可见的
     * (自己的 + 公开的 + 预设模板)。
     */
    public List<AgentDtos.AgentView> list(String userId, boolean isAdmin) {
        List<AgentDefinitionEntity> entities = isAdmin
                ? repository.findAllByOrderByIsTemplateDescUpdatedAtDesc()
                : repository.findVisible(userId);
        List<AgentDtos.AgentView> views = new ArrayList<>(entities.size());
        for (AgentDefinitionEntity e : entities) {
            views.add(toView(e, userId));
        }
        return views;
    }

    /** 仅预设模板 (模板库) */
    public List<AgentDtos.AgentView> listTemplates() {
        List<AgentDefinitionEntity> entities = repository.findByIsTemplateTrueOrderByCreatedAtDesc();
        List<AgentDtos.AgentView> views = new ArrayList<>(entities.size());
        for (AgentDefinitionEntity e : entities) {
            views.add(toView(e, null));
        }
        return views;
    }

    /** 详情 (归属校验: 创建者 / 公开 / 模板可读, 越权统一返回 404) */
    public AgentDtos.AgentView get(String id, String userId, boolean isAdmin) {
        ownershipGuard.check(ResourceType.AGENT, id, userId, isAdmin, false);
        return toView(loadEntity(id), userId);
    }

    /** 内部加载实体 (供 Orchestrator/WorkflowEngine 调用, 不做可见性校验) */
    public AgentDefinitionEntity loadEntity(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "Agent 不存在: " + id, "AGENT_NOT_FOUND"));
    }

    @Transactional
    public AgentDtos.AgentView create(AgentDtos.SaveAgentRequest req, String userId, boolean isAdmin) {
        validateName(req.getName());
        // V3.0-rc F-36: 租户级 Agent 数配额检查 (未启用多租户时直通)
        tenantService.checkResourceQuota(userId, "agent");
        // 仅管理员可创建模板
        boolean asTemplate = Boolean.TRUE.equals(req.getIsTemplate());
        if (asTemplate && !isAdmin) {
            throw new BusinessException(403, "仅管理员可创建预设模板", "FORBIDDEN");
        }
        AgentDefinitionEntity e = new AgentDefinitionEntity();
        e.setId("agent_" + UUID.randomUUID().toString().replace("-", ""));
        e.setUserId(userId);
        applyRequest(e, req);
        e.setTemplate(asTemplate);
        LocalDateTime now = LocalDateTime.now();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        repository.save(e);
        log.info("创建 Agent: {} (name={}, user={}, template={})", e.getId(), e.getName(), userId, asTemplate);
        return toView(e, userId);
    }

    @Transactional
    public AgentDtos.AgentView update(String id, AgentDtos.SaveAgentRequest req, String userId, boolean isAdmin) {
        // 归属校验(写): 模板仅 admin; 非模板仅创建者; admin 全部放行
        ownershipGuard.check(ResourceType.AGENT, id, userId, isAdmin, true);
        AgentDefinitionEntity e = loadEntity(id);
        validateName(req.getName());
        applyRequest(e, req);
        // 管理员可在更新时切换模板标记 (普通用户不允许, 上面已拦截)
        if (isAdmin && req.getIsTemplate() != null) {
            e.setTemplate(req.getIsTemplate());
        }
        e.setUpdatedAt(LocalDateTime.now());
        repository.save(e);
        log.info("更新 Agent: {} (name={}, template={})", e.getId(), e.getName(), e.isTemplate());
        return toView(e, userId);
    }

    @Transactional
    public void delete(String id, String userId, boolean isAdmin) {
        // 归属校验(写): 模板仅 admin; 非模板仅创建者; admin 全部放行
        ownershipGuard.check(ResourceType.AGENT, id, userId, isAdmin, true);
        AgentDefinitionEntity e = loadEntity(id);
        repository.delete(e);
        log.info("删除 Agent: {} (name={}, template={})", e.getId(), e.getName(), e.isTemplate());
    }

    /**
     * 克隆模板或他人公开 Agent 为自己的副本 (F-24-001 验收 2: 支持克隆预设模板)。
     */
    @Transactional
    public AgentDtos.AgentView clone(String id, String userId, boolean isAdmin) {
        // 归属校验(读): 仅可克隆自己 / 公开 / 模板的 Agent
        ownershipGuard.check(ResourceType.AGENT, id, userId, isAdmin, false);
        AgentDefinitionEntity src = loadEntity(id);
        AgentDtos.SaveAgentRequest req = new AgentDtos.SaveAgentRequest();
        req.setName(src.getName() + " (副本)");
        req.setDescription(src.getDescription());
        req.setAvatar(src.getAvatar());
        req.setPersona(src.getSystemPrompt());
        req.setModel(src.getModel());
        req.setTools(parseStrList(src.getTools()));
        req.setKbIds(parseStrList(src.getKbIds()));
        req.setTemperature(src.getTemperature());
        req.setIsPublic(false);
        req.setIsTemplate(false); // 克隆出来的始终是普通 Agent
        return create(req, userId, false);
    }

    // ===== 内部辅助 =====

    private void applyRequest(AgentDefinitionEntity e, AgentDtos.SaveAgentRequest req) {
        e.setName(req.getName().trim());
        e.setDescription(req.getDescription());
        e.setAvatar(req.getAvatar());
        e.setSystemPrompt(req.getPersona());
        e.setModel((req.getModel() == null || req.getModel().isBlank()) ? null : req.getModel().trim());
        e.setTools(serializeList(req.getTools()));
        e.setKbIds(serializeList(req.getKbIds()));
        // 温度参数校验: 0~2
        BigDecimal temp = req.getTemperature();
        if (temp != null) {
            if (temp.compareTo(BigDecimal.ZERO) < 0 || temp.compareTo(new BigDecimal("2")) > 0) {
                throw new BusinessException(400, "温度参数必须在 0~2 之间");
            }
            e.setTemperature(temp);
        } else {
            e.setTemperature(null);
        }
        if (req.getIsPublic() != null) e.setPublic(req.getIsPublic());
    }

    private void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(400, "Agent 名称不能为空");
        }
        if (name.length() > 100) {
            throw new BusinessException(400, "Agent 名称过长 (最大 100 字符)");
        }
    }

    private AgentDtos.AgentView toView(AgentDefinitionEntity e, String currentUserId) {
        AgentDtos.AgentView v = new AgentDtos.AgentView();
        v.setId(e.getId());
        v.setUserId(e.getUserId());
        v.setName(e.getName());
        v.setDescription(e.getDescription());
        v.setAvatar(e.getAvatar());
        v.setPersona(e.getSystemPrompt());
        v.setModel(e.getModel());
        v.setTools(parseStrList(e.getTools()));
        v.setKbIds(parseStrList(e.getKbIds()));
        v.setTemperature(e.getTemperature());
        v.setPublic(e.isPublic());
        v.setTemplate(e.isTemplate());
        v.setCreatedAt(e.getCreatedAt());
        v.setUpdatedAt(e.getUpdatedAt());
        // 可编辑: 创建者本人 且 非模板
        v.setEditable(currentUserId != null && currentUserId.equals(e.getUserId()) && !e.isTemplate());
        return v;
    }

    /** 把 List<String> 序列化为 JSON 数组字符串 (null/空统一存 null, 减少存储噪声) */
    private String serializeList(List<String> list) {
        if (list == null || list.isEmpty()) return null;
        try {
            return mapper.writeValueAsString(list);
        } catch (Exception e) {
            return null;
        }
    }

    /** 反序列化列表字符串为 List<String>。
     *  兼容三种存储形态以兼容历史脏数据:
     *   1. 标准 JSON 数组:   ["a", "b"]
     *   2. 裸字符串 (单值):  a
     *   3. 逗号分隔列表:      a, b, c
     */
    private List<String> parseStrList(String raw) {
        if (raw == null || raw.isBlank()) return Collections.emptyList();
        String json = raw.trim();
        // 已是 JSON 数组/对象形态, 走标准反序列化
        if (json.startsWith("[") || json.startsWith("{")) {
            try {
                return mapper.readValue(json, new TypeReference<List<String>>() {});
            } catch (Exception e) {
                log.warn("解析列表 JSON 失败, 尝试按逗号分隔兜底: {}", raw, e);
            }
        }
        // 兜底: 裸字符串按单个元素, 或逗号分隔多元素 (兼容历史脏数据)
        List<String> result = new ArrayList<>();
        for (String part : json.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }
}
