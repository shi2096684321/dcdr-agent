package com.dcdr.agent.controller;

import com.dcdr.agent.auth.ownership.OwnershipGuard;
import com.dcdr.agent.common.Result;
import com.dcdr.agent.dto.SkillDtos;
import com.dcdr.agent.skill.SkillPackageService;
import com.dcdr.agent.skill.SkillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * 技能接口 (V3.1 专家体系)。
 * <p>
 * 端点:
 * <ul>
 *   <li>GET    /api/skills                     可见技能列表(自己的 + 公开的)</li>
 *   <li>GET    /api/skills/{id}                技能详情(含指令正文与资源)</li>
 *   <li>POST   /api/skills                     创建</li>
 *   <li>PUT    /api/skills/{id}                更新</li>
 *   <li>DELETE /api/skills/{id}                删除</li>
 *   <li>POST   /api/skills/{id}/enabled        启用/停用</li>
 *   <li>POST   /api/skills/import              导入技能包</li>
 *   <li>GET    /api/skills/{id}/export         导出技能包</li>
 * </ul>
 * <p>
 * 归属校验统一由 {@link OwnershipGuard} 裁决(越权与不存在一律 404), 与其他资源同口径。
 * 所有写操作都是阻塞 DB 调用, 故统一切到 boundedElastic —— 纯 reactor 线程上跑 JDBC
 * 会占住事件循环线程。
 */
@Slf4j
@RestController
@RequestMapping("/api/skills")
@RequiredArgsConstructor
@Tag(name = "技能", description = "V3.1 专家体系: 按需加载的领域知识包 (技能管理)")
public class SkillController {

    private final SkillService skillService;
    private final SkillPackageService packageService;
    private final OwnershipGuard ownershipGuard;

    @Operation(summary = "技能列表", description = "返回当前用户可见的技能(自己的 + 公开的); admin 返回全部")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SkillDtos.SkillView.class)))
    @GetMapping
    public Mono<Result<List<SkillDtos.SkillView>>> list() {
        return ownershipGuard.currentContext().flatMap(ctx -> Mono
                .fromCallable(() -> Result.success(skillService.list(ctx.userId(), ctx.admin())))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    @Operation(summary = "技能详情", description = "含技能指令正文与参考资源内容")
    @ApiResponse(responseCode = "404", description = "技能不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireRead(#id, 'SKILL', principal)")
    @GetMapping("/{id}")
    public Mono<Result<SkillDtos.SkillView>> get(
            @Parameter(description = "技能 ID", required = true) @PathVariable String id) {
        return ownershipGuard.currentContext().flatMap(ctx -> Mono
                .fromCallable(() -> Result.success(skillService.get(id, ctx.userId(), ctx.admin())))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    @Operation(summary = "创建技能",
            description = "技能标识必须是 kebab-case; 指令正文与参考资源均有大小上限, "
                    + "参考资源仅支持文本格式(技能不携带可执行内容)")
    @PostMapping
    public Mono<Result<SkillDtos.SkillView>> create(@RequestBody SkillDtos.SaveSkillRequest req) {
        return ownershipGuard.currentContext().flatMap(ctx -> Mono
                .fromCallable(() -> Result.success(skillService.create(req, ctx.userId())))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    @Operation(summary = "更新技能")
    @ApiResponse(responseCode = "404", description = "技能不存在或无权修改")
    @PreAuthorize("@ownershipGuard.requireWrite(#id, 'SKILL', principal)")
    @PutMapping("/{id}")
    public Mono<Result<SkillDtos.SkillView>> update(
            @Parameter(description = "技能 ID", required = true) @PathVariable String id,
            @RequestBody SkillDtos.SaveSkillRequest req) {
        return ownershipGuard.currentContext().flatMap(ctx -> Mono
                .fromCallable(() -> Result.success(skillService.update(id, req, ctx.userId(), ctx.admin())))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    @Operation(summary = "删除技能", description = "内置技能不可删除, 只能停用")
    @ApiResponse(responseCode = "404", description = "技能不存在或无权删除")
    @PreAuthorize("@ownershipGuard.requireWrite(#id, 'SKILL', principal)")
    @DeleteMapping("/{id}")
    public Mono<Result<Void>> delete(
            @Parameter(description = "技能 ID", required = true) @PathVariable String id) {
        return ownershipGuard.currentContext().flatMap(ctx -> Mono
                .fromCallable(() -> {
                    skillService.delete(id, ctx.userId(), ctx.admin());
                    return Result.<Void>success(null);
                })
                .subscribeOn(Schedulers.boundedElastic()));
    }

    @Operation(summary = "启用/停用技能", description = "停用后该技能不再进入模型可见的技能目录")
    @PreAuthorize("@ownershipGuard.requireWrite(#id, 'SKILL', principal)")
    @PostMapping("/{id}/enabled")
    public Mono<Result<SkillDtos.SkillView>> setEnabled(
            @Parameter(description = "技能 ID", required = true) @PathVariable String id,
            @Parameter(description = "true=启用, false=停用", required = true) @RequestParam boolean enabled) {
        return ownershipGuard.currentContext().flatMap(ctx -> Mono
                .fromCallable(() -> Result.success(
                        skillService.setEnabled(id, enabled, ctx.userId(), ctx.admin())))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    @Operation(summary = "导入技能包",
            description = "Body 为技能包(见导出接口的产物)。同名技能默认拒绝导入, 需覆盖请传 overwrite=true。"
                    + "依赖工具缺失只返回告警, 不阻断导入")
    @PostMapping("/import")
    public Mono<Result<SkillDtos.ImportResult>> importPackage(@RequestBody SkillDtos.ImportRequest req) {
        return ownershipGuard.currentContext().flatMap(ctx -> Mono
                .fromCallable(() -> Result.success(packageService.importPackage(req, ctx.userId())))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    @Operation(summary = "导出技能包", description = "产出可直接分享/入库的技能包 JSON")
    @ApiResponse(responseCode = "404", description = "技能不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireRead(#id, 'SKILL', principal)")
    @GetMapping("/{id}/export")
    public Mono<Result<SkillDtos.SkillPackage>> exportPackage(
            @Parameter(description = "技能 ID", required = true) @PathVariable String id) {
        return ownershipGuard.currentContext().flatMap(ctx -> Mono
                .fromCallable(() -> Result.success(
                        packageService.exportPackage(id, ctx.userId(), ctx.admin())))
                .subscribeOn(Schedulers.boundedElastic()));
    }
}
