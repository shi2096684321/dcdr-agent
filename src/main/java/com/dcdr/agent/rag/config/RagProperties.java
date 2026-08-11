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
}
