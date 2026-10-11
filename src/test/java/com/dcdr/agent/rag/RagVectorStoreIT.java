package com.dcdr.agent.rag;

import com.dcdr.agent.entity.DocumentChunkEntity;
import com.dcdr.agent.entity.DocumentEntity;
import com.dcdr.agent.entity.KnowledgeBaseEntity;
import com.dcdr.agent.entity.UserEntity;
import com.dcdr.agent.entity.VectorType;
import com.dcdr.agent.repository.DocumentChunkRepository;
import com.dcdr.agent.repository.DocumentRepository;
import com.dcdr.agent.repository.KnowledgeBaseRepository;
import com.dcdr.agent.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG 向量存储集成测试(V3.2 测试体系 —— 集成层)。
 *
 * <p><b>为什么必须有这一层</b>: 此前的测试全部是"零外部依赖"的单元/装配测试,
 * 它们刻意用 H2 或纯对象替代了真实数据库 —— 这意味着<b>所有与 PostgreSQL 的接缝都没有被验证过</b>:
 * <ul>
 *   <li>JPA 实体映射在真实 PG 上是否建得出表({@code vector(1536)} 列依赖 pgvector 扩展)</li>
 *   <li>{@link VectorType} 的 {@code float[]} ↔ pgvector 文本转换在真实读写链路上是否正确</li>
 *   <li>原生 SQL 的 pgvector 运算符({@code <=>} 余弦距离)与函数({@code to_tsvector})是否真的可用</li>
 *   <li>投影接口({@code ChunkWithDistance} / {@code ChunkWithFtsScore})的列名→getter 映射是否成立</li>
 * </ul>
 * 这几类缺陷的共同点是:<b>单元测试永远发现不了, 但一上真实环境就炸</b>。
 *
 * <p><b>运行方式</b>: 类名以 {@code IT} 结尾 → 由 failsafe 在 {@code mvn verify} 阶段执行,
 * 不影响 {@code mvn test} 的秒级离线体验。需要本机 Docker 可用;
 * 无 Docker 时经 {@code disabledWithoutDocker} 自动跳过(而非失败)。
 *
 * <p><b>数据库</b>: {@code pgvector/pgvector:pg16} 官方镜像 + {@code @ServiceConnection} 自动注入连接信息。
 *
 * <p><b>表结构与生产同构</b>: 由 {@code docs/sql/*.sql} 脚本创建(见 {@code application-it.yml}),
 * 而非让 Hibernate 从实体推导 —— 后者测不出"生产建库脚本本身是否正确",
 * 也测不出脚本里 JPA 生成不了的部分(如 FTS 依赖的 pg_trgm 扩展与 trigram GIN 索引)。
 * 代价是测试数据必须满足脚本声明的**外键约束**(见 {@link #persist})。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"test", "it"})
@Transactional
class RagVectorStoreIT {

    /** 向量维度: 必须与 {@code document_chunks.embedding vector(1536)} 一致 */
    private static final int DIM = 1536;

    /** 测试用的归属用户 id(建库脚本中 users 是所有业务表的父表) */
    private static final String TEST_USER_ID = "test-user";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16")
                    // 本机 Docker 为 20.10.8: 其旧 seccomp profile 会拦截 glibc 的 clone3 系统调用,
                    // 使容器内 initdb 的 popen fork 报 ENOMEM, 容器以退出码 1 结束
                    // (现象为 "database system is ready to accept connections" 永远等不到)。
                    // 与 docker-compose.yml 的 postgres 服务采用同样的绕过方式;
                    // 升级 Docker 到 20.10.10+ 后这两处都可移除。
                    .withCreateContainerCmdModifier(cmd ->
                            cmd.getHostConfig().withSecurityOpts(List.of("seccomp=unconfined")))
                    // 与 compose 保持一致(默认 64MB 对 PG 偏小)
                    .withSharedMemorySize(256L * 1024 * 1024)
                    .withInitScript("db/init-pgvector.sql");

    @Autowired
    private DocumentChunkRepository chunkRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 本次测试内已创建的父记录(测试类为 per-method 实例, 天然随方法重置) */
    private final Set<String> createdKbIds = new HashSet<>();
    private final Set<String> createdDocIds = new HashSet<>();
    private boolean userCreated = false;

    // ==================================================================
    //  前提一: pgvector 扩展
    // ==================================================================

    @Test
    @DisplayName("容器已启用 pgvector 扩展(vector 类型可用是全部向量测试的前提)")
    void vectorExtensionIsInstalled() {
        List<String> extensions = jdbcTemplate.queryForList(
                "SELECT extname FROM pg_extension WHERE extname = 'vector'", String.class);

        assertFalse(extensions.isEmpty(), "扩展未启用则 vector(1536) 列无法创建");
    }

    // ==================================================================
    //  前提二: 建库脚本可执行(改用 docs/sql 脚本建表后新增的价值)
    // ==================================================================

    @Test
    @DisplayName("建库脚本已启用 pg_trgm 扩展(靠实体推导建表不会生成)")
    void pgTrgmExtensionIsInstalled() {
        List<String> extensions = jdbcTemplate.queryForList(
                "SELECT extname FROM pg_extension WHERE extname = 'pg_trgm'", String.class);

        assertFalse(extensions.isEmpty(),
                "v3.2_rag_hybrid_schema.sql 应启用 pg_trgm —— 为空说明该脚本未被加载或执行失败");
    }

    @Test
    @DisplayName("FTS 依赖的 trigram GIN 索引已由建库脚本创建")
    void trigramGinIndexExists() {
        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'document_chunks'", String.class);

        assertTrue(indexes.contains("idx_chunk_content_trgm"),
                "缺少 FTS 加速索引(靠实体推导建表不会生成它); 当前索引: " + indexes);
    }

    // ==================================================================
    //  JPA 实体映射 + 自定义类型往返
    // ==================================================================

    @Test
    @DisplayName("实体写入 → 读回: float[] 向量经 VectorType 在真实 PG 上往返一致")
    void embeddingRoundTripsThroughVectorType() {
        float[] vector = unitVector(0);
        persist(chunk("c-round", "kb-a", "向量往返", vector));

        DocumentChunkEntity loaded = chunkRepository.findById("c-round").orElseThrow();

        assertNotNull(loaded.getEmbedding(), "embedding 应能从 vector 列读回为 float[]");
        assertEquals(DIM, loaded.getEmbedding().length, "维度应与 vector(1536) 一致");
        assertArrayEquals(vector, loaded.getEmbedding(), 1e-6f, "向量读写往返不应失真");
    }

    @Test
    @DisplayName("embedding 为 null 的分块可正常存取(向量未就绪的文档不应导致写入失败)")
    void nullEmbeddingIsAllowed() {
        persist(chunk("c-null", "kb-a", "无向量", null));

        DocumentChunkEntity loaded = chunkRepository.findById("c-null").orElseThrow();
        assertEquals("无向量", loaded.getContent());
        assertNull(loaded.getEmbedding());
    }

    // ==================================================================
    //  原生 SQL: pgvector KNN 检索
    // ==================================================================

    @Test
    @DisplayName("KNN 检索: 按真实余弦距离排序, 与查询向量相同者排第一")
    void knnOrdersByCosineDistance() {
        persist(
                chunk("c1", "kb-a", "与查询完全一致", unitVector(0)),
                chunk("c2", "kb-a", "与查询正交", unitVector(1)),
                chunk("c3", "kb-a", "与查询部分相似", halfVector()));

        List<DocumentChunkEntity> top = chunkRepository.findTopKByKbId(
                "kb-a", VectorType.toVectorString(unitVector(0)), 3);

        // 余弦距离: c1=0(同向) < c3≈0.293(45°) < c2=1(正交)
        assertEquals(List.of("c1", "c3", "c2"),
                top.stream().map(DocumentChunkEntity::getId).toList(),
                "应按 <=> 余弦距离升序返回");
    }

    @Test
    @DisplayName("findTopKWithDistance 返回真实余弦距离(V3.0 修复: 不再按排序位置估算)")
    void distanceIsMeasuredNotEstimated() {
        persist(
                chunk("d1", "kb-a", "同向", unitVector(0)),
                chunk("d2", "kb-a", "正交", unitVector(1)));

        List<DocumentChunkRepository.ChunkWithDistance> rows = chunkRepository.findTopKWithDistance(
                "kb-a", VectorType.toVectorString(unitVector(0)), 2);

        assertEquals(2, rows.size());
        assertEquals(0.0, rows.get(0).getDistance(), 1e-4,
                "与查询向量同向 → 余弦距离应为 0");
        assertEquals(1.0, rows.get(1).getDistance(), 1e-4,
                "与查询向量正交 → 余弦距离应为 1(若按排名估算则会得到人为的等差值)");
    }

    @Test
    @DisplayName("topK 截断生效")
    void respectsTopKLimit() {
        persist(
                chunk("t1", "kb-a", "第一", unitVector(0)),
                chunk("t2", "kb-a", "第二", unitVector(1)),
                chunk("t3", "kb-a", "第三", unitVector(2)));

        List<DocumentChunkEntity> top = chunkRepository.findTopKByKbId(
                "kb-a", VectorType.toVectorString(unitVector(0)), 2);

        assertEquals(2, top.size());
    }

    @Test
    @DisplayName("知识库隔离: 检索只返回指定 kb 的分块")
    void isolatesByKnowledgeBase() {
        persist(
                chunk("a1", "kb-a", "A 库内容", unitVector(0)),
                chunk("b1", "kb-b", "B 库内容", unitVector(0)));

        List<DocumentChunkEntity> top = chunkRepository.findTopKByKbId(
                "kb-a", VectorType.toVectorString(unitVector(0)), 10);

        assertEquals(1, top.size(), "跨库泄漏是严重缺陷, 必须被隔离");
        assertEquals("a1", top.get(0).getId());
    }

    @Test
    @DisplayName("embedding 为 null 的分块被检索排除(WHERE embedding IS NOT NULL)")
    void excludesChunksWithoutEmbedding() {
        persist(
                chunk("e1", "kb-a", "有向量", unitVector(0)),
                chunk("e2", "kb-a", "无向量", null));

        List<DocumentChunkEntity> top = chunkRepository.findTopKByKbId(
                "kb-a", VectorType.toVectorString(unitVector(0)), 10);

        assertEquals(1, top.size());
        assertEquals("e1", top.get(0).getId());
    }

    // ==================================================================
    //  原生 SQL: Postgres 全文检索(混合检索的 FTS 路径)
    // ==================================================================

    @Test
    @DisplayName("FTS 全文检索: 关键词命中, 且 ts_rank_cd 分值 > 0")
    void fullTextSearchMatchesKeyword() {
        persist(
                chunk("f1", "kb-a", "postgresql full text search is powerful", unitVector(0)),
                chunk("f2", "kb-a", "a totally unrelated article about cooking", unitVector(1)));

        List<DocumentChunkRepository.ChunkWithFtsScore> hits =
                chunkRepository.findTopKByKbFts("kb-a", "postgresql", 10);

        assertEquals(1, hits.size(), "只有含该关键词的分块应命中");
        assertEquals("f1", hits.get(0).getId());
        assertTrue(hits.get(0).getScore() > 0, "ts_rank_cd 分值应大于 0");
    }

    @Test
    @DisplayName("FTS 全文检索: 无匹配关键词时返回空集(不抛异常)")
    void fullTextSearchReturnsEmptyWhenNoMatch() {
        persist(chunk("f3", "kb-a", "content without the keyword", unitVector(0)));

        List<DocumentChunkRepository.ChunkWithFtsScore> hits =
                chunkRepository.findTopKByKbFts("kb-a", "zzzznotexist", 10);

        assertTrue(hits.isEmpty());
    }

    // ==================================================================
    //  helper
    // ==================================================================

    /**
     * 写入分块并 flush(确保后续的原生 SQL 查询能看到数据)。
     *
     * <p><b>为什么要先建父记录</b>: 真实建库脚本声明了完整的外键链 ——
     * {@code document_chunks.doc_id → documents(id)}、{@code kb_id → knowledge_bases(id)}、
     * {@code knowledge_bases.user_id → users(id)}; 而 JPA 实体只用普通 {@code String} 列表达这些
     * 关联字段、没有 {@code @ManyToOne}, 因此"实体推导建表"时并无外键, 凭空插入也能成功。
     * 改用真实脚本后必须自底向上补齐 users → knowledge_bases → documents ——
     * 这正是本 profile 要暴露的差异: <b>测试数据必须满足真实 schema 的约束</b>。
     */
    private void persist(DocumentChunkEntity... chunks) {
        for (DocumentChunkEntity chunk : chunks) {
            ensureKnowledgeBase(chunk.getKbId());
            ensureDocument(chunk.getDocId(), chunk.getKbId());
        }
        chunkRepository.saveAll(List.of(chunks));
        chunkRepository.flush();
    }

    private void ensureUser() {
        if (userCreated) {
            return;
        }
        UserEntity user = new UserEntity();
        user.setId(TEST_USER_ID);
        user.setUsername(TEST_USER_ID);
        user.setPasswordHash("not-a-real-hash");   // 测试不涉及认证, 仅为满足 NOT NULL
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
        userCreated = true;
    }

    private void ensureKnowledgeBase(String kbId) {
        if (kbId == null || !createdKbIds.add(kbId)) {
            return;
        }
        ensureUser();
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setId(kbId);
        kb.setUserId(TEST_USER_ID);
        kb.setName("测试知识库 " + kbId);
        kb.setCreatedAt(LocalDateTime.now());
        kb.setUpdatedAt(LocalDateTime.now());
        knowledgeBaseRepository.save(kb);
    }

    private void ensureDocument(String docId, String kbId) {
        if (docId == null || !createdDocIds.add(docId)) {
            return;
        }
        DocumentEntity doc = new DocumentEntity();
        doc.setId(docId);
        doc.setKbId(kbId);
        doc.setUserId(TEST_USER_ID);
        doc.setFilename(docId + ".txt");
        doc.setCreatedAt(LocalDateTime.now());
        doc.setUpdatedAt(LocalDateTime.now());
        documentRepository.save(doc);
    }

    private static DocumentChunkEntity chunk(String id, String kbId, String content, float[] embedding) {
        DocumentChunkEntity entity = new DocumentChunkEntity();
        entity.setId(id);
        entity.setDocId("doc-" + id);
        entity.setKbId(kbId);
        entity.setUserId(TEST_USER_ID);
        entity.setChunkIndex(0);
        entity.setContent(content);
        entity.setEmbedding(embedding);
        entity.setCreatedAt(LocalDateTime.now());
        return entity;
    }

    /** 第 pos 位为 1 的单位向量(其余为 0) */
    private static float[] unitVector(int pos) {
        float[] v = new float[DIM];
        v[pos] = 1.0f;
        return v;
    }

    /** 与 {@code unitVector(0)} 夹角 45° 的单位向量: 余弦距离 = 1 - √2/2 ≈ 0.293 */
    private static float[] halfVector() {
        float[] v = new float[DIM];
        float c = (float) (1.0 / Math.sqrt(2));
        v[0] = c;
        v[1] = c;
        return v;
    }
}
