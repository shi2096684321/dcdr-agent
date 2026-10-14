package com.dcdr.agent.common.log;

import com.dcdr.agent.rag.RagSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 调用链步骤的<b>字段契约</b>测试。
 *
 * <p>锁的不是"日志好不好看", 而是字段名本身 —— 它同时被三处消费:
 * 前端调用链面板按 {@code kind} 渲染、库侧聚合 SQL 按字段名解析
 * (见 {@code docs/eval/memory-health.sql} 与 {@code docs/eval/retrieval-health.sql})、
 * 以及落库的 {@code steps_json}。改字段名不会有任何编译错误, 只会让那几处的解析<b>静默失效</b> ——
 * 表现为"看板一直是空的", 而不是报错。
 */
class LlmCallTraceTest {

    @Test
    @DisplayName("记忆召回步骤: kind/query/hits/chars/costMs/memoryIds 一个都不能少")
    void memoryStepKeepsFieldContract() {
        LlmCallTrace trace = LlmCallTrace.begin();
        trace.recordMemory("用户问的这句话", 3, 412, 7, List.of("m1", "m2", "m3"));

        Map<String, Object> step = trace.snapshot().stream()
                .filter(s -> "memory".equals(s.get("kind")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未写入 kind=memory 的步骤"));

        assertEquals("用户问的这句话", step.get("query"), "条件触发的分析要靠它");
        assertEquals(3, step.get("hits"));
        assertEquals(412, step.get("chars"), "注入字数: 用来验证背景信息预算是否真的在起作用");
        assertEquals(7L, step.get("costMs"));
        assertEquals(List.of("m1", "m2", "m3"), step.get("memoryIds"),
                "库侧靠它反查「从未命中过的记忆」—— 少了它这条观测就没有意义");
    }

    @Test
    @DisplayName("RAG 步骤: 必须带 ragSource —— 否则分不清这次检索是谁发起的")
    void ragStepCarriesSource() {
        LlmCallTrace trace = LlmCallTrace.begin();
        trace.recordRag("用户问的这句话", 2, 3, 88, 0.82, List.of("doc1"), RagSource.TOOL);

        Map<String, Object> step = trace.snapshot().stream()
                .filter(s -> "rag".equals(s.get("kind")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未写入 kind=rag 的步骤"));

        assertEquals("tool", step.get("ragSource"),
                "看到 rag 步骤却在工具调用里找不到 knowledge_base_search 时, 全靠这个字段判断"
                        + "是模型自主调用、还是平台预检索/用户强制检索");
        assertEquals("用户问的这句话", step.get("query"));
        assertEquals(3, step.get("hits"));
        assertEquals(88L, step.get("costMs"));
        assertEquals(List.of("doc1"), step.get("docIds"));
    }

    // 此处原有「来源为空时不写该字段」的用例。参数类型改为 RagSource 后, "来源为空"
    // 在编译期就不可能发生 —— 该用例守的是一个不再存在的分支, 故删除。
    @Test
    @DisplayName("RAG 步骤: 中文名随值下发 —— 面板不再自己维护一份「取值 → 人话」的映射表")
    void ragStepCarriesSourceLabel() {
        LlmCallTrace trace = LlmCallTrace.begin();
        trace.recordRag("q", 1, 0, 5, 0.0, List.of(), RagSource.FORCED);

        Map<String, Object> step = trace.snapshot().stream()
                .filter(s -> "rag".equals(s.get("kind")))
                .findFirst()
                .orElseThrow();
        assertEquals("forced", step.get("ragSource"),
                "取值是面板、CSV、库侧聚合共同的契约, 不能随 enum 改名而变");
        assertEquals(RagSource.FORCED.label(), step.get("ragSourceLabel"),
                "中文名与取值出自同一处定义: 改了取值, 中文名跟着走, 不会一边中文一边英文");
    }

    @Test
    @DisplayName("记忆召回步骤: 未命中时不写 memoryIds(与 rag 的 docIds 同口径, 省存储)")
    void memoryStepOmitsEmptyIds() {
        LlmCallTrace trace = LlmCallTrace.begin();
        trace.recordMemory("q", 0, 0, 1, List.of());

        Map<String, Object> step = trace.snapshot().stream()
                .filter(s -> "memory".equals(s.get("kind")))
                .findFirst()
                .orElseThrow();
        assertFalse(step.containsKey("memoryIds"), "空列表不该白占一个字段");
        assertEquals(0, step.get("hits"));
    }
}
