package com.dcdr.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

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

    /**
     * 允许暴露给模型的工具名白名单(V3.2, 部署期开关)。
     * <p>
     * <b>为什么放在部署配置里而不是产品界面</b>: "能调哪些工具"是部署方对风险与成本的决策
     * (联网出网、代码沙箱、第三方 MCP), 与数据库连接串同级; 而用户要表达的是"这个专家干什么活",
     * 不是"它能调哪个 API"。工具选择属于模型每轮的任务判断, 不该固化成一份人工配置。
     * <p>
     * 语义: {@code null} 或空集合 = 全部已注册工具都暴露; 一旦填写, 未列出的工具
     * (含 MCP / 插件等动态注册的工具)不再注入模型 —— 但仍可被 workflow 等显式路径按名执行。
     * 平台机制工具({@code skill_load} / {@code tool_result_read})不受此开关影响,
     * 否则会出现"模型看得见技能清单却没法读取"的自相矛盾状态。
     */
    private List<String> enabled;

    /**
     * 单轮注入给模型的工具定义条数告警阈值(V3.2)。
     * <p>
     * 工具定义是每轮都要重发的上下文成本, 且工具越多、模型选错的概率越高
     * (业界实测: 全量工具集下选择准确率可下降 20 个百分点以上)。
     * 超过该阈值说明到了该"合并/裁剪工具"而不是继续加工具的时候。
     */
    private int contextWarnCount = 15;

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

        /**
         * 按源的调用单价(元/次), key 形如 {@code deepseek:web_search} / {@code deepseek:web_extract}。
         *
         * <p><b>为什么必须按源分别配</b>: DeepSeek 按次计费, 自建 SearXNG 只有服务器成本(单价 0)。
         * 混成一个均价算出来的钱是错的 —— 而错的钱比没有钱更危险, 它会让人做出错误的取舍
         * ("联网太贵了, 关掉吧"), 实际可能只是定价配错了。
         *
         * <p>**默认全 0, 不猜价格**: 单价随合同/账单变化, 猜一个数写进默认值, 上线后没人会去核对。
         * 留空时页面只显示次数、费用显示"未配置单价", 逼着使用者按自己的账单填一次。
         *
         * <p><b>key 的真实形状</b>: yml 里写 {@code "deepseek:web_search"}, 但 Spring 绑定 Map 时
         * 会把冒号这类非字母数字字符剔掉, 内存里是 {@code deepseekweb_search}。取值方
         * ({@code WebUsageService.price}) 必须按同一规则归一化, 否则会表现为"配了单价却显示未配置"。
         */
        private java.util.Map<String, java.math.BigDecimal> pricing = new java.util.LinkedHashMap<>();

        /**
         * 搜索源策略(V3.3): {@code searxng} / {@code deepseek}。
         * <p>由 {@code WebProviderFactory} 在<b>启动时</b>解析, 配置写错会直接启动失败 ——
         * 联网工具是"配错了也照常跑、只是搜不到东西"的典型, 运行时才发现代价太大。
         * <p>两个源都注册了同名工具, 因此<b>不能同时启用</b>, 只在这一处二选一。
         */
        private String provider = "searxng";

        /**
         * 抓取源策略: {@code auto} / {@code local} / {@code deepseek}。
         * <p>{@code auto} = 跟随 {@link #provider}: 搜索走 deepseek 时抓取也走 deepseek,
         * 否则走本地 crux。之所以不把两个开关合并, 是因为"搜索质量差"和
         * "正文提取差"是两个独立问题, 需要分开定位(DeepSeek 的 Fetch 是黑盒, 提取不出
         * 也没法调; 本地 crux 能按站点打补丁)。
         */
        private String fetchProvider = "auto";

        /**
         * 抓取主源拿不到正文时, 是否回落本地 crux。
         * <p>DeepSeek Fetch 的 403(安全策略拦截) / 404(提不出可用 Markdown) 都是
         * <b>不可调</b>的失败, 而本地链路对中文站、GBK 编码、结构化重建做了大量适配,
         * 兜一层能实打实提高抓取成功率。代价是一次失败会多花一个往返。
         */
        private boolean fetchFallback = true;

        /**
         * 兜底的耗时预算(毫秒): 主源耗时超过它就不再换源重试。
         * <p>
         * <b>为什么需要这道闸</b>: 两个抓取源的超时都是 {@code timeout-seconds}(15s), 兜底一次
         * 意味着最坏 30s/篇; 配上"单轮 6 篇"的抓取预算, 最坏 180s —— 用户看到的就是卡死。
         * 而兜底真正有价值的场景是<b>快速失败</b>(403 安全拦截 / 404 提不出正文, 通常 <1s),
         * 那时换本地 crux 往往能拿到; 主源已经耗满 15s 的情况, 换源大概率再等 15s 还是超时。
         * <p>默认 5s 约为单次超时的三分之一。
         */
        private int fetchFallbackBudgetMs = 5000;

        /**
         * 单轮对话里「串联抓取」的总篇数预算(跨同一轮内的多次搜索共享)。
         * <p>
         * <b>为什么需要这个预算</b>: 模型会在一轮里并行发出多个 query 不同的 web_search。
         * 曾按每次搜索抓 top-3 跑过, 4 次搜索就是 12 次网页请求 —— 实测单轮 43.9s、
         * 9 万入参 token, 界面上表现为"联网卡死"。但也不能简单规定"只抓第一次": 那会让后续搜索
         * 只剩摘要, 而它们可能才是主题最相关的那几个。
         * <p>
         * <b>取值口径</b>: 单次搜索抓 1 篇({@code EXTRACT_TOP_N}=1, 只做正文兜底),
         * 故 6 = 允许<b>六次</b>搜索都拿到一篇正文, 正常对话基本用不到这么多 ——
         * 超出预算后的搜索只返回摘要, 模型可通过 web_extract 自行补抓。
         * 设小(如 2~3)会让更多条目退化为纯摘要, 换取更快响应;
         * 设为 0 表示不做任何串联抓取(全部交给模型按需抓)。
         * <p>按"延迟优先还是信息优先"调这一个数字即可。
         */
        private int extractBudgetPerRound = 6;

        /**
         * 单次请求内允许发起的 {@code web_search} 次数上限。
         *
         * <p><b>为什么必须有这道闸</b>: 之前只有 {@code MAX_TOOL_ROUNDS=10} 兜底, 而模型会在一轮里
         * 并行发 3~4 个不同关键词的搜索 —— 关键词不同, 循环检测(相同签名)一个都不命中,
         * 于是能一路搜到第 10 轮。实测一次对话 40 次搜索、0.2 元, 而回答只用了其中几条。
         *
         * <p>取 8 的理由: 正常"搜一次 → 不够 → 换词再搜"最多 2~3 次就该收敛; 8 次给了充足余量,
         * 又不至于一路烧到上限。调小更快但更容易漏信息, 按自己的场景取 4~10。
         */
        private int maxSearchPerTurn = 8;

        /**
         * 去重来源数达到该值后, 不再允许新的搜索(除非本轮还没搜过)。
         *
         * <p>只看次数不够: 8 次搜索如果每次都拿到新东西, 那是有价值的; 反过来 3 次搜索
         * 已经把同一批站点翻来覆去搜了三遍, 第 4 次就是纯浪费。数量是"信息是否已经够多"的
         * 最直接代理指标 —— 它不完美(可能有 30 条都不相关), 但比只数次数准确得多。
         */
        private int sourceSaturation = 30;

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

        /** DeepSeek 自研搜索/抓取接入配置 */
        private DeepseekConfig deepseek = new DeepseekConfig();

        /**
         * DeepSeek Search / Fetch 接入配置(V3.3)。
         * <p>
         * 两个接口是纯 JSON API, 与模型接口同一个 Base URL、同一套 Bearer 鉴权,
         * 因此默认复用 {@code DEEPSEEK_API_KEY}。若想用独立的 key 做成本核算,
         * 单独设 {@code DEEPSEEK_SEARCH_API_KEY} 即可覆盖。
         * <p>
         * <b>计费口径</b>: 每 1000 次<b>成功</b>请求 5 元(Search 返回非空结果、Fetch 返回正文
         * 且 HTTP 200 才算); 空结果、超时、失败不计费。{@code count} 不影响单价,
         * 所以把条数调大不会更贵 —— 但会撑大上下文, 上限仍由 {@code maxResultsLimit} 约束。
         */
        @Data
        public static class DeepseekConfig {

            /** 服务基址(不含 /v1) */
            private String baseUrl = "https://api.deepseek.com";

            /** API Key; 为空时该策略不可用, 工厂会回退到 searxng 并告警 */
            private String apiKey = "";

            /**
             * 是否让 Search 直接返回正文({@code need_content})。
             * <p><b>默认关</b>: 开着会让单次响应体积大增, 而编排层对 {@code web_search}
             * 本来就会串联 {@code web_extract} 补正文, 属于重复抓取。
             * 只有在"想用一次调用换掉一轮工具交互"时才建议打开。
             */
            private boolean needContent = false;

            /**
             * 相关性阈值: 丢弃 {@code rerank_score} 低于该值的结果。
             * <p>官方口径 ≥1.5 为优质结果; 默认 0 表示<b>不过滤</b> ——
             * 阈值属于"效果调优"而非"正确性", 部署方按自己的内容域实测后再收紧,
             * 默认就卡 1.5 会把长尾但正确的来源静默丢掉。
             */
            private double minRerankScore = 0;

            /**
             * 权威性阈值: 丢弃 {@code authority} 低于该值的结果。
             * <p>取值 0~4, -1 表示未知; 默认 -1 = 不过滤(未知也不丢)。
             */
            private int minAuthority = -1;

            /** 本地化 IP, 用于优先返回该地区的搜索结果; 空串表示不指定 */
            private String localization = "";
        }
    }
}
