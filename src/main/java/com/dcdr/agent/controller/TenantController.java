package com.dcdr.agent.controller;

import com.dcdr.agent.agent.tenant.TenantService;
import com.dcdr.agent.common.Result;
import com.dcdr.agent.dto.TenantDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * V3.0-rc 租户管理接口 (F-36 多租户 SaaS 化, 文档 §17.6)。
 * <p>
 * 端点清单 (均需 user:manage 权限, 由 SecurityConfig 的 /api/tenants 规则控制):
 * <ul>
 *   <li>POST /api/tenants                     创建租户</li>
 *   <li>GET  /api/tenants                     租户列表</li>
 *   <li>GET  /api/tenants/{id}                租户详情</li>
 *   <li>PUT  /api/tenants/{id}                修改套餐/限额/状态</li>
 *   <li>GET  /api/tenants/{id}/usage          租户资源使用情况</li>
 *   <li>POST /api/tenants/assign              用户归属租户</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/tenants")
@RequiredArgsConstructor
@Tag(name = "租户管理", description = "V3.0-rc F-36 多租户 SaaS 化 (仅管理员)")
public class TenantController {

    private final TenantService tenantService;

    @Operation(summary = "创建租户", description = "创建租户并设定套餐与资源限额 (free/pro/enterprise)。")
    @PostMapping
    public Result<TenantDtos.TenantView> create(@RequestBody TenantDtos.SaveTenantRequest req) {
        return Result.success(tenantService.create(req));
    }

    @Operation(summary = "租户列表")
    @GetMapping
    public Result<List<TenantDtos.TenantView>> list() {
        return Result.success(tenantService.list());
    }

    @Operation(summary = "租户详情")
    @GetMapping("/{id}")
    public Result<TenantDtos.TenantView> get(@PathVariable String id) {
        return Result.success(tenantService.get(id));
    }

    @Operation(summary = "修改租户", description = "更新套餐/资源限额/状态; suspended 状态下租户用户无法创建资源。")
    @PutMapping("/{id}")
    public Result<TenantDtos.TenantView> update(@PathVariable String id,
                                                @RequestBody TenantDtos.SaveTenantRequest req) {
        return Result.success(tenantService.update(id, req));
    }

    @Operation(summary = "租户资源使用情况", description = "用户数/Agent数/工作流数/月 token 已用量与限额。")
    @GetMapping("/{id}/usage")
    public Result<TenantDtos.TenantUsage> usage(@PathVariable String id) {
        return Result.success(tenantService.usage(id));
    }

    @Operation(summary = "用户归属租户", description = "把用户划入/移出租户; tenantId 传 null 移出租户。受租户用户数上限约束。")
    @PostMapping("/assign")
    public Result<Void> assign(@RequestBody TenantDtos.AssignUserRequest req) {
        tenantService.assignUser(req);
        return Result.success();
    }
}
