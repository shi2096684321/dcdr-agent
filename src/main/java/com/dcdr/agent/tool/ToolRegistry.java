package com.dcdr.agent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册中心 - 管理所有可用工具
 */
@Slf4j
@Component
public class ToolRegistry {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    public void register(Tool tool) {
        tools.put(tool.getName(), tool);
        log.info("注册工具: {}", tool.getName());
    }

    public Tool getTool(String name) {
        return tools.get(name);
    }

    public Collection<Tool> getAllTools() {
        return tools.values();
    }

    /** 执行工具 */
    public String execute(String toolName, Map<String, Object> params) {
        Tool tool = tools.get(toolName);
        if (tool == null) {
            return "工具不存在: " + toolName;
        }
        try {
            log.debug("执行工具: {}, 参数: {}", toolName, params);
            return tool.execute(params);
        } catch (Exception e) {
            log.error("工具执行失败: {}", toolName, e);
            return "工具执行失败: " + e.getMessage();
        }
    }

    /** 判断是否有已注册工具 */
    public boolean hasTools() {
        return !tools.isEmpty();
    }
}
