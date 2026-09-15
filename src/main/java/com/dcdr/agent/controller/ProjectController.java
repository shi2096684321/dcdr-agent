package com.dcdr.agent.controller;

import com.dcdr.agent.agent.session.ProjectManager;
import com.dcdr.agent.auth.ownership.OwnershipGuard;
import com.dcdr.agent.common.Result;
import com.dcdr.agent.dto.GenerationPageDTO;
import com.dcdr.agent.dto.ProjectDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 创作项目管理接口。
 * <p>
 * V3.1 F-41: 涉及具体项目的端点用 {@code @PreAuthorize} + {@code OwnershipGuard.requireRead/requireWrite}
 * 声明式校验归属(当前用户由 SpEL 变量 {@code principal} 提供, 无需任何方法参数),
 * 越权与不存在统一返回 404。
 * <p>
 * 注: 被保护方法必须返回 {@code Mono}({@code @EnableReactiveMethodSecurity} 的限制), 对外 JSON 契约不变。
 */
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
@Tag(name = "项目管理", description = "创作模式的项目管理：创建、查询、删除及生成记录")
public class ProjectController {

    private final ProjectManager projectManager;
    /** V3.1 F-41: 响应式读取当前登录用户(列表/创建需写入归属, 但无需声明参数) */
    private final OwnershipGuard ownershipGuard;

    @Operation(summary = "获取项目列表")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ProjectDTO.class)))
    @GetMapping
    public Mono<Result<List<ProjectDTO>>> list() {
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(projectManager.listProjects(ctx.userId(), ctx.admin())));
    }

    @Operation(summary = "创建项目", description = "创建创作模式项目 (图片/视频生成)")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ProjectDTO.class)))
    @PostMapping
    public Mono<Result<ProjectDTO>> create(@RequestBody Map<String, String> body) {
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(projectManager.createProject(
                        body.get("name"), body.get("mode"), body.get("model"), ctx.userId())));
    }

    @Operation(summary = "获取项目详情")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ProjectDTO.class)))
    @ApiResponse(responseCode = "404", description = "项目不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireRead(#id, 'PROJECT', principal)")
    @GetMapping("/{id}")
    public Mono<Result<ProjectDTO>> get(@Parameter(description = "项目 ID", required = true) @PathVariable String id) {
        ProjectDTO p = projectManager.getProject(id);
        if (p == null) return Mono.just(Result.error(404, "项目不存在"));
        return Mono.just(Result.success(p));
    }

    @Operation(summary = "删除项目")
    @ApiResponse(responseCode = "404", description = "项目不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireWrite(#id, 'PROJECT', principal)")
    @DeleteMapping("/{id}")
    public Mono<Result<Void>> delete(@Parameter(description = "项目 ID", required = true) @PathVariable String id) {
        projectManager.deleteProject(id);
        return Mono.just(Result.success());
    }

    @Operation(summary = "重命名项目")
    @ApiResponse(responseCode = "404", description = "项目不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireWrite(#id, 'PROJECT', principal)")
    @PutMapping("/{id}")
    public Mono<Result<ProjectDTO>> rename(@Parameter(description = "项目 ID", required = true) @PathVariable String id,
                                           @RequestBody Map<String, String> body) {
        ProjectDTO p = projectManager.renameProject(id, body.get("name"));
        if (p == null) return Mono.just(Result.error(404, "项目不存在"));
        return Mono.just(Result.success(p));
    }

    @Operation(summary = "获取项目生成记录", description = "分页返回项目下的图片/视频生成记录, 可按 type (image/video) 过滤")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = GenerationPageDTO.class)))
    @ApiResponse(responseCode = "404", description = "项目不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireRead(#id, 'PROJECT', principal)")
    @GetMapping("/{id}/generations")
    public Mono<Result<GenerationPageDTO>> generations(
            @Parameter(description = "项目 ID", required = true) @PathVariable String id,
            @Parameter(description = "生成类型过滤: image / video, 不传表示全部") @RequestParam(required = false) String type,
            @Parameter(description = "页码 (从 1 开始)") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页大小") @RequestParam(defaultValue = "20") int size) {
        return Mono.just(Result.success(projectManager.getGenerationsPage(id, type, page, size)));
    }
}
