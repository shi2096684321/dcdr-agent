package com.dcdr.agent.rag;

import com.dcdr.agent.rag.eval.RetrievalEvalMetrics;
import com.dcdr.agent.rag.eval.RetrievalEvalMetrics.EvalCase;
import com.dcdr.agent.rag.eval.RetrievalEvalMetrics.Report;
import com.dcdr.agent.rag.eval.RetrievalEvalMetrics.Thresholds;
import com.dcdr.agent.rag.service.RetrievalService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG 检索质量评测 —— <b>live 模式</b>: 直接对真实知识库评测。
 *
 * <p><b>与 fixture 模式({@code RagRetrievalEvalIT})的分工</b>:
 * <ul>
 *   <li>fixture: 语料内联在 JSON、灌进临时容器库 —— 测<b>检索算法</b>,
 *       但<b>跳过了 PDF 解析与切分</b>;</li>
 *   <li>live(本类): 语料就是<b>库里已有的真实文档</b> —— 把
 *       "PDF 解析 → 切分 → embedding → 检索"<b>全链路</b>都纳入评测。</li>
 * </ul>
 * 因此 PDF 场景下特有的退化(表格乱码、页眉页脚混入、双栏错序、扫描版 OCR 错字、
 * 切分切断句子)只有 live 模式能发现。指标口径与 fixture 完全一致(共用
 * {@link RetrievalEvalMetrics}), 但分数<b>不可互相比较</b>(语料不同)。
 *
 * <p><b>⚠️ 为什么默认不执行</b>: 本类连的是<b>真实数据库</b>。若不加门控,
 * {@code mvn verify} 在 CI 或无意识场景下就可能连上生产库。故用系统属性强制显式开启:
 * <pre>{@code
 * mvn verify -Dit.test=RagRetrievalEvalLiveIT -Deval.live=true
 * }</pre>
 * 未传 {@code -Deval.live=true} 时整个类被跳过, 不会加载 Spring 上下文、不会连任何库。
 *
 * <p><b>只读保证</b>: 全程只调用 {@link RetrievalService#retrieve}, 不写入任何业务数据。
 * 需要的是"真实库可访问"(本地 pg 已启动且含目标知识库), 而不是 Docker ——
 * 故本类<b>不启动 Testcontainers</b>。
 *
 * <p><b>分数怎么用</b>: 真实库文档会持续增长, 同样的用例分数天然波动, 因此
 * <b>看趋势、不比绝对值</b>。{@code thresholds} 只兜住"严重退化";
 * 历史值记在 {@code eval/live/kb.json} 的 {@code baseline} 里, 每次运行会打印差异。
 */
@EnabledIfSystemProperty(named = "eval.live", matches = "true")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        // 与 fixture 评测保持同样的检索配置: 纯向量、关掉 hybrid 与 rerank,
        // 使 live 与 fixture 的差异只来自"语料", 而不来自检索参数。
        properties = {
                "agent.rag.enabled=true",
                "agent.rag.retrieval.hybrid.enabled=false",
                "agent.rag.retrieval.rerank.enabled=false"
        })
// 连真实 PostgreSQL: 用 pg profile(其数据源指向本地库), 而非 test profile 的 H2 内存库。
@ActiveProfiles("pg")
class RagRetrievalEvalLiveIT {

    private static final String KB_CONFIG = "eval/live/kb.json";
    private static final String CASES = "eval/live/cases.json";

    /** 尚未填写时的占位内容(与 kb.json / cases.json 中的提示保持一致) */
    private static final String PLACEHOLDER_KB = "请填入";
    private static final String PLACEHOLDER_DOC = "请填入";

    @Autowired
    private RetrievalService retrievalService;

    @Test
    @DisplayName("真实知识库检索质量达到基线(Recall@K / MRR / HitRate@K)")
    void retrievalQualityOnRealKnowledgeBase() throws IOException {
        LiveConfig config = load(KB_CONFIG, LiveConfig.class);
        CasesFile casesFile = load(CASES, CasesFile.class);
        List<EvalCase> cases = casesFile.getCases();

        assertFalse(config.getKbId() == null || config.getKbId().contains(PLACEHOLDER_KB),
                "请先在 " + KB_CONFIG + " 中填入真实的知识库 ID"
                        + "(查询: SELECT id, name FROM knowledge_bases ORDER BY created_at DESC)");
        assertFalse(cases.isEmpty(), "请先在 " + CASES + " 中添加评测用例");

        // 标注自检: 明显是占位符的 docId 直接拦下, 否则会静默拖低所有指标
        List<String> placeholders = new ArrayList<>();
        for (EvalCase c : cases) {
            for (String docId : c.getExpectedDocIds()) {
                if (docId == null || docId.contains(PLACEHOLDER_DOC)) {
                    placeholders.add(c.getId());
                }
            }
        }
        assertTrue(placeholders.isEmpty(),
                "以下用例的 expectedDocIds 仍是占位符, 请填写库里真实的 documents.id: " + placeholders);

        Report report = RetrievalEvalMetrics.evaluate(
                "RAG 检索质量评测 —— live(真实知识库 " + config.getKbId() + ")",
                config.getTopK(),
                cases,
                (question, topK) -> retrievalService.retrieve(config.getKbId(), question, topK));

        report.print(config.getBaseline(), config.getThresholds());

        Thresholds t = config.getThresholds();
        int topK = config.getTopK();
        assertTrue(report.recallAtK() >= t.getRecallAtK(),
                String.format("Recall@%d=%.3f 低于下限 %.2f%n未命中清单:%n%s",
                        topK, report.recallAtK(), t.getRecallAtK(), report.missSummary()));
        assertTrue(report.mrr() >= t.getMrr(),
                String.format("MRR=%.3f 低于下限 %.2f(相关文档整体排名后移)", report.mrr(), t.getMrr()));
        assertTrue(report.hitRate() >= t.getHitRateAtK(),
                String.format("HitRate@%d=%.3f 低于下限 %.2f%n未命中清单:%n%s",
                        topK, report.hitRate(), t.getHitRateAtK(), report.missSummary()));
    }

    private static <T> T load(String path, Class<T> type) throws IOException {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new ObjectMapper().readValue(in, type);
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class LiveConfig {
        private String kbId;
        private int topK = 5;
        private Thresholds thresholds = new Thresholds();
        private Thresholds baseline = new Thresholds();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class CasesFile {
        private List<EvalCase> cases = new ArrayList<>();
    }
}
