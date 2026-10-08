package com.dcdr.agent.agent.market;

import com.dcdr.agent.agent.definition.AgentDefinitionService;
import com.dcdr.agent.agent.workflow.WorkflowEngine;
import com.dcdr.agent.auth.SecurityContext;
import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.dto.AgentDtos;
import com.dcdr.agent.dto.TemplateDtos;
import com.dcdr.agent.dto.WorkflowDtos;
import com.dcdr.agent.entity.TemplateShareEntity;
import com.dcdr.agent.repository.TemplateShareRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * V3.0-beta 模板市场 (F-35 Agent 与工作流模板市场, 文档 §9)。
 * <p>
 * 用户将自己的 Agent / 工作流分享到市场 (template_shares), 其他用户可搜索、克隆、评分。
 * 模板来源分两类:
 * <ul>
 *   <li>系统预置: isTemplate=true 的 Agent/工作流 (V2.3 已有), 市场列表合并展示</li>
 *   <li>用户分享: template_shares 记录, 引用已有 Agent/工作流定义, 克隆时复制配置</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateMarketService {

    private final TemplateShareRepository shareRepository;
    private final AgentDefinitionService agentDefinitionService;
    private final WorkflowEngine workflowEngine;

    /** 市场列表: 用户分享 + 系统预置模板合并, 支持类型/分类/关键词筛选与排序 */
    public TemplateDtos.TemplatePageView list(String type, String category, String keyword,
                                              String sort, int page, int size) {
        String t = normalize(type);
        String cat = blankToNull(category);
        String kw = blankToNull(keyword);
        String sortKey = sort == null || sort.isBlank() ? "recent" : sort.toLowerCase();
        Pageable pageable = switch (sortKey) {
            case "clones" -> PageRequest.of(Math.max(page, 0), clampSize(size), Sort.by(Sort.Direction.DESC, "cloneCount"));
            case "rating" -> PageRequest.of(Math.max(page, 0), clampSize(size), Sort.by(Sort.Direction.DESC, "ratingCount"));
            default -> PageRequest.of(Math.max(page, 0), clampSize(size), Sort.by(Sort.Direction.DESC, "createdAt"));
        };

        List<TemplateDtos.TemplateShareView> content = new ArrayList<>();
        long totalUserShares = 0;

        // 用户分享 (按筛选条件查询)
        if (!"builtin".equals(sortKey) || (kw != null || cat != null)) {
            Page<TemplateShareEntity> shares = shareRepository.searchMarket(t, cat, kw, pageable);
            totalUserShares = shares.getTotalElements();
            for (TemplateShareEntity s : shares.getContent()) content.add(toView(s));
        }

        // 系统预置模板 (isTemplate=true; 仅当无筛选或筛选匹配时合并展示)
        if ((kw == null && cat == null && t == null) || kw != null || cat != null || t != null) {
            List<TemplateDtos.TemplateShareView> builtins = new ArrayList<>();
            if (t == null || "agent".equals(t)) {
                for (AgentDtos.AgentView a : agentDefinitionService.listTemplates()) {
                    TemplateDtos.TemplateShareView v = fromAgent(a);
                    if (matches(v, kw, cat)) builtins.add(v);
                }
            }
            if (t == null || "workflow".equals(t)) {
                for (WorkflowDtos.WorkflowView w : workflowEngine.listTemplates()) {
                    TemplateDtos.TemplateShareView v = fromWorkflow(w);
                    if (matches(v, kw, cat)) builtins.add(v);
                }
            }
            // 预置模板按创建时间倒序 (已按此序返回)
            content.addAll(0, builtins);
        }

        TemplateDtos.TemplatePageView v = new TemplateDtos.TemplatePageView();
        v.setContent(content);
        v.setPage(Math.max(page, 0));
        v.setSize(clampSize(size));
        v.setTotalElements(totalUserShares);
        v.setTotalPages((int) ((totalUserShares + clampSize(size) - 1) / clampSize(size)));
        return v;
    }

    /** 分享自己的 Agent / 工作流到市场 */
    @Transactional
    public TemplateDtos.TemplateShareView share(TemplateDtos.ShareRequest req, String userId, boolean isAdmin) {
        String type = normalize(req.getType());
        if (!"agent".equals(type) && !"workflow".equals(type)) {
            throw new BusinessException(400, "分享类型必须为 agent 或 workflow");
        }
        if (req.getTargetId() == null || req.getTargetId().isBlank()) {
            throw new BusinessException(400, "目标 ID 不能为空");
        }
        // 权限: 自己的目标, 或管理员分享任意目标
        if (!isAdmin) {
            if ("agent".equals(type)) {
                AgentDtos.AgentView a = agentDefinitionService.get(req.getTargetId(), userId, false);
                if (!userId.equals(a.getUserId())) {
                    throw new BusinessException(403, "仅可分享自己的 Agent", "FORBIDDEN");
                }
            } else {
                WorkflowDtos.WorkflowView w = workflowEngine.get(req.getTargetId(), userId, false);
                if (!userId.equals(w.getUserId()) && !w.isTemplate()) {
                    throw new BusinessException(403, "仅可分享自己的工作流", "FORBIDDEN");
                }
            }
        }
        // 同一目标重复分享: 恢复上架并更新信息
        TemplateShareEntity e = shareRepository.findByTypeAndTargetId(type, req.getTargetId()).orElse(null);
        if (e == null) {
            e = new TemplateShareEntity();
            e.setId("ts_" + UUID.randomUUID().toString().replace("-", ""));
            e.setType(type);
            e.setTargetId(req.getTargetId());
            e.setUserId(userId);
            e.setCloneCount(0);
            e.setRatingSum(0);
            e.setRatingCount(0);
        } else if (!userId.equals(e.getUserId()) && !isAdmin) {
            throw new BusinessException(403, "该目标已被他人分享", "FORBIDDEN");
        } else {
            e.setEnabled(true);
        }
        // 标题/描述缺省取目标名称
        String title = blankToNull(req.getTitle());
        String desc = blankToNull(req.getDescription());
        if (title == null || desc == null) {
            if ("agent".equals(type)) {
                AgentDtos.AgentView a = agentDefinitionService.get(req.getTargetId(), userId, isAdmin);
                if (title == null) title = a.getName();
                if (desc == null) desc = a.getDescription();
            } else {
                WorkflowDtos.WorkflowView w = workflowEngine.get(req.getTargetId(), userId, isAdmin);
                if (title == null) title = w.getName();
                if (desc == null) desc = w.getDescription();
            }
        }
        e.setTitle(title);
        e.setDescription(desc);
        e.setCategory(blankToNull(req.getCategory()));
        e.setTags(req.getTags());
        // V3.1 F-44.1: 发布即钉住版本号 —— 克隆按该版本还原, 源后续改动不影响已发布内容
        e.setSourceVersion(resolveSourceVersion(type, req.getTargetId(), req.getVersion()));
        e.setCreatedAt(LocalDateTime.now());
        shareRepository.save(e);
        log.info("分享模板到市场: {} (type={}, target={}, version={})",
                e.getId(), type, req.getTargetId(), e.getSourceVersion());
        return toView(e);
    }

    /** 克隆市场模板 (复制 Agent/工作流配置到自己名下) */
    @Transactional
    public TemplateDtos.CloneResult clone(String shareId, String userId) {
        // 预置模板: id 形如 builtin:agent:{id} / builtin:workflow:{id}, 直接克隆目标
        if (shareId != null && shareId.startsWith("builtin:")) {
            String[] parts = shareId.split(":", 3);
            if (parts.length != 3) {
                throw new BusinessException(400, "非法的预置模板 ID: " + shareId);
            }
            // 预置模板无分享记录 → 无钉住版本, 按当前定义克隆
            return cloneTarget(parts[1], parts[2], null, userId);
        }
        TemplateShareEntity e = loadShare(shareId);
        TemplateDtos.CloneResult r = cloneTarget(e.getType(), e.getTargetId(), e.getSourceVersion(), userId);
        e.setCloneCount(e.getCloneCount() + 1);
        shareRepository.save(e);
        log.info("克隆市场模板: {} (type={}, target={}, user={})", shareId, e.getType(), e.getTargetId(), userId);
        return r;
    }

    /**
     * 按类型克隆目标 (Agent / 工作流), 分享模板与预置模板共用。
     *
     * @param version V3.1 F-44.1: 分享时钉住的源版本号; 为空(预置模板 / 历史数据 / 无版本记录)时
     *                按当前定义克隆, 与 V3.0 行为保持一致
     */
    private TemplateDtos.CloneResult cloneTarget(String type, String targetId, Integer version, String userId) {
        TemplateDtos.CloneResult r = new TemplateDtos.CloneResult();
        boolean byVersion = version != null && version > 0;
        if ("agent".equals(type)) {
            AgentDtos.AgentView copy = byVersion
                    ? agentDefinitionService.cloneFromVersion(targetId, version, userId)
                    : agentDefinitionService.clone(targetId, userId, false);
            r.setType("agent");
            r.setNewId(copy.getId());
            r.setNewName(copy.getName());
        } else {
            WorkflowDtos.WorkflowView copy = byVersion
                    ? workflowEngine.cloneFromVersion(targetId, version, userId)
                    : workflowEngine.clone(targetId, userId, false);
            r.setType("workflow");
            r.setNewId(copy.getId());
            r.setNewName(copy.getName());
        }
        return r;
    }

    /**
     * 解析"发布钉住的版本号": 显式传入则校验存在(否则 404), 缺省取当前最新版本。
     * 无任何版本记录时返回 {@code null} —— 兼容本特性上线前已存在的定义(按当前定义克隆)。
     */
    private Integer resolveSourceVersion(String type, String targetId, Integer requested) {
        if ("agent".equals(type)) {
            if (requested != null && !agentDefinitionService.versionExists(targetId, requested)) {
                throw new BusinessException(404, "Agent 版本不存在: v" + requested, "VERSION_NOT_FOUND");
            }
            int latest = agentDefinitionService.latestVersion(targetId);
            return requested != null ? requested : (latest > 0 ? latest : null);
        }
        if (requested != null && !workflowEngine.versionExists(targetId, requested)) {
            throw new BusinessException(404, "工作流版本不存在: v" + requested, "VERSION_NOT_FOUND");
        }
        int latest = workflowEngine.latestVersion(targetId);
        return requested != null ? requested : (latest > 0 ? latest : null);
    }

    /** 评分 (1~5, 累加式平均) */
    @Transactional
    public TemplateDtos.TemplateShareView rate(String shareId, int rating, String userId) {
        if (rating < 1 || rating > 5) {
            throw new BusinessException(400, "评分必须为 1~5");
        }
        TemplateShareEntity e = loadShare(shareId);
        e.setRatingSum(e.getRatingSum() + rating);
        e.setRatingCount(e.getRatingCount() + 1);
        shareRepository.save(e);
        return toView(e);
    }

    /** 下架自己的分享 (管理员可下架任意) */
    @Transactional
    public void unshare(String shareId, String userId, boolean isAdmin) {
        TemplateShareEntity e = loadShare(shareId);
        if (!userId.equals(e.getUserId()) && !isAdmin) {
            throw new BusinessException(403, "仅分享者或管理员可下架", "FORBIDDEN");
        }
        shareRepository.delete(e);
        log.info("下架市场模板: {}", shareId);
    }

    /** 我的分享列表 */
    public List<TemplateDtos.TemplateShareView> myShares(String userId) {
        List<TemplateShareEntity> shares = shareRepository
                .findByUserIdOrderByCreatedAtDesc(userId, Pageable.unpaged()).getContent();
        List<TemplateDtos.TemplateShareView> views = new ArrayList<>(shares.size());
        for (TemplateShareEntity s : shares) views.add(toView(s));
        return views;
    }

    private TemplateShareEntity loadShare(String id) {
        return shareRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "市场模板不存在: " + id, "SHARE_NOT_FOUND"));
    }

    private TemplateDtos.TemplateShareView toView(TemplateShareEntity e) {
        TemplateDtos.TemplateShareView v = new TemplateDtos.TemplateShareView();
        v.setId(e.getId());
        v.setType(e.getType());
        v.setTargetId(e.getTargetId());
        v.setTitle(e.getTitle());
        v.setDescription(e.getDescription());
        v.setCategory(e.getCategory());
        v.setTags(e.getTags() == null || e.getTags().isBlank()
                ? List.of() : Arrays.asList(e.getTags().split("[,，]")));
        v.setOwnerId(e.getUserId());
        v.setSourceVersion(e.getSourceVersion());
        v.setCloneCount(e.getCloneCount());
        v.setRatingAvg(e.getRatingCount() == 0 ? 0
                : Math.round(e.getRatingSum() * 100.0 / e.getRatingCount()) / 100.0);
        v.setRatingCount(e.getRatingCount());
        v.setCreatedAt(e.getCreatedAt());
        return v;
    }

    /** 预置 Agent 模板 → 市场视图 (id 用 target ID, 克隆走既有 /clone 语义) */
    private TemplateDtos.TemplateShareView fromAgent(AgentDtos.AgentView a) {
        TemplateDtos.TemplateShareView v = new TemplateDtos.TemplateShareView();
        v.setId("builtin:agent:" + a.getId());
        v.setType("agent");
        v.setTargetId(a.getId());
        v.setTitle(a.getName());
        v.setDescription(a.getDescription());
        v.setCategory("官方预置");
        v.setTags(List.of("预置"));
        v.setCloneCount(0);
        v.setRatingAvg(0);
        v.setRatingCount(0);
        v.setCreatedAt(a.getCreatedAt());
        return v;
    }

    /** 预置工作流模板 → 市场视图 */
    private TemplateDtos.TemplateShareView fromWorkflow(WorkflowDtos.WorkflowView w) {
        TemplateDtos.TemplateShareView v = new TemplateDtos.TemplateShareView();
        v.setId("builtin:workflow:" + w.getId());
        v.setType("workflow");
        v.setTargetId(w.getId());
        v.setTitle(w.getName());
        v.setDescription(w.getDescription());
        v.setCategory("官方预置");
        v.setTags(List.of("预置"));
        v.setCloneCount(0);
        v.setRatingAvg(0);
        v.setRatingCount(0);
        v.setCreatedAt(w.getCreatedAt());
        return v;
    }

    private static boolean matches(TemplateDtos.TemplateShareView v, String kw, String cat) {
        if (cat != null && !cat.equals(v.getCategory())) return false;
        if (kw == null) return true;
        String k = kw.toLowerCase();
        return (v.getTitle() != null && v.getTitle().toLowerCase().contains(k))
                || (v.getDescription() != null && v.getDescription().toLowerCase().contains(k));
    }

    private static String normalize(String s) {
        return s == null ? null : s.trim().toLowerCase();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static int clampSize(int size) {
        return Math.min(Math.max(size, 1), 50);
    }
}
