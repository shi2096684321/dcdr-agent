package com.dcdr.agent.controller;

import com.dcdr.agent.auth.ownership.OwnershipGuard;
import com.dcdr.agent.common.Result;
import com.dcdr.agent.dto.UserModelConfigDTO;
import com.dcdr.agent.service.UserModelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * V3.1 用户自定义模型管理接口。
 * <p>
 * 用户自行配置 OpenAI 兼容 LLM(base-url/api-key/model), apiKey 加密落库、对外不回显。
 * <p>
 * V3.1 F-41: 涉及具体配置的端点用 {@code @PreAuthorize} 声明式校验归属
 * (当前用户由 SpEL 变量 {@code principal} 提供, <b>无需</b>声明 {@code ServerWebExchange} 参数),
 * 越权与不存在统一返回 404。该资源含 apiKey 密文, 隔离要求最高。
 */
@RestController
@RequestMapping("/api/user-models")
@RequiredArgsConstructor
@Tag(name = "用户自定义模型", description = "V3.1: 用户自行配置 OpenAI 兼容 LLM 的增删改查")
public class UserModelController {

    private final UserModelService userModelService;
    /** V3.1 F-41: 响应式读取当前登录用户 + 归属校验 */
    private final OwnershipGuard ownershipGuard;

    @Operation(summary = "我的自定义模型列表", description = "返回当前用户所有自定义模型 (apiKey 恒为 null)")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = UserModelConfigDTO.class)))
    @GetMapping
    public Mono<Result<List<UserModelConfigDTO>>> list() {
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(userModelService.listConfigs(ctx.userId(), ctx.admin())));
    }

    @Operation(summary = "新增自定义模型", description = "apiKey 必填, 加密落库")
    @PostMapping
    public Mono<Result<UserModelConfigDTO>> create(@RequestBody UserModelConfigDTO dto) {
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(userModelService.create(ctx.userId(), dto)));
    }

    @Operation(summary = "更新自定义模型", description = "apiKey 留空表示保留原值")
    @ApiResponse(responseCode = "404", description = "模型配置不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireWrite(#id, 'USER_MODEL', principal)")
    @PutMapping("/{id}")
    public Mono<Result<UserModelConfigDTO>> update(@PathVariable String id,
                                                   @RequestBody UserModelConfigDTO dto) {
        return ownershipGuard.currentContext()
                .map(ctx -> Result.success(userModelService.update(ctx.userId(), id, dto, ctx.admin())));
    }

    @Operation(summary = "删除自定义模型")
    @ApiResponse(responseCode = "404", description = "模型配置不存在或无权访问")
    @PreAuthorize("@ownershipGuard.requireWrite(#id, 'USER_MODEL', principal)")
    @DeleteMapping("/{id}")
    public Mono<Result<Void>> delete(@PathVariable String id) {
        return ownershipGuard.currentContext().map(ctx -> {
            userModelService.delete(ctx.userId(), id, ctx.admin());
            return Result.success();
        });
    }
}
