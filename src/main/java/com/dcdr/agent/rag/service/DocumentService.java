package com.dcdr.agent.rag.service;

import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.entity.DocumentChunkEntity;
import com.dcdr.agent.entity.DocumentEntity;
import com.dcdr.agent.entity.KnowledgeBaseEntity;
import com.dcdr.agent.rag.config.RagProperties;
import com.dcdr.agent.rag.embedding.EmbeddingProvider;
import com.dcdr.agent.rag.embedding.EmbeddingRegistry;
import com.dcdr.agent.rag.reader.DocumentReader;
import com.dcdr.agent.rag.reader.DocumentReaderRegistry;
import com.dcdr.agent.rag.splitter.TextSplitter;
import com.dcdr.agent.repository.DocumentChunkRepository;
import com.dcdr.agent.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * 文档管理 Service (V2.1, 对应 F-20 / F-21-002)。
 * <p>
 * 上传流程(同步部分, 走 WebFlux):
 * <ol>
 *   <li>校验扩展名与大小</li>
 *   <li>落盘到 {@code {storage-dir}/{kbId}/{docId}.{ext}}</li>
 *   <li>创建 DocumentEntity(status=pending) 并落库</li>
 *   <li>触发 {@link #processDocumentAsync(String)} 异步执行解析+分块+向量化</li>
 * </ol>
 * 异步流程({@code ragExecutor} 线程):
 * <pre>
 *   读文件 → DocumentReader 解析为纯文本 → TextSplitter 分块
 *         → EmbeddingProvider 批量向量化 → 落库 DocumentChunkEntity
 *         → 更新 DocumentEntity.status = completed | failed
 * </pre>
 * <p>
 * <b>事务策略</b>: 与 {@code AgentOrchestrator} 一致, 不在 service 方法上加 @Transactional
 * (避免反应式调度下自调用绕过代理的陷阱), 依赖 {@code SimpleJpaRepository} 自带的方法级事务。
 * 需要原子的多步操作(如删 chunks + 删 document)用 {@code @Transactional} 标注的 public 方法,
 * 由外部调用经 Spring 代理进入。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final KnowledgeBaseService knowledgeBaseService;
    private final DocumentReaderRegistry readerRegistry;
    private final TextSplitter splitter;
    private final EmbeddingRegistry embeddingRegistry;
    private final RagProperties properties;
    /** RAG 异步入库专用 Scheduler(由 ragExecutor 派生), 注入即用, 无需 @Qualifier */
    private final Scheduler ragScheduler;

    /** 单文件大小硬上限(字节), 兜底防止配置失误导致 OOM */
    private static final long HARD_MAX_BYTES = 50L * 1024 * 1024;
    private static final Pattern EXT_PATTERN = Pattern.compile("\\.([A-Za-z0-9]+)$");

    /**
     * 上传文档(同步部分): 校验 → 落盘 → 落库(pending) → 触发异步入库。
     * <p>
     * 返回上传后的 DocumentEntity(status=pending, 真正入库在异步线程完成后才变 completed)。
     */
    public Mono<DocumentEntity> upload(String kbId, FilePart filePart, String userId) {
        if (!properties.isEnabled()) {
            return Mono.error(new BusinessException(503, "RAG 功能未启用", "RAG_DISABLED"));
        }
        // 1. 校验知识库存在 + 文档数未超上限
        KnowledgeBaseEntity kb = knowledgeBaseService.get(kbId);
        int maxDocs = properties.getDocument().getMaxDocsPerKb();
        long existing = documentRepository.findByKbIdOrderByCreatedAtDesc(kbId).size();
        if (existing >= maxDocs) {
            return Mono.error(new BusinessException(400, "知识库文档数已达上限(" + maxDocs + ")", "KB_DOC_LIMIT"));
        }

        String filename = filePart.filename();
        String ext = extractExtension(filename);
        if (!readerRegistry.supportedExtensions().contains(ext)) {
            return Mono.error(new BusinessException(400,
                    "不支持的文件格式: " + ext + " (支持: " + readerRegistry.supportedExtensions() + ")",
                    "UNSUPPORTED_FORMAT"));
        }
        long maxBytes = parseMaxBytes(properties.getDocument().getMaxFileSize());

        // 2. 生成 docId + 目标路径
        String docId = generateId("doc_");
        Path target = Paths.get(properties.getStorageDir(), kbId, docId + "." + ext);
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException e) {
            return Mono.error(new BusinessException(500, "创建存储目录失败: " + e.getMessage()));
        }

        // 3. 落盘 → 落库(pending) → 触发异步处理
        final String fileType = ext;
        return filePart.transferTo(target.toFile())
                .then(Mono.fromCallable(() -> {
                    long size = Files.size(target);
                    if (size > maxBytes) {
                        tryDelete(target);
                        throw new BusinessException(400,
                                "文件超过大小上限(" + properties.getDocument().getMaxFileSize() + ")", "FILE_TOO_LARGE");
                    }
                    DocumentEntity doc = new DocumentEntity();
                    doc.setId(docId);
                    doc.setKbId(kbId);
                    doc.setUserId(userId != null ? userId : kb.getUserId());
                    doc.setFilename(filename);
                    doc.setFileType(fileType);
                    doc.setFileSize(size);
                    doc.setChunkCount(0);
                    doc.setStatus("pending");
                    LocalDateTime now = LocalDateTime.now();
                    doc.setCreatedAt(now);
                    doc.setUpdatedAt(now);
                    documentRepository.save(doc);
                    knowledgeBaseService.incrementDocCount(kbId);
                    log.info("文档上传成功: {} (kb={}, file={}, size={}B), 触发异步入库", docId, kbId, filename, size);
                    // 异步处理: fire-and-forget, 不阻塞上传响应
                    processDocumentAsync(docId);
                    return doc;
                }).subscribeOn(Schedulers.boundedElastic()))
                .onErrorResume(e -> {
                    if (e instanceof BusinessException) return Mono.error(e);
                    return Mono.error(new BusinessException(500, "上传失败: " + e.getMessage()));
                });
    }

    /** 文档列表 */
    public List<DocumentEntity> listDocuments(String kbId) {
        knowledgeBaseService.get(kbId); // 校验存在
        return documentRepository.findByKbIdOrderByCreatedAtDesc(kbId);
    }

    /** 单个文档 */
    public DocumentEntity getDocument(String docId) {
        return documentRepository.findById(docId)
                .orElseThrow(() -> new BusinessException(404, "文档不存在: " + docId, "DOC_NOT_FOUND"));
    }

    /**
     * 删除文档: 删库 + 删向量 + 删落盘文件。
     * <p>
     * 标 {@code @Transactional} 保证删 chunks 与删 document 原子(经 Spring 代理进入, 非自调用)。
     */
    @Transactional
    public void deleteDocument(String docId) {
        DocumentEntity doc = getDocument(docId);
        chunkRepository.deleteAll(chunkRepository.findByDocIdOrderByChunkIndexAsc(docId));
        documentRepository.delete(doc);
        knowledgeBaseService.decrementDocCount(doc.getKbId());
        tryDelete(docPath(doc));
        log.info("文档已删除: {} (file={})", docId, doc.getFilename());
    }

    /**
     * 重新解析/向量化文档: 删除旧 chunks → 重置状态 → 触发异步入库。
     */
    public void reprocess(String docId) {
        DocumentEntity doc = getDocument(docId);
        chunkRepository.deleteAll(chunkRepository.findByDocIdOrderByChunkIndexAsc(docId));
        doc.setStatus("pending");
        doc.setErrorMsg(null);
        doc.setUpdatedAt(LocalDateTime.now());
        documentRepository.save(doc);
        log.info("文档重新入库: {}", docId);
        processDocumentAsync(docId);
    }

    // ===== 异步入库 =====

    /**
     * 异步调度文档入库: 在 {@code ragScheduler} 上执行 {@link #doProcessDocument}。
     * <p>
     * fire-and-forget: 调用方不等待结果; 失败在 doProcessDocument 内部捕获并更新 status=failed。
     */
    private void processDocumentAsync(String docId) {
        Mono.fromRunnable(() -> doProcessDocument(docId))
                .subscribeOn(ragScheduler)
                .subscribe(v -> {}, e -> log.error("异步入库调度异常: {}", docId, e));
    }

    /**
     * 实际的入库逻辑(运行在 ragExecutor 线程)。
     * <p>
     * 不加 {@code @Transactional}: 各 repository 调用自带方法级事务(SimpleJpaRepository);
     * chunk 批量落库用 {@code saveAll} 自身原子, 失败则整体回滚到 status=failed, 数据一致。
     */
    protected void doProcessDocument(String docId) {
        DocumentEntity doc = documentRepository.findById(docId).orElse(null);
        if (doc == null) {
            log.warn("异步入库: 文档不存在 {}", docId);
            return;
        }
        // 1. 置为 indexing
        doc.setStatus("indexing");
        doc.setUpdatedAt(LocalDateTime.now());
        documentRepository.save(doc);

        try {
            // 2. 读文件 + 解析为文本
            Path path = docPath(doc);
            if (!Files.exists(path)) {
                throw new IllegalStateException("落盘文件不存在: " + path);
            }
            DocumentReader reader = readerRegistry.get(doc.getFileType());
            String text;
            try (InputStream is = new FileSystemResource(path.toFile()).getInputStream()) {
                text = reader.read(is);
            }
            if (text == null || text.isBlank()) {
                throw new IllegalStateException("文档解析后无可用的文本内容(可能是扫描版 PDF 或空文档)");
            }

            // 3. 分块
            List<String> chunks = splitter.split(text);
            if (chunks.isEmpty()) {
                throw new IllegalStateException("分块结果为空");
            }

            // 4. 批量向量化
            // 构造元信息前缀注入每个片段, 使向量包含上下文语义, 解决"元信息含语义但正文没有"导致检索漏命中。
            // 采用方案二(避免向量语义稀释):
            //   - chunk #0: 完整前缀 [知识库名 | 描述 | 文件名], 保证可被库名/描述命中
            //   - chunk #1+: 仅文件名 [文件名], 保留片段内容语义纯度, 避免前缀拉平向量差异
            List<String> enrichedChunks = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                enrichedChunks.add(buildChunkPrefix(doc, i) + chunks.get(i));
            }

            EmbeddingProvider embedding = embeddingRegistry.getDefault();
            List<float[]> vectors = embedding.embedBatch(enrichedChunks);
            if (vectors.size() != enrichedChunks.size()) {
                log.warn("向量化数量({})与分块数量({})不一致, 按最小值落库", vectors.size(), enrichedChunks.size());
            }

            // 5. 落库 chunks (存储带前缀的内容, 检索时直接可用, 无需二次拼接)
            int n = Math.min(enrichedChunks.size(), vectors.size());
            List<DocumentChunkEntity> entities = new ArrayList<>(n);
            String kbId = doc.getKbId();
            String userId = doc.getUserId();
            LocalDateTime now = LocalDateTime.now();
            for (int i = 0; i < n; i++) {
                DocumentChunkEntity chunk = new DocumentChunkEntity();
                chunk.setId(generateId("chunk_"));
                chunk.setDocId(docId);
                chunk.setKbId(kbId);
                chunk.setUserId(userId);
                chunk.setChunkIndex(i);
                chunk.setContent(sanitizeContent(enrichedChunks.get(i)));
                chunk.setEmbedding(vectors.get(i));
                chunk.setTokenCount(estimateTokens(enrichedChunks.get(i)));
                chunk.setCreatedAt(now);
                entities.add(chunk);
            }
            chunkRepository.saveAll(entities);

            // 6. 更新文档状态
            doc.setChunkCount(n);
            doc.setStatus("completed");
            doc.setErrorMsg(null);
            doc.setUpdatedAt(LocalDateTime.now());
            documentRepository.save(doc);
            log.info("文档入库完成: {} ({} 块, {} 维向量, 模型={})",
                    docId, n, embedding.getDimensions(), embedding.getName());

        } catch (Exception e) {
            log.error("文档入库失败: {}", docId, e);
            doc.setStatus("failed");
            String msg = e.getMessage();
            if (msg != null && msg.length() > 500) msg = msg.substring(0, 500);
            doc.setErrorMsg(msg);
            doc.setUpdatedAt(LocalDateTime.now());
            documentRepository.save(doc);
        }
    }

    // ===== 工具方法 =====

    /**
     * 构造片段元信息前缀。
     * 方案二(避免向量语义稀释):
     *   - chunk #0: 完整前缀 [知识库名 | 描述 | 文件名], 保证可被库名/描述命中
     *   - chunk #1+: 仅文件名 [文件名], 保留片段内容语义纯度
     *
     * @param doc        文档实体
     * @param chunkIndex 片段序号(0-based)
     */
    private String buildChunkPrefix(DocumentEntity doc, int chunkIndex) {
        // 非首片: 只带文件名, 避免前缀重复稀释向量语义
        String filename = doc.getFilename();
        if (chunkIndex > 0) {
            if (filename == null || filename.isBlank()) return "";
            return "[文件: " + filename + "]\n";
        }

        // 首片: 完整元信息 [知识库名 | 描述 | 文件名]
        StringBuilder sb = new StringBuilder("[");
        try {
            KnowledgeBaseEntity kb = knowledgeBaseService.get(doc.getKbId());
            if (kb != null) {
                if (kb.getName() != null && !kb.getName().isBlank()) {
                    sb.append("知识库: ").append(kb.getName());
                }
                if (kb.getDescription() != null && !kb.getDescription().isBlank()) {
                    sb.append(" | ").append(kb.getDescription());
                }
            }
        } catch (Exception e) {
            log.warn("构造首片前缀时查询知识库失败, 跳过知识库元信息: {}", doc.getKbId(), e);
        }
        if (filename != null && !filename.isBlank()) {
            if (sb.length() > 1) sb.append(" | ");
            sb.append("文件: ").append(filename);
        }
        sb.append("]\n");
        return sb.toString();
    }

    private Path docPath(DocumentEntity doc) {
        return Paths.get(properties.getStorageDir(), doc.getKbId(), doc.getId() + "." + doc.getFileType());
    }

    private static String extractExtension(String filename) {
        if (filename == null) return "";
        var m = EXT_PATTERN.matcher(filename);
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : "";
    }

    /** 解析 "20MB"/"200KB"/"1G" 等大小字符串为字节数 */
    private static long parseMaxBytes(String size) {
        if (size == null || size.isBlank()) return HARD_MAX_BYTES;
        String s = size.trim().toUpperCase(Locale.ROOT);
        long mul = 1;
        if (s.endsWith("KB")) { mul = 1024L; s = s.substring(0, s.length() - 2); }
        else if (s.endsWith("MB")) { mul = 1024L * 1024; s = s.substring(0, s.length() - 2); }
        else if (s.endsWith("GB")) { mul = 1024L * 1024 * 1024; s = s.substring(0, s.length() - 2); }
        else if (s.endsWith("B")) { s = s.substring(0, s.length() - 1); }
        try {
            long v = Long.parseLong(s.trim());
            return Math.min(v * mul, HARD_MAX_BYTES);
        } catch (NumberFormatException e) {
            return HARD_MAX_BYTES;
        }
    }

    /**
     * 清洗文本中可能破坏 PostgreSQL UTF8 存储的非法字符, 尤其是 NUL 字节(0x00)。
     * <p>
     * PostgreSQL 的 UTF8 编码不允许字符串包含 0x00, 否则报
     * "invalid byte sequence for encoding 'UTF8': 0x00"。文档(PDF/Word 等)解析后
     * 文本常混入 NUL, 必须在落库前剔除, 否则整批 chunk 入库失败。
     * 同时剔除其它常见的不可见控制字符(C0/C1 控制块, 不含合法换行/制表)。
     */
    private static String sanitizeContent(String text) {
        if (text == null) return null;
        int len = text.length();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            // 剔除 NUL 以及 C0 控制字符(除 \t \n \r) 和 C1 控制字符
            if (c == 0 || (c < 0x20 && c != '\t' && c != '\n' && c != '\r') || (c >= 0x7F && c <= 0x9F)) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 粗略估算 token 数(中文按 1 字 ≈ 1 token, 英文按 4 字符 ≈ 1 token), 仅用于落库统计 */
    private static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) cjk++;
            else other++;
        }
        return cjk + other / 4;
    }

    private static void tryDelete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("删除落盘文件失败(忽略): {}", path, e);
        }
    }

    private static String generateId(String prefix) {
        return prefix + System.currentTimeMillis()
                + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000, 0xfffffff));
    }
}
