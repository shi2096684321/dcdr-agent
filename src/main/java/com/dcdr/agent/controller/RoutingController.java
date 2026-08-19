package com.dcdr.agent.controller;

import com.dcdr.agent.auth.SecurityContext;
import com.dcdr.agent.common.Result;
import com.dcdr.agent.dto.RoutingDtos;
import com.dcdr.agent.entity.AgentDefinitionEntity;
import com.dcdr.agent.entity.RoutingLogEntity;
import com.dcdr.agent.entity.WorkflowDefinitionEntity;
import com.dcdr.agent.repository.AgentDefinitionRepository;
import com.dcdr.agent.repository.RoutingLogRepository;
import com.dcdr.agent.repository.WorkflowDefinitionRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * V3.0 智能路由记录查询接口 (F-30, 对应 V3.0 规划文档 §4.5)。
 * <p>
 * 端点清单:
 * <ul>
 *   <li>GET /api/routing/logs   分页查询路由记录 (用于分析路由准确率与调优置信度阈值)</li>
 * </ul>
 * <p>
 * 权限: 走 {@code /api/routing/**}, SecurityConfig 中归入 {@code /api/** authenticated()} (仅需登录)。
 * <p>
 * 返回结构为 {@link RoutingDtos.RoutingLogPageView} (分页对象), 与工作流执行历史接口保持一致,
 * 便于前端统一处理分页。
 */
@Slf4j
@RestController
@RequestMapping("/api/routing")
@RequiredArgsConstructor
@Tag(name = "智能路由", description = "V3.0 智能路由记录查询 (F-30)")
public class RoutingController {

    private final RoutingLogRepository routingLogRepository;
    private final WorkflowDefinitionRepository workflowRepository;
    private final AgentDefinitionRepository agentRepository;

    @Operation(summary = "路由记录列表 (分页)",
            description = "V3.0 F-30: 分页查询当前用户的智能路由记录, 用于分析路由准确率与调优置信度阈值。"
                    + "管理员可查全部 (传 userId=all); 普通用户仅查自己。"
                    + "返回分页对象 {content, page, size, totalElements, totalPages}。")
    @GetMapping("/logs")
    public Result<RoutingDtos.RoutingLogPageView> logs(
            @Parameter(description = "页码 (从 0 开始)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "每页条数 (1~100)") @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "查询全部用户 (仅管理员)") @RequestParam(required = false) String userId,
            ServerWebExchange exchange) {
        String currentUserId = SecurityContext.getUserId(exchange);
        boolean isAdmin = SecurityContext.isAdmin(exchange);
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        // 普通用户强制只查自己; 管理员可传 userId=all 查全部
        Page<RoutingLogEntity> p;
        if (isAdmin && "all".equalsIgnoreCase(userId)) {
            p = routingLogRepository.findAllByOrderByCreatedAtDesc(pageable);
        } else {
            p = routingLogRepository.findByUserIdOrderByCreatedAtDesc(currentUserId, pageable);
        }
        // 批量回填 targetName (工作流名 / Agent 名), 避免逐条查库
        Map<String, String> nameCache = new HashMap<>();
        List<RoutingDtos.RoutingLogView> views = p.getContent().stream()
                .map(e -> toView(e, nameCache))
                .toList();
        RoutingDtos.RoutingLogPageView v = new RoutingDtos.RoutingLogPageView();
        v.setContent(views);
        v.setPage(p.getNumber());
        v.setSize(p.getSize());
        v.setTotalElements(p.getTotalElements());
        v.setTotalPages(p.getTotalPages());
        return Result.success(v);
    }

    /** 实体转视图, 顺带回填 targetName */
    private RoutingDtos.RoutingLogView toView(RoutingLogEntity e, Map<String, String> nameCache) {
        RoutingDtos.RoutingLogView v = new RoutingDtos.RoutingLogView();
        v.setId(e.getId());
        v.setUserId(e.getUserId());
        v.setSessionId(e.getSessionId());
        v.setUserMessage(e.getUserMessage());
        v.setRouteType(e.getRouteType());
        v.setTargetId(e.getTargetId());
        v.setTargetName(resolveTargetName(e, nameCache));
        v.setConfidence(e.getConfidence());
        v.setReason(e.getReason());
        v.setCreatedAt(e.getCreatedAt());
        BigDecimal conf = e.getConfidence();
        v.setHighConfidence(conf != null && conf.compareTo(new BigDecimal("0.6")) >= 0);
        return v;
    }

    /** 解析目标名称: workflow → 工作流名; agent → Agent 名; chat → null */
    private String resolveTargetName(RoutingLogEntity e, Map<String, String> nameCache) {
        String targetId = e.getTargetId();
        if (targetId == null || targetId.isBlank()) return null;
        String type = e.getRouteType();
        if ("chat".equalsIgnoreCase(type)) return null;
        // 命中缓存直接返回
        String cacheKey = type + ":" + targetId;
        if (nameCache.containsKey(cacheKey)) return nameCache.get(cacheKey);
        String name = null;
        try {
            if ("workflow".equalsIgnoreCase(type)) {
                name = workflowRepository.findById(targetId)
                        .map(WorkflowDefinitionEntity::getName).orElse(null);
            } else if ("agent".equalsIgnoreCase(type)) {
                name = agentRepository.findById(targetId)
                        .map(AgentDefinitionEntity::getName).orElse(null);
            }
        } catch (Exception ex) {
            log.warn("解析路由目标名称失败: type={}, id={}, err={}", type, targetId, ex.getMessage());
        }
        nameCache.put(cacheKey, name);
        return name;
    }
}
