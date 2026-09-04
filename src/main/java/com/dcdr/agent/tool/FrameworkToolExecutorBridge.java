package com.dcdr.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 框架原生的工具执行桥(V3.2 框架化: 工具调用协议收敛到 LangChain4j {@link ToolExecutor})。
 * <p>
 * 把协议层从"散落的名称+Map 分发"统一为框架的 {@link ToolExecutionRequest}(id/name/arguments),
 * 内部仍复用到现有的 {@link ToolRegistry} 完成实际执行, 保留 execute/executeInfo 两条语义:
 * <ul>
 *   <li>{@link #execute}: 返回纯文本(框架 {@link ToolExecutor} 契约)</li>
 *   <li>{@link #executeWithInfo}: 返回结构化 {@link ToolInfo}(含 sources, orchestrator 主路径)</li>
 * </ul>
 * 工具本体、白名单、{@code LlmCallTrace} 打点仍在 orchestrator/ToolRegistry 中, 此处仅作协议适配。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FrameworkToolExecutorBridge implements ToolExecutor {

    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 框架 {@link ToolExecutor} 契约: 返回纯文本结果 */
    @Override
    public String execute(ToolExecutionRequest request, Object memoryId) {
        String name = request.name();
        Map<String, Object> params = parseArgs(request.arguments());
        return toolRegistry.execute(name, params);
    }

    /** 执行并返回结构化结果(保留 sources 等扩展信息), orchestrator 工具循环主路径 */
    public ToolInfo executeWithInfo(ToolExecutionRequest request, Object memoryId) {
        String name = request.name();
        Map<String, Object> params = parseArgs(request.arguments());
        return toolRegistry.executeInfo(name, params);
    }

    /** 解析参数 JSON; 空/非法参数视为无参数, 与旧分发路径行为一致 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArgs(String args) {
        if (args == null || args.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(args, Map.class);
        } catch (Exception e) {
            log.warn("工具参数 JSON 解析失败: {}", args, e);
            return new LinkedHashMap<>();
        }
    }
}