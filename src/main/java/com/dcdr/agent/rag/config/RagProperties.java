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
        /** 分块策略: fixed | paragraph | recursive (当前实现 recursive) */
        private String strategy = "recursive";
        /** 每块目标字符数(与 schema knowledge_bases.chunk_size 默认 1000 一致) */
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
