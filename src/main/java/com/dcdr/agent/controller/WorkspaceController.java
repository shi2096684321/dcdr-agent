package com.dcdr.agent.controller;

import com.dcdr.agent.auth.ownership.OwnershipGuard;
import com.dcdr.agent.common.Result;
import com.dcdr.agent.dto.WorkspaceDTO;
import com.dcdr.agent.workspace.WorkspaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 工作区接口(V3.3 F-49)。
 * <p>
 * 归属校验用 {@link OwnershipGuard#currentContext()} 显式取当前用户, 不依赖参数传入
 * (被保护方法必须返回 {@code Mono}, 这是 {@code @EnableReactiveMethodSecurity} 的限制,
 * 与 {@code ProjectController} 保持同一写法)。
 * <p>
 * <b>三处文案必须写死</b>:
 * <ol>
 *   <li>删除工作区只解除关联, 不删磁盘文件</li>
 *   <li>升到 FULL 档等于把删除权限交给模型(删除会先进回收区, 可撤销)</li>
 *   <li>降档立即生效, 当前会话里已注册的写/删工具在下一轮对话重建工具列表时消失</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/workspaces")
@RequiredArgsConstructor
@Tag(name = "工作区", description = "本地工作区管理：选择目录、授权档位、目录浏览")
public class WorkspaceController {

    private final WorkspaceService workspaceService;
    private final OwnershipGuard ownershipGuard;

    @Operation(summary = "工作区列表", description = "管理员返回全部, 普通用户仅自己的")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = WorkspaceDTO.class)))
    @GetMapping
    public Mono<Result<List<WorkspaceDTO>>> list() {
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(workspaceService.list(ctx.userId(), ctx.admin())));
    }

    @Operation(summary = "创建工作区",
            description = "把一个目录授权给模型。accessLevel: READ(默认) / WRITE / FULL")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = WorkspaceDTO.class)))
    @PostMapping
    public Mono<Result<WorkspaceDTO>> create(@RequestBody Map<String, String> body) {
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(workspaceService.create(
                        ctx.userId(), body.get("name"), body.get("rootPath"), body.get("accessLevel"))));
    }

    @Operation(summary = "获取工作区详情")
    @ApiResponse(responseCode = "404", description = "工作区不存在或无权访问")
    @GetMapping("/{id}")
    public Mono<Result<WorkspaceDTO>> get(
            @Parameter(description = "工作区 ID", required = true) @PathVariable String id) {
        return ownershipGuard.currentContext().map(ctx -> {
            workspaceService.requireOwned(id, ctx.userId(), ctx.admin());
            return Result.success(workspaceService.get(id));
        });
    }

    @Operation(summary = "修改工作区", description = "改名或升降档位; 降档立即生效")
    @ApiResponse(responseCode = "404", description = "工作区不存在或无权访问")
    @PatchMapping("/{id}")
    public Mono<Result<WorkspaceDTO>> update(
            @Parameter(description = "工作区 ID", required = true) @PathVariable String id,
            @RequestBody Map<String, String> body) {
        return ownershipGuard.currentContext().map(ctx -> {
            workspaceService.requireOwned(id, ctx.userId(), ctx.admin());
            return Result.success(workspaceService.update(id, body.get("name"), body.get("accessLevel")));
        });
    }

    @Operation(summary = "删除工作区",
            description = "只解除关联并删除记录, 不会删除磁盘上的任何文件")
    @ApiResponse(responseCode = "404", description = "工作区不存在或无权访问")
    @DeleteMapping("/{id}")
    public Mono<Result<Void>> delete(
            @Parameter(description = "工作区 ID", required = true) @PathVariable String id) {
        return ownershipGuard.currentContext().map(ctx -> {
            workspaceService.requireOwned(id, ctx.userId(), ctx.admin());
            workspaceService.delete(id);
            return Result.<Void>success();
        });
    }

    @Operation(summary = "目录浏览",
            description = "返回当前目录的子目录 + 上一级 + 本机盘符列表, 供前端逐级下钻选工作区根。"
                    + "仅管理员可用, 只返回目录不返回文件; 服务器部署形态整体禁用")
    @GetMapping("/browse")
    public Mono<Result<Map<String, Object>>> browse(
            @Parameter(description = "要展开的目录绝对路径, 不传则从用户主目录开始")
            @RequestParam(required = false) String path) {
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(workspaceService.browse(path, ctx.admin())));
    }
}
