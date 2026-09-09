package com.dcdr.agent.controller;

import com.dcdr.agent.auth.SecurityContext;
import com.dcdr.agent.common.Result;
import com.dcdr.agent.dto.UserModelConfigDTO;
import com.dcdr.agent.service.UserModelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;

/**
 * V3.1 用户自定义模型管理接口。
 * <p>
 * 用户自行配置 OpenAI 兼容 LLM(base-url/api-key/model), apiKey 加密落库、对外不回显。
 */
@RestController
@RequestMapping("/api/user-models")
@RequiredArgsConstructor
@Tag(name = "用户自定义模型", description = "V3.1: 用户自行配置 OpenAI 兼容 LLM 的增删改查")
public class UserModelController {

    private final UserModelService userModelService;

    @Operation(summary = "我的自定义模型列表", description = "返回当前用户所有自定义模型 (apiKey 恒为 null)")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = UserModelConfigDTO.class)))
    @GetMapping
    public Result<List<UserModelConfigDTO>> list(ServerWebExchange exchange) {
        String userId = SecurityContext.getUserId(exchange);
        return Result.success(userModelService.listConfigs(userId));
    }

    @Operation(summary = "新增自定义模型", description = "apiKey 必填, 加密落库")
    @PostMapping
    public Result<UserModelConfigDTO> create(@RequestBody UserModelConfigDTO dto, ServerWebExchange exchange) {
        String userId = SecurityContext.getUserId(exchange);
        return Result.success(userModelService.create(userId, dto));
    }

    @Operation(summary = "更新自定义模型", description = "apiKey 留空表示保留原值")
    @PutMapping("/{id}")
    public Result<UserModelConfigDTO> update(@PathVariable String id, @RequestBody UserModelConfigDTO dto,
                                             ServerWebExchange exchange) {
        String userId = SecurityContext.getUserId(exchange);
        return Result.success(userModelService.update(userId, id, dto));
    }

    @Operation(summary = "删除自定义模型")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id, ServerWebExchange exchange) {
        String userId = SecurityContext.getUserId(exchange);
        userModelService.delete(userId, id);
        return Result.success();
    }
}