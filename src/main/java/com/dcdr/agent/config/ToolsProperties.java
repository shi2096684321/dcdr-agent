package com.dcdr.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 工具配置属性
 */
@Data
@ConfigurationProperties(prefix = "agent-tools")
public class ToolsProperties {

    private WeatherConfig weather = new WeatherConfig();
    private ToolConfig calculator = new ToolConfig();
    private ToolConfig time = new ToolConfig();
    private SandboxConfig sandbox = new SandboxConfig();
    private WebToolsConfig web = new WebToolsConfig();

    @Data
    public static class WeatherConfig {
        private boolean enabled = true;
        private String apiKey = "demo-key";
        private String baseUrl = "https://restapi.amap.com/v3/weather/weatherInfo";
    }

    @Data
    public static class ToolConfig {
        private boolean enabled = true;
    }

    /**
     * V3.1 F-43 代码执行沙箱配置(OpenSandbox 自托管沙箱中间件客户端)。
     * <p>
     * <b>默认关闭</b>: 启用前需先部署 opensandbox-server 并提供 API Key,
     * 选型理由与已知坑见仓库根目录 TODO.md「V3.1 F-43 代码执行沙箱」。
     * <p>
     * 应用侧只连 Sandbox Server 的 HTTP, <b>不持有 docker.sock</b>;
     * 沙箱的资源限额 / 网络策略 / 生命周期均由服务端实施。
     */
    @Data
    public static class SandboxConfig {

        /** 是否启用(默认关闭, 仅管理员通过配置开启) */
        private boolean enabled = false;

        /** OpenSandbox Server 地址, 如 localhost:8080 */
        private String domain = "localhost:8080";

        /** OpenSandbox API Key(服务端鉴权) */
        private String apiKey = "";

        /** HTTP 协议: http / https */
        private String protocol = "http";

        /**
         * 是否经由 Server 代理访问沙箱(execd 命令执行 / endpoint)。
         * <p>
         * 必须保持 true: 客户端(宿主上的应用, 或 compose 里的 agent 容器)与沙箱不在
         * 同一个 docker bridge 网络, <b>无法直连沙箱的内网 IP</b>(实测 172.x 地址,
         * 健康检查必然 300s 超时 → SandboxReadyTimeoutException)。
         * 开启后由 Server 转发 HTTP / WebSocket。对应 SDK 的 {@code useServerProxy}。
         */
        private boolean useServerProxy = true;

        /** 沙箱基础镜像(需自带 python 解释器) */
        private String pythonImage = "python:3.12-slim";

        /** 沙箱存活超时(秒): 到期由服务端自动终止, 作为容器泄漏的兜底 */
        private int timeoutSeconds = 30;

        /** 等待沙箱就绪超时(秒) */
        private int readyTimeoutSeconds = 30;

        /** 单条命令执行超时(秒), 应小于 timeoutSeconds */
        private int executionTimeoutSeconds = 10;

        /** CPU 限额 */
        private String cpus = "1";

        /** 内存限额, 如 512Mi */
        private String memory = "512Mi";

        /** 代码最大长度(字符), 超限直接拒绝, 不进入沙箱 */
        private int maxCodeLength = 8000;

        /** 输出最大长度(字符), 与回灌治理的 agent.tools.result.max-length 口径保持一致 */
        private int maxOutputLength = 6000;

        /** 全局并发上限(防单次对话打满宿主) */
        private int maxConcurrent = 2;

        /** 是否禁止沙箱出网(true=NetworkPolicy 默认 DENY) */
        private boolean networkDisabled = true;
    }

    /**
     * V3.2 自建联网搜索配置(替代第三方 Tavily)。
     * <p>
     * 由两个原生 {@code Tool} 提供能力, 工具名与原先 MCP 版本保持一致
     * ({@code web_search} / {@code web_extract}), 因此 Agent 编排层零改动:
     * <ul>
     *   <li>{@code web_search} → SearXNG 元搜索(自托管, 无 API Key)</li>
     *   <li>{@code web_extract} → crux 正文提取(Mozilla Readability 移植)</li>
     * </ul>
     * SearXNG 需在 {@code settings.yml} 中开启 {@code search.formats: [html, json]}。
     */
    @Data
    public static class WebToolsConfig {

        /** 是否启用自建联网搜索(关闭时两个工具不注册, 模型不可见) */
        private boolean enabled = true;

        /** SearXNG 服务地址(compose 内为 http://searxng:8080, 宿主本机跑 jar 用 http://localhost:8888) */
        private String searxngUrl = "http://localhost:8888";

        /** 搜索语言, 如 zh-CN / en-US / all */
        private String language = "zh-CN";

        /** 单次搜索默认返回条数(会被模型传入的 maxResults 覆盖, 上限见 maxResultsLimit) */
        private int maxResults = 8;

        /** 单次搜索最多返回条数上限 */
        private int maxResultsLimit = 12;

        /** HTTP 超时(秒): 搜索与抓取共用 */
        private int timeoutSeconds = 15;

        /** 单页正文最大长度(字符), 超出截断 */
        private int maxContentLength = 8000;

        /** 单次 web_extract 最多抓取的 URL 数 */
        private int maxExtractUrls = 3;

        /**
         * 是否开启 SSRF 防护: 拒绝环回/内网/链路本地(含云元数据 169.254.169.254)等地址。
         * <p>仅在需要抓取内网内部站点时才应关闭。
         */
        private boolean ssrfGuard = true;

        /** 抓取时使用的 User-Agent(部分站点对无 UA 的请求直接 403) */
        private String userAgent =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36";
    }
}
