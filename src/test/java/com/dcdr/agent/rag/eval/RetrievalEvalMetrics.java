package com.dcdr.agent.rag.eval;

import com.dcdr.agent.rag.dto.RetrievalResult;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * 检索质量评测的指标计算与报告(供两种评测模式共用)。
 *
 * <p><b>两种模式的区别只在"语料从哪来"</b>, 指标口径必须完全一致, 否则分数无法互相参照:
 * <ul>
 *   <li>{@code fixture}({@code RagRetrievalEvalIT}): 语料内联在 JSON, 灌进临时容器库 ——
 *       测"检索算法", 但跳过了 PDF 解析与切分;</li>
 *   <li>{@code live}({@code RagRetrievalEvalLiveIT}): 直接查真实知识库 ——
 *       把"PDF 解析 → 切分 → embedding → 检索"全链路都纳入评测。</li>
 * </ul>
 *
 * <p><b>命中判定用 documentId 而非 chunkId</b>: 一个文档会被切成多个分块,
 * "检索到该文档的任意分块"即已满足业务需要; 按 chunkId 判定会虚低。
 *
 * <p>指标口径:
 * <ul>
 *   <li>{@code Recall@K} —— 期望命中的文档里, 前 K 条召回了多少(按用例求宏平均);</li>
 *   <li>{@code MRR} —— 首个命中排名的倒数(1/rank)均值, 反映"相关文档排得够不够靠前";</li>
 *   <li>{@code HitRate@K} —— 至少命中一个期望文档的用例占比。</li>
 * </ul>
 */
public final class RetrievalEvalMetrics {

    private RetrievalEvalMetrics() {
    }

    /** 一条评测用例(与 JSON 中的结构一一对应) */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EvalCase {
        private String id;
        private String question;
        private List<String> expectedDocIds = new ArrayList<>();
    }

    /** 阈值 / 基线(两者结构相同, 只是语义不同: 前者是断言下限, 后者是上次实测值) */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Thresholds {
        private double recallAtK = 0.0;
        private double mrr = 0.0;
        private double hitRateAtK = 0.0;
    }

    /** 单条用例的结果 */
    public record CaseResult(String id,
                             String question,
                             Set<String> expected,
                             List<String> returned,
                             double recall,
                             int rank) {

        /** rank 为 1-based; 未命中记为 0 */
        public double reciprocalRank() {
            return rank > 0 ? 1.0 / rank : 0.0;
        }

        public boolean hit() {
            return rank > 0;
        }
    }

    /** 汇总报告 */
    public record Report(String title, int topK, List<CaseResult> results) {

        public int n() {
            return results.size();
        }

        public double recallAtK() {
            return results.stream().mapToDouble(CaseResult::recall).average().orElse(0.0);
        }

        public double mrr() {
            return results.stream().mapToDouble(CaseResult::reciprocalRank).average().orElse(0.0);
        }

        public double hitRate() {
            return results.isEmpty() ? 0.0
                    : (double) results.stream().filter(CaseResult::hit).count() / results.size();
        }

        /** 完全未命中的用例(诊断用; 空列表说明全部至少命中一个) */
        public List<CaseResult> misses() {
            return results.stream().filter(r -> !r.hit()).collect(Collectors.toList());
        }

        public List<CaseResult> partialMisses() {
            return results.stream().filter(r -> r.hit() && r.recall() < 1.0).collect(Collectors.toList());
        }

        /** 打印逐条明细 + 指标汇总(实测 / 基线 / 下限 / 判定) */
        public void print(Thresholds baseline, Thresholds thresholds) {
            System.out.printf("%n================ %s (topK=%d, n=%d) ================%n", title, topK, n());

            for (CaseResult r : results) {
                System.out.printf("  %-6s Recall=%.2f  RR=%.2f  rank=%-3s 期望=%s 实际=%s%n",
                        r.id(), r.recall(), r.reciprocalRank(),
                        r.rank() > 0 ? String.valueOf(r.rank()) : "-", r.expected(), r.returned());
            }

            System.out.println("------------------------------------------------------------------");
            System.out.printf("  指标              实测     基线     下限   判定%n");
            System.out.printf("  Recall@%-2d         %.3f    %.3f    %.2f   %s%n",
                    topK, recallAtK(), baseline.getRecallAtK(), thresholds.getRecallAtK(),
                    verdict(recallAtK(), baseline.getRecallAtK()));
            System.out.printf("  MRR                %.3f    %.3f    %.2f   %s%n",
                    mrr(), baseline.getMrr(), thresholds.getMrr(), verdict(mrr(), baseline.getMrr()));
            System.out.printf("  HitRate@%-2d        %.3f    %.3f    %.2f   %s%n",
                    topK, hitRate(), baseline.getHitRateAtK(), thresholds.getHitRateAtK(),
                    verdict(hitRate(), baseline.getHitRateAtK()));
            System.out.println("  (判定 = 相对基线: 提升/持平/退化; 退化未到下限仍算通过, 但值得关注)");

            List<CaseResult> misses = misses();
            if (!misses.isEmpty()) {
                System.out.printf("  完全未命中 %d 条:%n", misses.size());
                misses.forEach(r -> System.out.printf("    %s | 期望=%s | 实际=%s | %s%n",
                        r.id(), r.expected(), r.returned(), r.question()));
            }
            System.out.println("==================================================================");
        }

        /** 供断言消息复用的未命中清单 */
        public String missSummary() {
            List<CaseResult> misses = misses();
            if (misses.isEmpty()) {
                return "(无完全未命中的用例)";
            }
            return misses.stream()
                    .map(r -> String.format("%s | 期望=%s | 实际=%s | %s",
                            r.id(), r.expected(), r.returned(), r.question()))
                    .collect(Collectors.joining(System.lineSeparator()));
        }
    }

    /**
     * 跑一遍评测。
     *
     * @param title     报告标题(如 "RAG 检索质量评测 —— fixture 语料")
     * @param topK      每条问题取前 K 条
     * @param cases     用例列表
     * @param retriever 查询函数: (问题, topK) → 检索结果。两种评测模式各自注入
     *                  (fixture 走临时库、live 走真实库), 指标口径因此完全一致
     */
    public static Report evaluate(String title,
                                  int topK,
                                  List<EvalCase> cases,
                                  BiFunction<String, Integer, List<RetrievalResult>> retriever) {
        List<CaseResult> results = new ArrayList<>(cases.size());
        for (EvalCase c : cases) {
            List<String> returned = retriever.apply(c.getQuestion(), topK).stream()
                    .map(RetrievalResult::getDocumentId)
                    .collect(Collectors.toList());
            Set<String> expected = new LinkedHashSet<>(c.getExpectedDocIds());

            long matched = expected.stream().filter(returned::contains).count();
            double recall = expected.isEmpty() ? 0.0 : (double) matched / expected.size();

            int rank = 0;
            for (int i = 0; i < returned.size(); i++) {
                if (expected.contains(returned.get(i))) {
                    rank = i + 1;
                    break;
                }
            }
            results.add(new CaseResult(c.getId(), c.getQuestion(), expected, returned, recall, rank));
        }
        return new Report(title, topK, results);
    }

    /**
     * 与基线对比: 仅供提示, 不参与断言(断言看下限)。
     * <p>容差取千分位 —— 指标是统计值, 且基线在 JSON 里按 3 位小数记录,
     * 用浮点严格比较会把四舍五入误差误报成退化。
     */
    public static String verdict(double actual, double baseline) {
        if (baseline <= 0.0) {
            return "—";   // 尚未建立基线
        }
        double delta = actual - baseline;
        if (delta > 1e-3) {
            return String.format("提升 %+.3f", delta);
        }
        if (delta < -1e-3) {
            return String.format("退化 %.3f", delta);
        }
        return "持平";
    }
}
