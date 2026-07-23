package com.dcdr.agent.controller;

import com.dcdr.agent.agent.session.ProjectManager;
import com.dcdr.agent.common.Result;
import com.dcdr.agent.dto.GenerationDTO;
import com.dcdr.agent.dto.ProjectDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
@Tag(name = "项目管理", description = "创作模式的项目管理：创建、查询、删除及生成记录")
public class ProjectController {
    private final ProjectManager projectManager;

    @Operation(summary = "获取项目列表")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ProjectDTO.class)))
    @GetMapping
    public Result<List<ProjectDTO>> list() {
        return Result.success(projectManager.listProjects());
    }

    @Operation(summary = "创建项目", description = "创建创作模式项目 (图片/视频生成)")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ProjectDTO.class)))
    @PostMapping
    public Result<ProjectDTO> create(@RequestBody Map<String, String> body) {
        return Result.success(projectManager.createProject(
                body.get("name"), body.get("mode"), body.get("model")));
    }

    @Operation(summary = "获取项目详情")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ProjectDTO.class)))
    @ApiResponse(responseCode = "404", description = "项目不存在")
    @GetMapping("/{id}")
    public Result<ProjectDTO> get(@Parameter(description = "项目 ID", required = true) @PathVariable String id) {
        ProjectDTO p = projectManager.getProject(id);
        if (p == null) return Result.error(404, "项目不存在");
        return Result.success(p);
    }

    @Operation(summary = "删除项目")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "项目 ID", required = true) @PathVariable String id) {
        projectManager.deleteProject(id);
        return Result.success();
    }

    @Operation(summary = "重命名项目")
    @PutMapping("/{id}")
    public Result<ProjectDTO> rename(@Parameter(description = "项目 ID", required = true) @PathVariable String id,
                                     @RequestBody Map<String, String> body) {
        ProjectDTO p = projectManager.renameProject(id, body.get("name"));
        if (p == null) return Result.error(404, "项目不存在");
        return Result.success(p);
    }

    @Operation(summary = "获取项目生成记录", description = "返回项目下的所有图片/视频生成记录")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = GenerationDTO.class)))
    @GetMapping("/{id}/generations")
    public Result<List<GenerationDTO>> generations(@Parameter(description = "项目 ID", required = true) @PathVariable String id) {
        return Result.success(projectManager.getGenerations(id));
    }
}
