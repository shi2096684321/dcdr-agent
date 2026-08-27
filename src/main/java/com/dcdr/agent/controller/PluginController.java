package com.dcdr.agent.controller;

import com.dcdr.agent.common.Result;
import com.dcdr.agent.tool.plugin.PluginLoader;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * V3.0-rc F-37 插件系统管理接口。
 * <ul>
 *   <li>GET  /api/plugins          已加载插件列表 (含工具清单)</li>
 *   <li>POST /api/plugins/reload   热重载: 重新扫描 plugins 目录 (先卸载旧插件)</li>
 * </ul>
 * 权限: user:manage (仅管理员, SecurityConfig /api/plugins 规则)。
 */
@Slf4j
@RestController
@RequestMapping("/api/plugins")
@RequiredArgsConstructor
@Tag(name = "插件系统", description = "V3.0-rc F-37 插件加载/卸载/热重载 (仅管理员)")
public class PluginController {

    private final PluginLoader pluginLoader;

    @Operation(summary = "已加载插件列表", description = "返回当前加载的插件 (版本/作者/JAR/注册工具清单)。")
    @GetMapping
    public Result<List<Map<String, Object>>> list() {
        return Result.success(pluginLoader.listPlugins());
    }

    @Operation(summary = "热重载插件",
            description = "重新扫描 plugins 目录: 已加载插件先 destroy 并反注册工具, 再按 JAR 顺序重新加载。"
                    + "危险 API 审计拦截在加载前执行。")
    @PostMapping("/reload")
    public Result<Map<String, Object>> reload() {
        Map<String, Object> before = Map.of("plugins", pluginLoader.listPlugins());
        pluginLoader.loadAll();
        return Result.success(Map.of(
                "before", before,
                "after", pluginLoader.listPlugins(),
                "toolCount", pluginLoader.toolCount()));
    }
}
