package com.dcdr.agent.controller;

import com.dcdr.agent.auth.SecurityContext;
import com.dcdr.agent.common.Result;
import com.dcdr.agent.dto.ModelInfo;
import com.dcdr.agent.model.registry.ModelRegistry;
import com.dcdr.agent.service.UserModelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型接口
 */
@RestController
@RequestMapping("/api/models")
@RequiredArgsConstructor
@Tag(name = "模型查询", description = "查询可用模型列表，用于前端模型切换与 UI 模式判定")
public class ModelController {

    private final ModelRegistry registry;
    private final UserModelService userModelService;

    @Operation(summary = "获取模型列表", description = "返回平台内置模型 (yaml 配置) + 当前用户自定义模型 (V3.1)。" +
            "前端依据 uiMode 字段决定渲染对话模式或创作模式; 用户自定义模型 name 以 custom: 前缀标识")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ModelInfo.class)))
    @GetMapping
    public Result<List<ModelInfo>> list(ServerWebExchange exchange) {
        List<ModelInfo> list = new ArrayList<>(registry.listModels());
        String userId = SecurityContext.getUserId(exchange);
        if (userId != null) {
            // 用户自定义模型追加在平台内置模型之后
            list.addAll(userModelService.listModels(userId));
        }
        return Result.success(list);
    }
}
