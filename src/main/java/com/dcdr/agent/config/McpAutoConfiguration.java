package com.dcdr.agent.config;

import com.dcdr.agent.tool.ToolRegistry;
import com.dcdr.agent.tool.mcp.McpClient;
import com.dcdr.agent.tool.mcp.McpTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * MCP 自动装配 - 作为 Host 连接「外部 MCP Server」, 把其暴露的工具动态注册进 ToolRegistry。
 * <p>
 * 之前讨论中确认: 本项目只需写 MCP Client 去访问别人提供的 MCP Server, 不需要自己当 Server。
 * 因此这里只做「连接」这一件事——读取 {@link McpServerProperties} 中配置的 Server(通常是
 * 别人用 npx/python 提供的 stdio Server), 每个建立 McpClient, 把发现的工具包装成 McpTool 注册。
 * </p>
 * <p>
 * 对编排层(AgentOrchestrator)、前端而言, MCP 工具与内置工具(Weather/Calculator)走完全相同的
 * Tool 抽象, 上层代码无需感知背后是进程内调用还是跨进程的 MCP 调用。
 * </p>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(McpServerProperties.class)
public class McpAutoConfiguration {

    @Bean
    public CommandLineRunner mcpRegistrar(ToolRegistry registry, McpServerProperties properties,
                                           ObjectMapper objectMapper) {
        return args -> {
            List<McpServerProperties.ServerConfig> servers = properties.getServers();
            if (servers == null || servers.isEmpty()) {
                log.info("未配置任何 MCP Server, 跳过 MCP 工具注册");
                return;
            }
            for (McpServerProperties.ServerConfig server : servers) {
                connectAndRegister(server, registry, objectMapper);
            }
        };
    }

    private void connectAndRegister(McpServerProperties.ServerConfig server, ToolRegistry registry,
                                    ObjectMapper objectMapper) {
        try {
            List<String> command = server.resolveCommand();
            if (command.isEmpty()) {
                if (server.getUrl() != null && !server.getUrl().isBlank()) {
                    log.warn("MCP Server[{}] 仅配置了 url({}), 当前实现仅支持 stdio, 已跳过",
                            server.getName(), server.getUrl());
                } else {
                    log.warn("MCP Server[{}] 未配置 command, 已跳过", server.getName());
                }
                return;
            }
            McpClient client = McpClient.connect(command, objectMapper);
            List<McpClient.McpToolSpec> specs = client.listTools();
            int registered = 0;
            for (McpClient.McpToolSpec spec : specs) {
                boolean streaming = server.getStreamingTools().isEmpty()
                        || server.getStreamingTools().contains(spec.name);
                // 若配置了别名, 把第三方工具名对齐到本项目约定(如 tavily-search -> web_search)
                String registeredName = server.getAliases().getOrDefault(spec.name, spec.name);
                McpTool tool = new McpTool(client, spec, objectMapper, streaming, registeredName);
                registry.register(tool);
                registered++;
            }
            log.info("MCP Server[{}] 工具注册完成, 共 {} 个", server.getName(), registered);
        } catch (Exception e) {
            log.error("MCP Server[{}] 连接/注册失败, 其工具将不可用", server.getName(), e);
        }
    }
}
