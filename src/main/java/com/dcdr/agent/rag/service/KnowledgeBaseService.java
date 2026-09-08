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
 * <b>userId 处理与越权防护</b>: 已接入多用户认证(V2.2+)。知识库按 {@code userId} 归属,
 * 并区分 {@code visibility}(USER 用户级 / PLATFORM 平台级):
 * <ul>
 *   <li>admin: 可访问/修改任意知识库</li>
 *   <li>普通用户: 仅可访问/修改自己创建的 USER 级; PLATFORM 级只读</li>
 *   <li>未登录(认证关闭): userId 为 null, 退化为单用户视角(兼容历史部署方式)</li>
 * </ul>
 * 所有外部入口(Controller)必须经 {@link #getAccessible(String, String, boolean, boolean)} 校验,
 * 避免用户跨账号访问/篡改他人知识库。
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

    /**
     * 校验用户对知识库的访问权并返回知识库(防越权统一入口)。
     * <ul>
     *   <li>admin: 任意知识库可访问/可写</li>
     *   <li>普通用户(userId != null 且非 admin):
     *       USER 级仅可访问/写自己的; PLATFORM 级只读(write=true 时拒绝)</li>
     *   <li>匿名(userId == null, 认证关闭): 兼容 V2.1, 可访问任意 USER 级(写由上层决定)</li>
     * </ul>
     *
     * @param write true 表示写操作(创建文档/更新/删除), false 表示读操作(查看/检索/上传受限)
     */
    public KnowledgeBaseEntity getAccessible(String kbId, String userId, boolean isAdmin, boolean write) {
        KnowledgeBaseEntity kb = get(kbId);
        if (isAdmin) return kb;
        boolean platform = "PLATFORM".equals(kb.getVisibility());
        // 平台级: 对所有登录用户只读可见; 写操作仅 admin 可做
        if (platform) {
            if (write) {
                throw new BusinessException(403, "平台级知识库仅管理员可修改", "FORBIDDEN");
            }
            return kb;
        }
        // 用户级: 仅创建者可访问(未登录/匿名退化为放行, 兼容 V2.1 单用户)
        if (userId != null && !userId.equals(kb.getUserId())) {
            throw new BusinessException(404, "知识库不存在: " + kbId, "KB_NOT_FOUND");
        }
        return kb;
    }

    public List<KnowledgeBaseEntity> list(String userId) {
        validateEnabled();
        // 仅返回该用户的 USER 级知识库(平台级不在此接口返回, 由 Controller 单独合并)
        if (userId == null) {
            // 匿名/认证关闭: 返回全部 USER 级(V2.1 单用户兜底)
            return new ArrayList<>(kbRepository.findAll().stream()
                    .filter(kb -> "USER".equals(kb.getVisibility()))
                    .toList());
        }
        return new ArrayList<>(kbRepository.findByVisibilityAndUserIdOrderByUpdatedAtDesc("USER", userId));
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
    public KnowledgeBaseEntity update(String kbId, String name, String description, String userId, boolean isAdmin) {
        validateEnabled();
        KnowledgeBaseEntity kb = getAccessible(kbId, userId, isAdmin, true);
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
    public void delete(String kbId, String userId, boolean isAdmin) {
        validateEnabled();
        KnowledgeBaseEntity kb = getAccessible(kbId, userId, isAdmin, true);
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
