package com.dcdr.agent.rag;

import com.dcdr.agent.entity.DocumentChunkEntity;
import com.dcdr.agent.entity.DocumentEntity;
import com.dcdr.agent.entity.KnowledgeBaseEntity;
import com.dcdr.agent.entity.UserEntity;
import com.dcdr.agent.rag.config.RagProperties;
import com.dcdr.agent.rag.dto.RetrievalResult;
import com.dcdr.agent.rag.embedding.EmbeddingProvider;
import com.dcdr.agent.rag.embedding.EmbeddingRegistry;
import com.dcdr.agent.rag.eval.RetrievalEvalMetrics;
import com.dcdr.agent.rag.eval.RetrievalEvalMetrics.EvalCase;
import com.dcdr.agent.rag.eval.RetrievalEvalMetrics.Report;
import com.dcdr.agent.rag.eval.RetrievalEvalMetrics.Thresholds;
import com.dcdr.agent.rag.service.RetrievalService;
import com.dcdr.agent.rag.splitter.RecursiveSplitter;
import com.dcdr.agent.repository.DocumentChunkRepository;
import com.dcdr.agent.repository.DocumentRepository;
import com.dcdr.agent.repository.KnowledgeBaseRepository;
import com.dcdr.agent.repository.UserRepository;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * RAG 检索质量评测 (Offline Eval) —— 测试体系的"效果评估层"。
 *
 * <p><b>与其它测试的本质区别</b>: 单元测试断言"代码是否按预期运行"(确定性、非黑即白),
 * 而本类衡量的是<b>召回质量</b>(好/较好/差)。二者不能互相替代 ——
 * {@link RagVectorStoreIT} 已经验证了"KNN 能不能排序、距离算得对不对"这类<b>机制</b>,
 * 但它用的是单位向量, 因此<b>回答不了"该找的文档到底有没有被找出来"</b>。
 * 本类用<b>真实 Embedding</b> + 人工标注的期望命中, 回答后一个问题。
 *
 * <p><b>为什么用 documentId 做命中判定</b>: 一个文档会被切成多个分块,
 * 而"检索到该文档的某个分块"即已满足业务需要。按 chunkId 判定会虚低 ——
 * 命中同一文档的另一个分块本应算成功。
 *
 * <p><b>为什么问题措辞与文档不同</b>: 见黄金集内 {@code _comment}。若照抄文档原词,
 * 测出来的会是"关键词匹配"而非"语义召回", 指标虚高、失去意义。
 *
 * <p><b>门控(两道)</b>:
 * <ul>
 *   <li>需 {@code DASHSCOPE_API_KEY}: 语料与问题都要真调 Embedding 接口
 *       (成本很低 —— 全部语料一次批量, 但这仍属"会产生费用"的测试, 故不设默认执行);</li>
 *   <li>需 Docker: 容器在类加载时自启动, 启动失败则由 {@code @BeforeAll} 的
 *       {@code assumeTrue} 整类跳过(详见 {@code POSTGRES} 字段的说明)。</li>
 * </ul>
 * 两者任一不满足即跳过, 不会失败 —— 因为效果评测本就不该绑架 {@code mvn test}/{@code verify}。
 *
 * <p><b>指标怎么用(企业实践)</b>: 合格 = <b>不低于基线</b>, 而不是追求绝对高分。
 * 首次运行记录实测值作为基线; 此后每次改动检索/切分/prompt 后重跑,
 * 明显低于基线即说明退化。详见 {@link #retrievalQualityMeetsBaseline}。
 *
 * <p><b>运行方式</b>: 类名以 {@code IT} 结尾 → 归 failsafe(即 {@code mvn verify} 阶段)。
 * 需要本机 Docker + DASHSCOPE_API_KEY, 日常按需单独跑:
 * <pre>{@code mvn verify -Dit.test=RagRetrievalEvalIT}</pre>
 */
@EnabledIfEnvironmentVariable(named = "DASHSCOPE_API_KEY", matches = ".+")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        // test profile 默认关闭 RAG; 本类要跑真实检索, 故显式打开, 并关掉两个可选增强,
        // 使基线稳定在"纯向量"语义上(hybrid/rerank 的增益应作为独立对比项来测)。
        properties = {
                "agent.rag.enabled=true",
                "agent.rag.retrieval.hybrid.enabled=false",
                "agent.rag.retrieval.rerank.enabled=false"
        })
@ActiveProfiles({"test", "it"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RagRetrievalEvalIT {

    private static final String GOLDEN_PATH = "eval/retrieval-golden.json";
    private static final String TEST_USER_ID = "eval-user";

    /**
     * 容器在<b>类加载时</b>自行启动, 刻意不用 {@code @Testcontainers} / {@code @Container}。
     *
     * <p>原因(实测踩坑): 两者都借助 {@code BeforeAllCallback} —— Testcontainers 用它启动容器,
     * Spring 用它加载上下文; 而多个扩展的执行顺序由注解组合决定, <b>并无保证</b>。
     * 一旦 Spring 先执行, 取数据源时容器尚未启动, 就会报
     * {@code Mapped port can only be obtained after the container is started}。
     * 放进 static 初始化, 时序完全确定: 类加载 → 容器就绪 → 上下文加载。
     */
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            // 本机 Docker 20.10.8 的旧 seccomp 会拦截 clone3, 使容器内 initdb fork 失败;
            // 与 docker-compose.yml / RagVectorStoreIT 采用同样的绕过方式。
            .withCreateContainerCmdModifier(cmd ->
                    cmd.getHostConfig().withSecurityOpts(List.of("seccomp=unconfined")))
            .withSharedMemorySize(256L * 1024 * 1024)
            .withInitScript("db/init-pgvector.sql");

    static {
        try {
            POSTGRES.start();
        } catch (Exception e) {
            // 无 Docker 等情形: 不在此处中断类初始化, 交给 @BeforeAll 里的 assumeTrue 跳过
            System.err.println("pgvector 容器启动失败, 检索质量评测将被跳过: " + e.getMessage());
        }
    }

    /** 把容器连接信息交给 Spring(与 {@code @ServiceConnection} 等价, 但时序可控) */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @AfterAll
    static void stopContainer() {
        if (POSTGRES.isRunning()) {
            POSTGRES.stop();
        }
    }

    @Autowired
    private RetrievalService retrievalService;

    @Autowired
    private EmbeddingRegistry embeddingRegistry;

    @Autowired
    private RagProperties ragProperties;

    @Autowired
    private DocumentChunkRepository chunkRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Autowired
    private UserRepository userRepository;

    private Golden golden;

    // ==================================================================
    //  准备: 灌入语料(切分 → 真实 Embedding → 落库)
    // ==================================================================

    @BeforeAll
    void ingestGoldenCorpus() throws IOException {
        assumeTrue(POSTGRES.isRunning(), "pgvector 容器未启动(需要 Docker), 跳过检索质量评测");

        golden = loadGolden();

        assertFalse(golden.getDocuments().isEmpty(), "黄金集未配置 documents, 评测无从谈起");
        assertFalse(golden.getCases().isEmpty(), "黄金集未配置 cases, 评测无从谈起");

        EmbeddingProvider provider = embeddingRegistry.getDefault();
        RagProperties.SplitterConfig sp = ragProperties.getSplitter();
        RecursiveSplitter splitter = new RecursiveSplitter(sp.getChunkSize(), sp.getChunkOverlap());

        ensureUser();
        ensureKnowledgeBase(golden.getKbId());

        List<DocumentChunkEntity> chunks = new ArrayList<>();
        for (GoldenDoc doc : golden.getDocuments()) {
            ensureDocument(doc.getDocId(), doc.getFilename());

            // 用生产同一套分块器, 使"分块质量"也进入评测范围(切得不好会直接体现在召回率上)
            List<String> pieces = splitter.split(doc.getContent());
            List<float[]> vectors = provider.embedBatch(pieces);
            assertEquals(pieces.size(), vectors.size(), "Embedding 返回条数与入参不一致: " + doc.getDocId());

            for (int i = 0; i < pieces.size(); i++) {
                DocumentChunkEntity chunk = new DocumentChunkEntity();
                chunk.setId(doc.getDocId() + "-c" + i);
                chunk.setDocId(doc.getDocId());
                chunk.setKbId(golden.getKbId());
                chunk.setUserId(TEST_USER_ID);
                chunk.setChunkIndex(i);
                chunk.setContent(pieces.get(i));
                chunk.setEmbedding(vectors.get(i));
                chunk.setCreatedAt(LocalDateTime.now());
                chunks.add(chunk);
            }
        }

        chunkRepository.saveAll(chunks);
        chunkRepository.flush();

        System.out.printf("%n[评测准备] 语料 %d 篇 → %d 个分块(已真实向量化), 待测问题 %d 个, topK=%d%n",
                golden.getDocuments().size(), chunks.size(), golden.getCases().size(), golden.getTopK());
    }

    // ==================================================================
    //  评测主项: Recall@K / MRR / HitRate@K
    // ==================================================================

    @Test
    @DisplayName("检索质量达到基线(Recall@K / MRR / HitRate@K)")
    void retrievalQualityMeetsBaseline() {
        // 指标计算与报告共用 RetrievalEvalMetrics —— live 模式(RagRetrievalEvalLiveIT)用同一套,
        // 保证两种模式的口径完全一致, 否则两边分数无法互相参照。
        Report report = RetrievalEvalMetrics.evaluate(
                "RAG 检索质量评测 —— fixture(内联语料)",
                golden.getTopK(),
                golden.getCases(),
                (question, topK) -> retrievalService.retrieve(golden.getKbId(), question, topK));

        report.print(golden.getBaseline(), golden.getThresholds());

        // 断言按"指标"而非"某条 case"——质量评测看的是整体水平, 单条波动不应阻断;
        // 失败时把未命中清单打进消息里, 便于直接定位是哪个问题退化。
        Thresholds t = golden.getThresholds();
        int topK = golden.getTopK();
        assertTrue(report.recallAtK() >= t.getRecallAtK(),
                String.format("Recall@%d=%.3f 低于下限 %.2f%n未命中清单:%n%s",
                        topK, report.recallAtK(), t.getRecallAtK(), report.missSummary()));
        assertTrue(report.mrr() >= t.getMrr(),
                String.format("MRR=%.3f 低于下限 %.2f(说明相关文档的排名整体后移了)", report.mrr(), t.getMrr()));
        assertTrue(report.hitRate() >= t.getHitRateAtK(),
                String.format("HitRate@%d=%.3f 低于下限 %.2f%n未命中清单:%n%s",
                        topK, report.hitRate(), t.getHitRateAtK(), report.missSummary()));
    }

    // ==================================================================
    //  辅助断言: 评测本身的可信度
    // ==================================================================

    @Test
    @DisplayName("评测集自检: 每个 case 的期望文档都真实存在于语料中(防止标注笔误导致的假阴性)")
    void goldenSetIsSelfConsistent() {
        Set<String> knownDocIds = golden.getDocuments().stream()
                .map(GoldenDoc::getDocId)
                .collect(Collectors.toSet());

        List<String> unknown = new ArrayList<>();
        for (EvalCase c : golden.getCases()) {
            for (String expected : c.getExpectedDocIds()) {
                if (!knownDocIds.contains(expected)) {
                    unknown.add(c.getId() + " → " + expected);
                }
            }
        }

        assertTrue(unknown.isEmpty(),
                "以下期望命中的文档 ID 在 documents 中不存在(标注笔误会让该条 case 永远无法命中): " + unknown);
    }

    @Test
    @DisplayName("同一问题重复检索结果稳定(检索链路的确定性; 若抖动说明上游返回不稳定)")
    void retrievalIsRepeatable() {
        EvalCase c = golden.getCases().get(0);
        List<String> first = ids(retrievalService.retrieve(golden.getKbId(), c.getQuestion(), golden.getTopK()));
        List<String> second = ids(retrievalService.retrieve(golden.getKbId(), c.getQuestion(), golden.getTopK()));

        assertEquals(first, second, "同一问题两次检索的文档顺序应一致");
    }

    private static List<String> ids(List<RetrievalResult> results) {
        return results.stream().map(RetrievalResult::getDocumentId).collect(Collectors.toList());
    }

    // ==================================================================
    //  黄金集加载
    // ==================================================================

    private static Golden loadGolden() throws IOException {
        try (InputStream in = new ClassPathResource(GOLDEN_PATH).getInputStream()) {
            return new ObjectMapper().readValue(in, Golden.class);
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Golden {
        private String kbId;
        private int topK = 5;
        /** 断言下限: 低于即判为退化 */
        private Thresholds thresholds = new Thresholds();
        /** 上次实测值, 仅用于打印对比(不参与断言) */
        private Thresholds baseline = new Thresholds();
        private List<GoldenDoc> documents = new ArrayList<>();
        /** 用例定义复用 {@link RetrievalEvalMetrics.EvalCase}, 与 live 模式口径一致 */
        private List<EvalCase> cases = new ArrayList<>();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class GoldenDoc {
        private String docId;
        private String filename;
        private String content;
    }

    // ==================================================================
    //  建父记录(真实 schema 有完整外键链: users → knowledge_bases → documents → document_chunks)
    // ==================================================================

    private void ensureUser() {
        UserEntity user = new UserEntity();
        user.setId(TEST_USER_ID);
        user.setUsername(TEST_USER_ID);
        user.setPasswordHash("not-a-real-hash");   // 评测不涉及认证, 仅为满足 NOT NULL
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
    }

    private void ensureKnowledgeBase(String kbId) {
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setId(kbId);
        kb.setUserId(TEST_USER_ID);
        kb.setName("检索评测知识库");
        kb.setCreatedAt(LocalDateTime.now());
        kb.setUpdatedAt(LocalDateTime.now());
        knowledgeBaseRepository.save(kb);
    }

    private void ensureDocument(String docId, String filename) {
        DocumentEntity doc = new DocumentEntity();
        doc.setId(docId);
        doc.setKbId(golden.getKbId());
        doc.setUserId(TEST_USER_ID);
        doc.setFilename(filename);
        doc.setCreatedAt(LocalDateTime.now());
        doc.setUpdatedAt(LocalDateTime.now());
        documentRepository.save(doc);
    }
}
