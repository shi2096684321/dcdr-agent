package com.dcdr.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP Server 连接配置。
 * <p>
 * 本应用作为 MCP Host, 只需写好 Client 去连接「别人提供的 MCP Server」。
 * 这里声明要连接哪些 Server(通过 stdio 命令启动, 或将来扩展为 SSE/HTTP url)。
 * </p>
 * <p>
 * 示例(application.yml):
 * <pre>
 * mcp:
 *   servers:
 *     - name: web-search
 *       command: ["npx", "-y", "@anthropic/mcp-server-web-search"]
 *       streaming-tools: ["web_search"]
 * </pre>
 * 也可直接写字符串(空格分隔会被拆分):
 * <pre>
 *     - name: web-search
 *       command: npx -y @anthropic/mcp-server-web-search
 * </pre>
 * </p>
 */
@Data
@ConfigurationProperties(prefix = "mcp")
public class McpServerProperties {

    /** 要连接的 MCP Server 列表, 启动时逐个建立连接并把其工具注册进 ToolRegistry */
    private List<ServerConfig> servers = new ArrayList<>();

    @Data
    public static class ServerConfig {
        /** Server 名称(仅用于日志标识) */
        private String name;

        /**
         * 启动该 Server 的 stdio 命令。
         * 推荐用字符串数组形式(原样使用, 避免参数含空格被误拆):
         *   command: ["npx", "-y", "@tavily/mcp-server"]
         * 也支持单个字符串(按空格拆分):
         *   command: "npx -y @tavily/mcp-server"
         */
        private List<String> command;

        /** 远程 Server 的 SSE/HTTP 地址(预留, 当前实现仅支持 stdio) */
        private String url;

        /**
         * 该 Server 里哪些工具允许在流式对话中被 agent 调用(支持 function calling)。
         * 不填则默认为「全部工具都允许流式调用」。
         */
        private List<String> streamingTools = new ArrayList<>();

        /**
         * 工具名映射: 真实工具名 -> 注册到本应用 ToolRegistry 的名字(别名)。
         * 用于把第三方 Server 的工具名对齐到本项目约定(如把 tavily-search 映射为 web_search)。
         * 例: { "tavily-search": "web_search" }
         */
        private Map<String, String> aliases = new LinkedHashMap<>();

        /** 解析 command 为进程参数列表(已绑定为 List<String>) */
        public List<String> resolveCommand() {
            return command == null ? new ArrayList<>() : command;
        }
    }
}
