package com.dcdr.agent.rag.service;

import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.entity.DocumentEntity;
import com.dcdr.agent.entity.KnowledgeBaseEntity;
import com.dcdr.agent.rag.config.RagProperties;
import com.dcdr.agent.repository.DocumentChunkRepository;
import com.dcdr.agent.repository.DocumentRepository;
import com.dcdr.agent.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 知识库管理 Service (V2.1, 对应 F-21)。
 * <p>
 * 提供知识库 CRUD; 删除时级联清理文档与向量(数据库 FK ON DELETE CASCADE 已兜底,
 * 此处显式调 repository 便于日志与计数同步)。
 * <p>
 * <b>userId 处理</b>: V2.1 尚未上认证(V2.2 才做), 当前以单用户视角运行,
 * userId 入参允许为 null 或固定值; 表结构已预留 user_id 列, V2.2 接入认证后无需改 schema。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final KnowledgeBaseRepository kbRepository;
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final RagProperties properties;

    @Transactional
    public KnowledgeBaseEntity create(String name, String description, String userId, String visibility) {
        validateEnabled();
        if (name == null || name.isBlank()) {
            throw new BusinessException(400, "知识库名称不能为空");
        }
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setId(generateId("kb_"));
        kb.setUserId(userId);
        kb.setName(name.trim());
        kb.setDescription(description);
        // visibility: USER(用户级, 默认) 或 PLATFORM(平台级, 对所有对话无感可用)
        kb.setVisibility("PLATFORM".equalsIgnoreCase(visibility) ? "PLATFORM" : "USER");
        // 复用 schema 默认值, 显式赋一次保证与 RagProperties 一致
        kb.setEmbeddingModel(properties.getEmbedding().getDefaultProvider());
        kb.setChunkSize(properties.getSplitter().getChunkSize());
        kb.setChunkOverlap(properties.getSplitter().getChunkOverlap());
        kb.setDocCount(0);
        kb.setStatus("ready");
        LocalDateTime now = LocalDateTime.now();
        kb.setCreatedAt(now);
        kb.setUpdatedAt(now);
        kbRepository.save(kb);
        log.info("创建知识库: {} (name={}, user={}, visibility={})", kb.getId(), kb.getName(), userId, kb.getVisibility());
        return kb;
    }

    public KnowledgeBaseEntity get(String kbId) {
        validateEnabled();
        KnowledgeBaseEntity kb = kbRepository.findById(kbId)
                .orElseThrow(() -> new BusinessException(404, "知识库不存在: " + kbId, "KB_NOT_FOUND"));
        return kb;
    }

    public List<KnowledgeBaseEntity> list(String userId) {
        validateEnabled();
        // 仅返回用户级(visibility=USER)知识库, 平台级(PLATFORM)不暴露给普通用户
        if (userId == null) {
            // userId 为 null 时返回全部用户级(单用户兜底)
            return new ArrayList<>(kbRepository.findAll().stream()
                    .filter(kb -> "USER".equals(kb.getVisibility()))
                    .toList());
        }
        List<KnowledgeBaseEntity> list = kbRepository.findByVisibilityAndUserIdOrderByUpdatedAtDesc("USER", userId);
        // 兜底: userId 维度查不到时退化为全部用户级(V2.1 单用户场景)
        if (list.isEmpty()) {
            list = new ArrayList<>(kbRepository.findAll().stream()
                    .filter(kb -> "USER".equals(kb.getVisibility()))
                    .toList());
        }
        return list;
    }

    /**
     * 查询平台级知识库(visibility=PLATFORM)。
     * <p>平台级知识库由管理员维护, 普通用户不可见, 但对话时通过配置自动加入 RAG 检索范围。
     */
    public List<KnowledgeBaseEntity> listPlatform() {
        validateEnabled();
        return kbRepository.findByVisibilityOrderByUpdatedAtDesc("PLATFORM");
    }

    /**
     * 查询全部知识库(管理界面用, 不区分 visibility)。
     */
    public List<KnowledgeBaseEntity> listAll() {
        validateEnabled();
        return kbRepository.findAll();
    }

    @Transactional
    public KnowledgeBaseEntity update(String kbId, String name, String description) {
        validateEnabled();
        KnowledgeBaseEntity kb = get(kbId);
        if (name != null && !name.isBlank()) kb.setName(name.trim());
        if (description != null) kb.setDescription(description);
        kb.setUpdatedAt(LocalDateTime.now());
        return kbRepository.save(kb);
    }

    /**
     * 删除知识库: 级联删除文档(含落盘文件)与向量。
     * DB 层 FK ON DELETE CASCADE 已保证 document_chunks / documents 跟随删除,
     * 此处主要做落盘文件清理 + 计数日志。
     */
    @Transactional
    public void delete(String kbId) {
        validateEnabled();
        KnowledgeBaseEntity kb = get(kbId);
        List<DocumentEntity> docs = documentRepository.findByKbIdOrderByCreatedAtDesc(kbId);
        // 先删 chunks(防止部分 DB FK 未配 CASCADE 时残留)
        for (DocumentEntity doc : docs) {
            chunkRepository.deleteAll(chunkRepository.findByDocIdOrderByChunkIndexAsc(doc.getId()));
        }
        documentRepository.deleteAll(docs);
        kbRepository.delete(kb);
        log.info("删除知识库: {} ({} 个文档)", kbId, docs.size());
        // 落盘文件清理留给 DocumentService 处理(此处不引入 storage 依赖); 当前实现见 DocumentService.deleteDocumentFiles
    }

    /** 文档上传后由 DocumentService 调用, 同步更新文档计数与时间戳 */
    @Transactional
    public void incrementDocCount(String kbId) {
        kbRepository.findById(kbId).ifPresent(kb -> {
            kb.setDocCount(kb.getDocCount() + 1);
            kb.setUpdatedAt(LocalDateTime.now());
            kbRepository.save(kb);
        });
    }

    /** 文档删除后同步递减计数(不低于 0) */
    @Transactional
    public void decrementDocCount(String kbId) {
        kbRepository.findById(kbId).ifPresent(kb -> {
            kb.setDocCount(Math.max(0, kb.getDocCount() - 1));
            kb.setUpdatedAt(LocalDateTime.now());
            kbRepository.save(kb);
        });
    }

    private void validateEnabled() {
        if (!properties.isEnabled()) {
            throw new BusinessException(503, "RAG 功能未启用 (agent.rag.enabled=false)", "RAG_DISABLED");
        }
    }

    /**
     * 生成业务 ID: 前缀 + 时间戳 + 4 位随机十六进制, 避免同毫秒并发冲突。
     * 与 SessionManager 的 sess_ 风格一致, 比 UUID 更短可读。
     */
    private static String generateId(String prefix) {
        return prefix + System.currentTimeMillis()
                + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000, 0xfffffff));
    }
}
