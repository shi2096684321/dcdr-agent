package com.dcdr.agent.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 知识库配置属性 (V2.1)。
 * <p>
 * 对应 application.yml 中 {@code agent.rag.*}, 与 v2.0 规划文档 §3.6 配置设计对齐。
 */
@Data
@ConfigurationProperties(prefix = "agent.rag")
public class RagProperties {

    /** RAG 总开关: 关闭后知识库相关 Bean 仍注册但 Service 直接拒绝操作 */
    private boolean enabled = true;

    /**
     * 平台级知识库 ID 列表(visibility=PLATFORM)。
     * <p>这些知识库对所有对话无感可用: 用户不需要在前端选择,
     * 对话时自动加入 Agentic RAG 检索范围(与用户选的知识库合并)。
     * <p>管理员将知识库 visibility 设为 PLATFORM 后, 把 ID 填入此处即可全局生效。
     */
    private List<String> platformKbIds = new ArrayList<>();

    /** 上传文档落盘根目录(相对工作目录) */
    private String storageDir = "./data/kb";

    private DocumentConfig document = new DocumentConfig();
    private SplitterConfig splitter = new SplitterConfig();
    private EmbeddingConfig embedding = new EmbeddingConfig();
    private VectorStoreConfig vectorStore = new VectorStoreConfig();
    private RetrievalConfig retrieval = new RetrievalConfig();
    /** 检索结果注入 prompt 的策略: 边界标记与长度预算 */
    private InjectConfig inject = new InjectConfig();
    /** 扫描版 PDF / 图片 OCR 配置(V2.1): 关闭时扫描版 PDF 直接报错不降级 */
    private OcrConfig ocr = new OcrConfig();

    @Data
    public static class DocumentConfig {
        /** 单文件大小上限(字符串, 支持 20MB / 200KB 等后缀, 由 service 解析) */
        private String maxFileSize = "20MB";
        /** 支持的文件扩展名(不含点, 全小写) */
        private List<String> supportedFormats = List.of("pdf", "docx", "txt", "md", "markdown", "html", "htm");
        /** 单知识库文档数量上限 */
        private int maxDocsPerKb = 100;
    }

    @Data
    public static class SplitterConfig {
        /** 全局默认分块策略: fixed | recursive | sentence | markdown; 各知识库可覆盖 */
        private String strategy = "recursive";
        /** 全局默认块大小(与 schema knowledge_bases.chunk_size 默认 1000 一致) */
        private int chunkSize = 1000;
        /** 块间重叠字符数(避免语义断裂, 与 schema 默认 200 一致) */
        private int chunkOverlap = 200;
    }

    @Data
    public static class EmbeddingConfig {
        /** 默认 Embedding provider 名称, 未指定时取已注册的第一个 */
        private String defaultProvider;
        private List<EmbeddingProviderConfig> providers = new ArrayList<>();
    }

    @Data
    public static class EmbeddingProviderConfig {
        private String name;
        private boolean enabled = true;
        /** 提供方: qwen | openai | zhipu */
        private String provider;
        /** 实现引擎: langchain4j=框架内核(默认, 适配任意 OpenAI 兼容端点) | manual=手写 WebClient 实现(回退选项) */
        private String engine = "langchain4j";
        private String apiKey;
        private String baseUrl;
        private String model;
        /** 向量维度, 必须与 schema document_chunks.embedding VECTOR(1536) 一致 */
        private int dimensions = 1536;
        /** 单次批量请求最大条目数(DashScope/OpenAI 限制) */
        private int batchSize = 25;
    }

    @Data
    public static class VectorStoreConfig {
        /** 向量库类型: 当前仅支持 pgvector */
        private String type = "pgvector";
    }

    @Data
    public static class RetrievalConfig {
        /** 检索 Top-K (与 application-pg.yml agent.vector-store.top-k 一致) */
        private int topK = 5;
        /** 最小余弦相似度, 低于此值的片段过滤掉 */
        private double minScore = 0.3;
        /** 混合检索(向量 + BM25 全文, RRF 融合)。默认关闭, 纯向量迁移不带行为变化 */
        private HybridConfig hybrid = new HybridConfig();
        /** 相关性重排(ReRankingContentAggregator, 需配置 ScoringModel)。默认关闭 */
        private RerankConfig rerank = new RerankConfig();
        /** 检索查询改写: 多轮对话里把"它/这个"补全成可独立检索的句子。默认开启, 失败自动回退原句 */
        private QueryRewriteConfig rewrite = new QueryRewriteConfig();
    }

    /**
     * 检索查询改写。
     *
     * <p><b>解决什么</b>: 挂了知识库的会话是"每轮都检索", 但检索用的是用户原话。第二轮问
     * 「它的价格呢」时, 向量里没有"它"指向任何东西 —— 检索必然打偏, 而且失败是静默的
     * (没有片段、模型凭记忆答)。改写就是用最近几轮把这句话补成
     * 「A123 订单的价格是多少」, 再拿去检索。
     *
     * <p><b>代价</b>: 每轮多一次小模型调用(几十 token 出、几百 token 入)。所以:
     * 只在"会话挂了库 + 历史非空 + 本句确实依赖上文(含指代或过短)"时才触发;
     * 失败/超时一律回退原句, 绝不阻断对话。
     */
    @Data
    public static class QueryRewriteConfig {
        private boolean enabled = true;
        /** 改写用模型: 填 model-registry 的 provider 名(如 qwen-chat), 不是上游模型 id; 留空跟随系统默认 */
        private String model = "";
        /** 单次改写超时(秒): 超时即用原句。这段等待发生在首字节之前, 别调大 */
        private int timeoutSeconds = 8;
        /** 送进改写请求的历史消息条数上限 */
        private int historyMessages = 6;
    }

    /**
     * 检索结果注入 prompt 的策略。
     *
     * <p>两个目的:
     * <ol>
     *   <li><b>防「间接提示注入」</b>: 注入的片段是<b>外部内容</b>(用户上传的文档、OCR 结果、
     *       联网抓取的网页), 可能含形如"忽略以上指令"、"你现在是 XXX"的文本。
     *       加边界标记 + 显式告知模型"标记内是资料而非指令", 可显著降低被带偏的概率。
     *       此前是原样拼接, 无任何隔离。</li>
     *   <li><b>控住 prompt 长度</b>: 此前注入片段全文, 长度只靠
     *       {@code chunkSize × topK} 间接约束(默认约 5×1000 字) —— 任一项调大就会失控,
     *       且没有显式上限。这里给出明确的字符预算, 超出时按序截断
     *       (保留高分片段: results 已按相似度降序)。</li>
     * </ol>
     */
    @Data
    public static class InjectConfig {

        /** 注入片段的总字符预算; 超出则按序截断(保留高分片段)。<= 0 表示不限制 */
        private int maxTotalChars = 6000;

        /** 单个片段的字符上限; 超出则截断并标注。<= 0 表示不限制 */
        private int maxCharsPerChunk = 2000;

        /**
         * 是否用边界标记包裹片段, 并告知模型"标记内是资料、不是指令"。
         * <p>关闭仅用于对比测试(观察该防护对回答质量的影响), 生产不建议关闭。
         */
        private boolean boundaryMarker = true;

        /**
         * 检索结果以 <b>tool 消息</b>承载并落库（方案 B）。
         *
         * <p><b>为什么</b>: 此前片段只注入当轮的 system 上下文(volatileTail), 用完即弃 ——
         * 于是"这个会话挂了库"就必须每轮重新检索、重新付费。改成落一对
         * {@code assistant(tool_calls)} + {@code tool(结果)} 之后, 片段成为会话历史的一部分,
         * 下一轮模型直接看得到, 不必重复注入(OpenAI Responses / Assistants 的做法)。
         *
         * <p><b>代价</b>: 历史里会多出文档原文(体积、隐私), 所以同时受
         * {@link #keepRecentRetrievals} 限制 —— 只留最近几轮, 更早的成对裁掉。
         *
         * <p>关闭则退回旧行为(当轮 system 注入、不落库)。
         */
        private boolean asToolMessage = true;

        /**
         * 历史里最多保留几轮检索片段（{@code <= 0} = 不裁剪，默认）。
         *
         * <p><b>为什么默认不裁</b>: 前缀缓存是"只追加"的艺术 —— 历史中段一旦被删或被改，
         * <b>删除点之后的所有 token 都要按全价重算</b>。而每轮新增的片段本身多数能命中缓存
         * （读取价约为输入价的 1/10），省下的那几 K 字符远比缓存失效的代价小。
         *
         * <p>重复问题改由两条更便宜的手段解决：
         * <ol>
         *   <li><b>增量去重</b>：历史里已注入过的 chunk 不再重复注入/落库（按 chunkId 比对）；</li>
         *   <li><b>摘要压缩</b>：文量真的涨起来时由 {@code agent.memory.compaction} 统一收敛 ——
         *       低频（十几轮一次）改写一次历史，比每轮破坏前缀划算得多。</li>
         * </ol>
         *
         * <p>只有在你明确"宁可牺牲缓存也要压小上下文"时才调成正数（如 1）。
         */
        private int keepRecentRetrievals = 0;
    }

    @Data
    public static class HybridConfig {
        /** 混合检索总开关 */
        private boolean enabled = false;
        /** RRF 融合常数 K(仅融合排序用, 不改变向量分语义) */
        private int rrfK = 60;
    }

    @Data
    public static class RerankConfig {
        /** 重排总开关 */
        private boolean enabled = false;
        /**
         * 实现引擎:
         * <ul>
         *   <li>{@code dashscope}=百炼原生重排({@code QwenScoringModel}, 走官方 dashscope-sdk-java)</li>
         *   <li>{@code openai}=OpenAI 兼容 HTTP 端点(自研适配器, 对接 Jina / Cohere / 自部署 bge-reranker)</li>
         * </ul>
         * 与 {@code agent.rag.embedding.providers[].engine} 同风格: 换引擎只改此值。
         */
        private String engine = "openai";
        /** rerank 端点 baseUrl; engine=openai 时必填, engine=dashscope 时留空走 SDK 默认端点 */
        private String baseUrl;
        /** API Key; engine=dashscope 时复用 DASHSCOPE_API_KEY */
        private String apiKey;
        /** rerank 模型名: dashscope 用 qwen3.7-text-rerank; openai 用服务商模型名 */
        private String model = "xxx-rerank";
        /**
         * 排序任务说明(仅 engine=dashscope 生效, 建议英文)。
         * 留空则用模型默认: 问答检索任务("Given a web search query, retrieve relevant passages that answer the query.")。
         */
        private String instruct;
        /** 重排后保留的最低分; 为空则用检索 minScore */
        private Double minScore;
        /** 重排后保留的最大条数; 为空则用检索 topK */
        private Integer maxResults;
    }

    /**
     * OCR 配置(V2.1)。
     * <p>
     * 用于扫描版 PDF / 图片型文档的文本提取, 当前接入 DashScope {@code qwen3.5-ocr}。
     * 走 OpenAI 兼容协议 {@code /chat/completions}, 复用 DASHSCOPE_API_KEY。
     * <p>
     * 触发策略: PDFBox 文字版抽取后若每页平均字符数 &lt; {@link #thresholdCharsPerPage},
     * 判定为扫描版, 自动降级调 OCR; 文字版 PDF 不会触发, 避免无谓成本。
     */
    @Data
    public static class OcrConfig {
        /** OCR 总开关: 关闭时 OcrPdfReader 不注册, 扫描版 PDF 沿用原 PdfReader 报错行为 */
        private boolean enabled = false;
        /** API Key, 由 application.yml 用 ${DASHSCOPE_API_KEY} 注入, 默认复用 DashScope 主 Key */
        private String apiKey;
        /** OpenAI 兼容 baseUrl, 默认华北2(北京) DashScope 域名 */
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        /** 模型名: qwen3.5-ocr (Qwen3.5 架构, 支持多轮+PDF) | qwen-vl-ocr | qwen-vl-ocr-latest */
        private String model = "qwen3.5-ocr";
        /**
         * 扫描版判定阈值: PDFBox 抽取的"每页平均字符数"低于此值时触发 OCR。
         * 默认 50, 纯图片型 PDF 通常每页 0~10 字符, 文字版 PDF 一般每页 500+ 字符。
         */
        private int thresholdCharsPerPage = 50;
        /** 单文档最多 OCR 页数, 兜底防止大文档烧钱(整 PDF 直传时仅作日志告警, 不强制截断) */
        private int maxPages = 50;
        /** 单次 OCR 请求超时(秒), qwen3.5-ocr 处理大 PDF 较慢, 默认 300s */
        private int timeoutSeconds = 300;
        /** 自定义 prompt; 为空时用模型默认("只输出图像中的文本内容, 不附加描述或格式") */
        private String prompt = "";
    }
}
