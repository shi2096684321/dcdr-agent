package com.dcdr.agent.agent.definition;

import com.dcdr.agent.agent.memory.longterm.LongTermMemoryService;
import com.dcdr.agent.agent.tenant.TenantService;
import com.dcdr.agent.agent.version.AgentVersionDiffer;
import com.dcdr.agent.auth.ownership.OwnershipGuard;
import com.dcdr.agent.auth.ownership.ResourceType;
import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.dto.AgentDtos;
import com.dcdr.agent.dto.VersionDiffDtos;
import com.dcdr.agent.entity.AgentDefinitionEntity;
import com.dcdr.agent.entity.AgentDefinitionVersionEntity;
import com.dcdr.agent.repository.AgentDefinitionRepository;
import com.dcdr.agent.repository.AgentDefinitionVersionRepository;
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
import java.util.Set;
import java.util.UUID;

/**
 * V2.3 自定义 Agent 管理 Service (F-24)。
 * <p>
 * 提供 Agent 的 CRUD 与预设模板库:
 * <ul>
 *   <li>用户可创建/更新/删除自己的 Agent</li>
 *   <li>管理员可更新/删除<b>任意用户</b>的 Agent (含他人私有 Agent 与预设模板),
 *       由 {@link com.dcdr.agent.auth.ownership.OwnershipGuard} 的管理员放行规则统一裁决</li>
 *   <li>预设模板 (isTemplate=true) 对所有用户可见且不可删除, 普通用户仅可克隆</li>
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
    /** V3.1 F-44.1: 版本快照仓库 (对应工作流侧的 WorkflowVersionRepository) */
    private final AgentDefinitionVersionRepository versionRepository;
    private final TenantService tenantService;
    /** V3.1 F-41: 归属判定统一收敛到 OwnershipGuard (可见性口径见 AgentOwnershipProvider) */
    private final OwnershipGuard ownershipGuard;
    /**
     * V3.1: 删除专家时一并归档它的专属记忆 —— 否则 {@code long_term_memories.agentId} 会悬空。
     * <p>这里注入 Service 而不是 Repository: "归档"的语义(保留数据、可恢复、记日志)归记忆模块
     * 自己决定, 删除专家的一方只需表达"这个专家没了"。
     */
    private final LongTermMemoryService longTermMemoryService;
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
            views.add(toView(e, userId, isAdmin));
        }
        return views;
    }

    /** 仅预设模板 (模板库, 只读视图: 模板一律通过「克隆为我的 Agent」使用, 故 editable 恒为 false) */
    public List<AgentDtos.AgentView> listTemplates() {
        List<AgentDefinitionEntity> entities = repository.findByIsTemplateTrueOrderByCreatedAtDesc();
        List<AgentDtos.AgentView> views = new ArrayList<>(entities.size());
        for (AgentDefinitionEntity e : entities) {
            views.add(toView(e, null, false));
        }
        return views;
    }

    /** 详情 (归属校验: 创建者 / 公开 / 模板 / admin 可读, 越权统一返回 404) */
    public AgentDtos.AgentView get(String id, String userId, boolean isAdmin) {
        ownershipGuard.check(ResourceType.AGENT, id, userId, isAdmin, false);
        return toView(loadEntity(id), userId, isAdmin);
    }

    /** 内部加载实体 (供 Orchestrator/WorkflowEngine 调用, 不做可见性校验) */
    public AgentDefinitionEntity loadEntity(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "Agent 不存在: " + id, "AGENT_NOT_FOUND"));
    }

    @Transactional
    public AgentDtos.AgentView create(AgentDtos.SaveAgentRequest req, String userId, boolean isAdmin) {
        validateName(req.getName());
        validateTools(req.getTools(), isAdmin);
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
        // V3.1 F-44.1 版本管理: 创建即生成 v1 快照 (与 WorkflowEngine.create 同口径)
        snapshotVersion(e, userId, req.getChangeNote() == null || req.getChangeNote().isBlank()
                ? "初始版本" : req.getChangeNote());
        log.info("创建 Agent: {} (name={}, user={}, template={})", e.getId(), e.getName(), userId, asTemplate);
        return toView(e, userId, isAdmin);
    }

    @Transactional
    public AgentDtos.AgentView update(String id, AgentDtos.SaveAgentRequest req, String userId, boolean isAdmin) {
        // 归属校验(写): 模板仅 admin; 非模板仅创建者; admin 全部放行
        ownershipGuard.check(ResourceType.AGENT, id, userId, isAdmin, true);
        AgentDefinitionEntity e = loadEntity(id);
        validateName(req.getName());
        validateTools(req.getTools(), isAdmin);
        applyRequest(e, req);
        // 管理员可在更新时切换模板标记 (普通用户不允许, 上面已拦截)
        if (isAdmin && req.getIsTemplate() != null) {
            e.setTemplate(req.getIsTemplate());
        }
        e.setUpdatedAt(LocalDateTime.now());
        repository.save(e);
        // V3.1 F-44.1: 每次修改生成新版本快照, 可回滚到任意历史版本
        snapshotVersion(e, userId, req.getChangeNote() == null || req.getChangeNote().isBlank()
                ? null : req.getChangeNote());
        log.info("更新 Agent: {} (name={}, template={}, operator={}, owner={})",
                e.getId(), e.getName(), e.isTemplate(), userId, e.getUserId());
        return toView(e, userId, isAdmin);
    }

    @Transactional
    public void delete(String id, String userId, boolean isAdmin) {
        // 归属校验(写): 模板仅 admin; 非模板仅创建者; admin 全部放行
        ownershipGuard.check(ResourceType.AGENT, id, userId, isAdmin, true);
        AgentDefinitionEntity e = loadEntity(id);
        // 先归档专属记忆, 再删专家。顺序本身不影响结果(记忆按 agentId 查, 与专家是否还在库无关),
        // 但放在同一事务里才能保证"专家没了"与"它的记忆不再注入"一起生效或一起回滚
        int archived = longTermMemoryService.archiveByAgent(e.getId());
        repository.delete(e);
        log.info("删除 Agent: {} (name={}, template={}, 归档专属记忆 {} 条)",
                e.getId(), e.getName(), e.isTemplate(), archived);
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
        // 克隆产物始终是普通 Agent(下方 create 传 isAdmin=false), 故按非管理员口径过滤受限工具
        req.setTools(filterAdminOnlyTools(parseStrList(src.getTools()), false));
        req.setKbIds(parseStrList(src.getKbIds()));
        req.setTemperature(src.getTemperature());
        // 专家元数据随克隆一起带走: 克隆出来的还是一个"同款专家", 丢掉分类与标签会让它在市场里
        // 退化成"无名角色"(这也是"克隆内置模板再改"这条主路径的前提)
        req.setProfession(src.getProfession());
        req.setCategoryId(src.getCategoryId());
        req.setTags(parseStrList(src.getTags()));
        req.setQuickPrompts(parseStrList(src.getQuickPrompts()));
        req.setIsPublic(false);
        req.setIsTemplate(false); // 克隆出来的始终是普通 Agent
        return create(req, userId, false);
    }

    // ===== V3.1 F-44.1 版本管理 (口径与 WorkflowEngine 对齐) =====

    /** 版本历史列表 (归属校验: 读; 越权与不存在统一 404, 由 OwnershipGuard 裁决) */
    public List<AgentDtos.VersionView> listVersions(String agentId, String userId, boolean isAdmin) {
        ownershipGuard.check(ResourceType.AGENT, agentId, userId, isAdmin, false);
        return toVersionViews(versionRepository.findByAgentIdOrderByVersionDesc(agentId));
    }

    /**
     * 回滚到指定历史版本。
     * <p>回滚动作本身也会生成新版本快照, 因此历史不被覆盖、可继续回滚。
     * {@code isTemplate} 属管理员标记, 故回滚与更新同口径受写权限约束(模板仅 admin 可写)。
     */
    @Transactional
    public AgentDtos.AgentView rollback(String agentId, int version, String userId, boolean isAdmin) {
        ownershipGuard.check(ResourceType.AGENT, agentId, userId, isAdmin, true);
        AgentDefinitionEntity e = loadEntity(agentId);
        AgentDefinitionVersionEntity snapshot = requireVersion(agentId, version);
        applySnapshot(e, snapshot);
        e.setUpdatedAt(LocalDateTime.now());
        repository.save(e);
        snapshotVersion(e, userId, "回滚到 v" + version);
        log.info("回滚 Agent: {} 到 v{} (operator={})", agentId, version, userId);
        return toView(e, userId, isAdmin);
    }

    /** 结构化对比两个历史版本 (归属校验: 读) */
    public VersionDiffDtos.View diff(String agentId, int leftVersion, int rightVersion,
                                     String userId, boolean isAdmin) {
        ownershipGuard.check(ResourceType.AGENT, agentId, userId, isAdmin, false);
        AgentDefinitionVersionEntity left = requireVersion(agentId, leftVersion);
        AgentDefinitionVersionEntity right = requireVersion(agentId, rightVersion);
        VersionDiffDtos.View view = new VersionDiffDtos.View();
        view.setResourceType("agent");
        view.setResourceId(agentId);
        view.setLeftVersion(leftVersion);
        view.setRightVersion(rightVersion);
        view.setLeftCreatedAt(left.getCreatedAt());
        view.setRightCreatedAt(right.getCreatedAt());
        view.setFields(AgentVersionDiffer.diff(left, right));
        return view;
    }

    /**
     * 按指定版本快照克隆为新 Agent (F-44.1 模板市场: 发布时钉住版本, 克隆按该版本还原)。
     * <p>回填快照内容后复用 {@link #create}, 模板市场无需了解版本表结构。
     */
    @Transactional
    public AgentDtos.AgentView cloneFromVersion(String agentId, int version, String userId) {
        AgentDefinitionVersionEntity snapshot = requireVersion(agentId, version);
        AgentDtos.SaveAgentRequest req = new AgentDtos.SaveAgentRequest();
        // 命名与 clone() 保持一致; 名称取该版本快照内的值(而非当前名称)
        req.setName(snapshot.getName() + " (副本)");
        req.setDescription(snapshot.getDescription());
        req.setAvatar(snapshot.getAvatar());
        req.setPersona(snapshot.getSystemPrompt());
        req.setModel(snapshot.getModel());
        // 克隆产物始终是普通 Agent(下方 create 传 isAdmin=false), 故按非管理员口径过滤受限工具
        req.setTools(filterAdminOnlyTools(parseStrList(snapshot.getTools()), false));
        req.setKbIds(parseStrList(snapshot.getKbIds()));
        req.setTemperature(snapshot.getTemperature());
        // 同上: 从历史版本克隆也要带走元数据(版本快照已含这些列)
        req.setProfession(snapshot.getProfession());
        req.setCategoryId(snapshot.getCategoryId());
        req.setTags(parseStrList(snapshot.getTags()));
        req.setQuickPrompts(parseStrList(snapshot.getQuickPrompts()));
        req.setIsPublic(false);
        req.setIsTemplate(false);
        req.setChangeNote("克隆自 v" + version);
        return create(req, userId, false);
    }

    /** 当前最新版本号 (无版本时返回 0) */
    public int latestVersion(String agentId) {
        return versionRepository.findFirstByAgentIdOrderByVersionDesc(agentId)
                .map(AgentDefinitionVersionEntity::getVersion).orElse(0);
    }

    /** 版本号是否存在 (模板市场分享时校验用) */
    public boolean versionExists(String agentId, int version) {
        return versionRepository.findByAgentIdAndVersion(agentId, version).isPresent();
    }

    /** 保存当前定义为新版本快照 (version = max + 1) */
    private void snapshotVersion(AgentDefinitionEntity e, String userId, String changeNote) {
        try {
            int next = versionRepository.findFirstByAgentIdOrderByVersionDesc(e.getId())
                    .map(AgentDefinitionVersionEntity::getVersion).orElse(0) + 1;
            AgentDefinitionVersionEntity v = new AgentDefinitionVersionEntity();
            v.setId("adv_" + UUID.randomUUID().toString().replace("-", ""));
            v.setAgentId(e.getId());
            v.setVersion(next);
            v.setName(e.getName());
            v.setDescription(e.getDescription());
            v.setAvatar(e.getAvatar());
            v.setProfession(e.getProfession());
            v.setCategoryId(e.getCategoryId());
            v.setTags(e.getTags());
            v.setQuickPrompts(e.getQuickPrompts());
            v.setSystemPrompt(e.getSystemPrompt());
            v.setModel(e.getModel());
            v.setTools(e.getTools());
            v.setKbIds(e.getKbIds());
            v.setTemperature(e.getTemperature());
            v.setPublic(e.isPublic());
            v.setTemplate(e.isTemplate());
            v.setChangeNote(changeNote);
            v.setCreatedBy(userId);
            v.setCreatedAt(LocalDateTime.now());
            versionRepository.save(v);
        } catch (Exception ex) {
            // 版本快照失败不阻断主流程(与 WorkflowEngine.snapshotVersion 同策略)
            log.warn("Agent 版本快照失败(不影响保存): {}", ex.getMessage());
        }
    }

    /** 用快照内容覆盖定义实体 (不含 userId: 归属不随版本变化) */
    private void applySnapshot(AgentDefinitionEntity e, AgentDefinitionVersionEntity snapshot) {
        e.setName(snapshot.getName());
        e.setDescription(snapshot.getDescription());
        e.setAvatar(snapshot.getAvatar());
        e.setProfession(snapshot.getProfession());
        e.setCategoryId(snapshot.getCategoryId());
        e.setTags(snapshot.getTags());
        e.setQuickPrompts(snapshot.getQuickPrompts());
        e.setSystemPrompt(snapshot.getSystemPrompt());
        e.setModel(snapshot.getModel());
        e.setTools(snapshot.getTools());
        e.setKbIds(snapshot.getKbIds());
        e.setTemperature(snapshot.getTemperature());
        e.setPublic(snapshot.isPublic());
        e.setTemplate(snapshot.isTemplate());
    }

    private AgentDefinitionVersionEntity requireVersion(String agentId, int version) {
        return versionRepository.findByAgentIdAndVersion(agentId, version)
                .orElseThrow(() -> new BusinessException(404, "版本不存在: v" + version, "VERSION_NOT_FOUND"));
    }

    private List<AgentDtos.VersionView> toVersionViews(List<AgentDefinitionVersionEntity> versions) {
        List<AgentDtos.VersionView> views = new ArrayList<>(versions.size());
        for (AgentDefinitionVersionEntity v : versions) {
            AgentDtos.VersionView view = new AgentDtos.VersionView();
            view.setId(v.getId());
            view.setAgentId(v.getAgentId());
            view.setVersion(v.getVersion());
            view.setName(v.getName());
            view.setDescription(v.getDescription());
            view.setChangeNote(v.getChangeNote());
            view.setCreatedBy(v.getCreatedBy());
            view.setCreatedAt(v.getCreatedAt());
            views.add(view);
        }
        return views;
    }

    // ===== 内部辅助 =====

    private void applyRequest(AgentDefinitionEntity e, AgentDtos.SaveAgentRequest req) {
        e.setName(req.getName().trim());
        e.setDescription(req.getDescription());
        e.setAvatar(req.getAvatar());
        // V3.1 专家元数据: 这些字段决定"专家能否被找到" —— 分类/标签为空时市场里就只能靠名字搜,
        // 故与其它定义字段走同一套序列化(JSON 数组)与同一套空值规整
        e.setProfession(trimToNull(req.getProfession()));
        e.setCategoryId(trimToNull(req.getCategoryId()));
        e.setTags(serializeList(req.getTags()));
        e.setQuickPrompts(serializeList(req.getQuickPrompts()));
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

    /**
     * 受限工具: <b>仅管理员</b>可写入 Agent 白名单。
     * <p>
     * 背景: 「沙箱默认关闭, 仅管理员可启用」是 V3.1 F-43 安全设计的一部分
     * (见 {@code agent-tools.sandbox.enabled})。而 Agent 白名单
     * ({@code AgentDefinition.tools}) 是"工具对模型可见"的最后一道闸 ——
     * 前端选择框允许自由输入工具名, 若此处不校验, 普通用户写入 {@code code_interpreter}
     * 即可绕过全局开关直接让模型调用沙箱。
     */
    private static final Set<String> ADMIN_ONLY_TOOLS = Set.of("code_interpreter");

    /** 校验工具白名单: 受限工具仅管理员可启用(非管理员写入直接 403) */
    private void validateTools(List<String> tools, boolean isAdmin) {
        if (isAdmin || tools == null || tools.isEmpty()) {
            return;
        }
        for (String tool : tools) {
            if (tool != null && ADMIN_ONLY_TOOLS.contains(tool.trim())) {
                throw new BusinessException(403, "仅管理员可启用工具: " + tool.trim(), "FORBIDDEN");
            }
        }
    }

    /**
     * 过滤受限工具(克隆 / AI 生成场景共用): 普通用户接触含受限工具的配置
     * (克隆管理员模板、或模型生成时"自作主张"选了沙箱)时<b>静默剔除</b>而非整体失败 ——
     * 这两种场景对用户都是"我并没有主动要求这个工具", 直接报错只会让人莫名其妙。
     * <p>
     * 对外可见是为了让 {@code AgentDraftGenerator} 复用同一份受限清单: 该清单是安全边界,
     * 两处各抄一份的话, 将来新增受限工具必然漏掉一边。
     */
    public List<String> filterAdminOnlyTools(List<String> tools, boolean isAdmin) {
        if (isAdmin || tools == null || tools.isEmpty()) {
            return tools;
        }
        return tools.stream()
                .filter(tool -> tool == null || !ADMIN_ONLY_TOOLS.contains(tool.trim()))
                .toList();
    }

    /** 去空白并归一空值为 null(避免库里出现 "" 与 null 两种空表示) */
    private static String trimToNull(String s) {
        if (s == null || s.isBlank()) return null;
        return s.trim();
    }

    private void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(400, "Agent 名称不能为空");
        }
        if (name.length() > 100) {
            throw new BusinessException(400, "Agent 名称过长 (最大 100 字符)");
        }
    }

    private AgentDtos.AgentView toView(AgentDefinitionEntity e, String currentUserId, boolean isAdmin) {
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
        v.setProfession(e.getProfession());
        v.setCategoryId(e.getCategoryId());
        v.setTags(parseStrList(e.getTags()));
        v.setQuickPrompts(parseStrList(e.getQuickPrompts()));
        v.setCreatedAt(e.getCreatedAt());
        v.setUpdatedAt(e.getUpdatedAt());
        // 可编辑: 管理员可编辑任意 Agent (含他人私有 Agent 与预设模板); 普通用户仅限自己创建的非模板 Agent
        v.setEditable(isAdmin
                || (currentUserId != null && currentUserId.equals(e.getUserId()) && !e.isTemplate()));
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
