package com.dcdr.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 核心配置属性绑定
 */
@Data
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    private String name;

    private MemoryConfig memory = new MemoryConfig();
    private StorageConfig storage = new StorageConfig();
    private ModelsConfig models = new ModelsConfig();
    private ToolsConfig tools = new ToolsConfig();

    @Data
    public static class MemoryConfig {
        private int maxMessages = 6;
        private String storage = "memory";
        /** 估算上下文 token 上限, 达到 warn-ratio 比例时提示用户开启新对话 */
        private int contextTokenLimit = 8000;
        /** 触发提示的阈值比例(0~1) */
        private double contextWarnRatio = 0.8;
        /** LLM 自动抽取记忆 (V3.1 P1), 默认关闭 */
        private AutoExtractConfig autoExtract = new AutoExtractConfig();
    }

    /**
     * LLM 自动抽取长期记忆 (V3.1 P1) —— 参考 CodeBuddy 的记忆设计:
     * 它同样是一个「可全局关闭的开关」+ 「AI 自动识别并保存」, 且由 AI 自己管理记忆。
     * <p>
     * <b>为什么默认关闭</b>: ①它每隔若干轮就要发起一次真实模型调用, 是<b>持续性费用</b>;
     * ②在此之前记忆全部由用户亲手写下, "AI 自己往里写"是质变 —— 应当由部署方显式开启
     * (与 {@code agent.tools.result.summary} 同一口径, 那个也是默认关闭)。
     * <p>
     * <b>关闭时的影响仅限于"不再自动抽取"</b>: 手动增删改、召回注入、过期归档、命中统计
     * 一律照常; 也不会影响已经抽出来的记忆。
     */
    @Data
    public static class AutoExtractConfig {
        /** 默认关闭: 见类注释 */
        private boolean enabled = false;
        /**
         * 能力开启后, <b>未表态用户</b>的默认是否生效 —— 即"该能力默认给谁用"。
         * <p>
         * {@code true}(默认) = 跟随部署开启, 用户可在记忆页自行关闭
         * (与 CodeBuddy 的"AI 自动识别并保存 + 可全局关闭"一致);
         * {@code false} = 用户必须自己在记忆页开启(opt-in), 适合同意口径更严的部署。
         * <p>
         * <b>为什么默认 true</b>: 若默认 false, 那么部署方把 {@link #isEnabled()} 设为 true
         * 之后什么都不会发生 —— 一个显式开关被另一个开关的默认值悄悄抵消, 排查起来很费解。
         * 用户级开关应当是<b>新增的一层控制</b>, 而不是顺手改变已配好的行为。
         * <p>
         * 之所以做成配置而不是写死: "要不要对用户默认开启"是产品判断, 随部署方的合规口径而异,
         * 在代码里定死的话改一次就要发一次版。
         */
        private boolean userDefault = true;
        /** 抽取用模型 (留空 = 系统默认对话模型)。建议配低成本模型 —— 它是周期性调用 */
        private String model;
        /**
         * 每多少轮抽取一次 (按会话累计消息数估算)。
         * <p>不以"会话结束"触发: 对话是流式的, 没有可靠的"结束"事件 —— 用户随时可能再说一句,
         * 靠超时或断连判定会话结束, 反而会漏掉最能沉淀记忆的那批长对话。
         */
        private int everyNTurns = 4;
        /**
         * 单次抽取最多写入几条(ADD/UPDATE/DELETE 合计)。
         * <p>注入是每轮的常驻成本, 一次灌进十条会把背景预算吃光, 让后面真正重要的记忆挤不进来。
         */
        private int maxPerRun = 2;
        /**
         * 新增前与现有记忆的相似度阈值(0~1): 达到即视为重复, 跳过并记日志;
         * &lt;= 0 表示不查重。
         * <p>取值比召回的 {@code HIT_THRESHOLD}(0.5) 高 —— 召回漏判只是少注入一条,
         * 这里误判会让"该记的东西记不下来", 宁可放过, 不可错杀。
         */
        private double dedupeThreshold = 0.6;
    }

    @Data
    public static class StorageConfig {
        private String baseDir = "./data";
        private String imageDir = "./data/images";
        private String videoDir = "./data/videos";
        private String uploadDir = "./data/uploads";
    }

    @Data
    public static class ModelsConfig {

        /**
         * 内置兜底主密钥 —— <b>公开可知, 仅供占位</b>。
         * <p>提为常量而不是留在 {@code ApiKeyCipher} 里: {@code SecretGuard} 要按它判定"是否仍是默认值"。
         */
        public static final String DEFAULT_API_KEY_SECRET = "dcdr-agent-model-api-key-secret-default";

        private String defaultChat = "qwen-chat";

        /**
         * 用户自定义模型 apiKey 的 AES 加密主密钥(>=16 字节)。
         * <p>用环境变量 MODEL_API_KEY_SECRET 注入; 缺省/为空会被 {@code SecretGuard} 拒绝启动 ——
         * 加密主密钥公开可知时, 拿到库里的密文就能解出用户真实的 Key。
         */
        private String apiKeySecret;

        /**
         * 是否允许使用内置兜底主密钥(默认否)。
         * <p>同上: 只应在"本机开发、库里没有真实用户 Key"时打开。
         */
        private boolean allowDefaultKeySecret = false;
        private List<ModelConfig> chat = new ArrayList<>();
        private List<ModelConfig> image = new ArrayList<>();
        private List<ModelConfig> video = new ArrayList<>();
        /** 语音模型 (STT/TTS), V1.1 新增 */
        private List<ModelConfig> audio = new ArrayList<>();
    }

    @Data
    public static class ModelConfig {
        private String name;
        private boolean enabled = true;
        private String provider;
        /** 实现引擎: langchain4j=框架内核(默认, 适配任意 OpenAI 兼容端点) | manual=手写 WebClient 实现(回退选项) */
        private String engine = "langchain4j";
        private String apiKey;
        /** 主 base-url: langchain4j 引擎使用(版本段如 /v1 由它携带, 框架在其后追加资源路径) */
        private String baseUrl;
        /** 手写引擎专用 base-url(可选): 仅当两种引擎端点本质不同时配置, 如 qwen 生图
         *  (manual=DashScope 原生根地址 / langchain4j=compatible-mode); 缺省回退 base-url */
        private String manualBaseUrl;
        /** 鉴权头格式: bearer(默认, 即 "Bearer <key>") 或 raw(即 "<key>", 部分中转平台用) */
        private String authScheme = "bearer";
        private String model;
        /** 图生视频专用模型名(可选, 默认同 model), 如万相 wan2.7-i2v 系列 */
        private String modelI2v;
        private Double temperature;
        private Integer maxTokens;
        private String size;
        private List<String> capabilities = new ArrayList<>();
    }

    /**
     * V3.1 F-47 工具相关配置。
     * <p>
     * <b>为什么挂在 {@code agent.tools.*} 而不是既有的 {@code agent-tools.*}</b>:
     * {@code agent-tools.*}({@link ToolsProperties}) 承载的是「各内置工具自己的设置」
     * (天气 Key、沙箱开关、输出上限), 而这里的「结果回灌治理」是<b>编排层行为</b>
     * (由 AgentOrchestrator 对所有工具统一施加), 与 {@code agent.memory.*} 同层更贴切。
     */
    @Data
    public static class ToolsConfig {
        private ResultConfig result = new ResultConfig();
    }

    /** 工具结果体积治理(F-47): 回灌模型 / 下发前端 / 落库三处共用的压缩口径 */
    @Data
    public static class ResultConfig {
        /** 单条工具结果字符上限(默认 6000, 与 V3.0 的 TOOL_RESULT_MAX_LEN 一致) */
        private int maxLength = 6000;
        /** 单条 token 预算(0 = 不限); 比 max-length 更紧时生效 */
        private int maxTokenBudget = 0;
        /** 超限时保留尾部的比例(0~0.5); 0 = 退回纯头截断 */
        private double keepTailRatio = 0.3;
        /** 落库到 messages.sources 时单个来源正文的字符上限 */
        private int sourceContentMaxLength = 4000;
        /** 回灌窗口内工具结果的 token 总量预算(0 = 不限) */
        private int windowTokenBudget = 0;
        /** 窗口预算触顶后, 本轮单条结果的字符上限 */
        private int windowFloorLength = 800;
        private SummaryConfig summary = new SummaryConfig();
        private SpillConfig spill = new SpillConfig();
    }

    /** LLM 摘要降级(F-47.4): 超阈值时先摘要再回灌, 失败回退硬截断 */
    @Data
    public static class SummaryConfig {
        /** 默认关闭: 摘要是一次真实模型调用, 会产生费用与延迟, 由 yml 显式开启 */
        private boolean enabled = false;
        /** 超过该字符数(且未达落盘阈值)时触发摘要; 建议为 max-length 的 3 倍左右 */
        private int threshold = 20000;
        /** 摘要用模型(留空 = 系统默认对话模型) */
        private String model;
    }

    /** 超长结果落盘 + 分页读取(F-47.5) */
    @Data
    public static class SpillConfig {
        private boolean enabled = true;
        /** 超过该字符数则全文落盘, 回灌只给"路径 + 规模 + 前 N 行" */
        private int threshold = 32768;
        /** 落盘根目录(相对工作目录; 建议置于 bind mount 的 data 下, 容器重建不丢) */
        private String dir = "./data/tool-results";
        /** 落盘提示里附带的前几行预览 */
        private int headLines = 30;
        /** 分页读取的默认行数 */
        private int pageSize = 200;
        /** 分页读取的单次行数上限 */
        private int maxPageSize = 1000;
        /** 落盘文件保留天数(0 = 不清理) */
        private int retainDays = 7;
    }
}
