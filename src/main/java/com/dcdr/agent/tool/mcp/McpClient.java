package com.dcdr.agent.tool.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP Client - 连接一个 stdio 模式的 MCP Server, 完成握手、工具发现与调用。
 * <p>
 * 这是 Host(本应用) 内的客户端组件, 对应之前讨论里「只需写 MCP Client」。
 * 它把 MCP Server 暴露的能力拉成本地的 {@link McpToolSpec}, 由上层包装成现有 {@code Tool} 注册进 ToolRegistry。
 * </p>
 * <p>纯 JDK 实现, 不依赖任何 MCP SDK, 便于离线编译。</p>
 */
@Slf4j
public class McpClient implements AutoCloseable {

    private static final long TIMEOUT_MS = 15000;

    private final McpTransport transport;
    private final ObjectMapper mapper;

    private McpClient(McpTransport transport, ObjectMapper mapper) {
        this.transport = transport;
        this.mapper = mapper;
    }

    /** 启动一个 stdio 模式的 MCP Server 并建立连接(含 initialize 握手) */
    public static McpClient connect(List<String> command, ObjectMapper mapper) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        McpTransport transport = new McpTransport(pb, mapper);
        McpClient client = new McpClient(transport, mapper);
        client.initialize();
        return client;
    }

    /** 按 MCP 协议执行 initialize 握手 */
    private void initialize() throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", "2024-11-05");
        params.put("capabilities", new LinkedHashMap<>());
        Map<String, Object> clientInfo = new LinkedHashMap<>();
        clientInfo.put("name", "java-agent");
        clientInfo.put("version", "1.1");
        params.put("clientInfo", clientInfo);

        Map<String, Object> resp = transport.request("initialize", params, TIMEOUT_MS);
        Object result = resp.get("result");
        log.info("MCP initialize 成功: {}", result);

        // 发送 initialized 通知(协议要求)
        transport.notify("notifications/initialized", null);
    }

    /** 列出 Server 暴露的所有工具 */
    @SuppressWarnings("unchecked")
    public List<McpToolSpec> listTools() throws Exception {
        Map<String, Object> resp = transport.request("tools/list", null, TIMEOUT_MS);
        Object result = resp.get("result");
        List<McpToolSpec> specs = new ArrayList<>();
        if (result instanceof Map) {
            Object toolsObj = ((Map<String, Object>) result).get("tools");
            if (toolsObj instanceof List) {
                for (Object o : (List<?>) toolsObj) {
                    if (o instanceof Map) {
                        Map<String, Object> m = (Map<String, Object>) o;
                        specs.add(new McpToolSpec(
                                str(m.get("name")),
                                str(m.get("description")),
                                m.get("inputSchema"),
                                (Map<String, Object>) m.get("annotations")));
                    }
                }
            }
        }
        log.info("MCP Server 暴露 {} 个工具", specs.size());
        return specs;
    }

    /** 调用某个工具, 返回 Server 的 content 文本集合(用 \n 拼接) */
    @SuppressWarnings("unchecked")
    public McpCallResult callTool(String name, Map<String, Object> arguments) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", name);
        params.put("arguments", arguments == null ? new LinkedHashMap<>() : arguments);
        Map<String, Object> resp = transport.request("tools/call", params, 30000);
        Object result = resp.get("result");
        StringBuilder text = new StringBuilder();
        boolean isError = false;
        List<Map<String, Object>> structured = new ArrayList<>();
        if (result instanceof Map) {
            Map<String, Object> rm = (Map<String, Object>) result;
            isError = Boolean.TRUE.equals(rm.get("isError"));
            Object content = rm.get("content");
            if (content instanceof List) {
                for (Object c : (List<?>) content) {
                    if (c instanceof Map) {
                        Map<String, Object> cm = (Map<String, Object>) c;
                        String type = str(cm.get("type"));
                        if ("text".equals(type)) {
                            if (text.length() > 0) text.append("\n");
                            text.append(str(cm.get("text")));
                        } else if ("resource".equals(type) && cm.get("resource") instanceof Map) {
                            Map<String, Object> res = (Map<String, Object>) cm.get("resource");
                            structured.add(res);
                        }
                    }
                }
            }
        }
        return new McpCallResult(text.toString(), isError, structured);
    }

    @Override
    public void close() {
        transport.close();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    /** MCP 工具规格(name/description/inputSchema) */
    public static class McpToolSpec {
        public final String name;
        public final String description;
        public final Object inputSchema;
        public final Map<String, Object> annotations;

        public McpToolSpec(String name, String description, Object inputSchema,
                           Map<String, Object> annotations) {
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
            this.annotations = annotations;
        }
    }

    /** MCP 工具调用结果 */
    public static class McpCallResult {
        public final String text;
        public final boolean isError;
        public final List<Map<String, Object>> structured;

        public McpCallResult(String text, boolean isError, List<Map<String, Object>> structured) {
            this.text = text;
            this.isError = isError;
            this.structured = structured;
        }
    }
}
