package com.dcdr.agent.controller;

import com.dcdr.agent.auth.SecurityContext;
import com.dcdr.agent.common.Result;
import com.dcdr.agent.dto.RoutingDtos;
import com.dcdr.agent.entity.RoutingLogEntity;
import com.dcdr.agent.repository.RoutingLogRepository;
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
import java.util.List;

/**
 * V3.0 智能路由记录查询接口 (F-30, 对应 V3.0 规划文档 §4.5)。
 * <p>
 * 端点清单:
 * <ul>
 *   <li>GET /api/routing/logs   分页查询路由记录 (用于分析路由准确率与调优置信度阈值)</li>
 * </ul>
 * <p>
 * 权限: 走 {@code /api/routing/**}, SecurityConfig 中归入 {@code /api/** authenticated()} (仅需登录)。
 */
@Slf4j
@RestController
@RequestMapping("/api/routing")
@RequiredArgsConstructor
@Tag(name = "智能路由", description = "V3.0 智能路由记录查询 (F-30)")
public class RoutingController {

    private final RoutingLogRepository routingLogRepository;

    @Operation(summary = "路由记录列表",
            description = "V3.0 F-30: 分页查询当前用户的智能路由记录, 用于分析路由准确率与调优置信度阈值。"
                    + "管理员可查全部 (传 userId=all); 普通用户仅查自己。")
    @GetMapping("/logs")
    public Result<List<RoutingDtos.RoutingLogView>> logs(
            @Parameter(description = "页码 (从 0 开始)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "每页条数") @RequestParam(defaultValue = "20") int size,
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
        List<RoutingDtos.RoutingLogView> views = p.getContent().stream()
                .map(RoutingController::toView).toList();
        return Result.success(views);
    }

    private static RoutingDtos.RoutingLogView toView(RoutingLogEntity e) {
        RoutingDtos.RoutingLogView v = new RoutingDtos.RoutingLogView();
        v.setId(e.getId());
        v.setUserId(e.getUserId());
        v.setSessionId(e.getSessionId());
        v.setUserMessage(e.getUserMessage());
        v.setRouteType(e.getRouteType());
        v.setTargetId(e.getTargetId());
        v.setConfidence(e.getConfidence());
        v.setReason(e.getReason());
        v.setCreatedAt(e.getCreatedAt());
        BigDecimal conf = e.getConfidence();
        v.setHighConfidence(conf != null && conf.compareTo(new BigDecimal("0.6")) >= 0);
        return v;
    }
}
