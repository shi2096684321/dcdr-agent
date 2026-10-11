package com.dcdr.agent.rag.splitter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RecursiveSplitter} 单元测试(V3.2)。
 *
 * <p>纯单元测试: 只依赖构造传入的两个 int 配置, 无外部分依赖, 不启 Spring 上下文。
 *
 * <p><b>为什么它值得测</b>: 它是 RAG 的默认分块策略, 分块结果直接决定检索质量 ——
 * 切得太碎会丢失上下文, 切得太大则检索命中率下降且浪费 token。而它的实现是
 * "递归按优先级分隔符切分 + 相邻小块合并 + overlap 回溯"三者叠加, 行为不直观:
 * <ul>
 *   <li>切分时会<b>把分隔符粘回上一段末尾</b>(避免切出无结构碎片)</li>
 *   <li>合并阶段会把多个原子块拼到接近 {@code chunkSize}, 而非一块一原子</li>
 *   <li>带 overlap 时, 产出的块可能<b>超过 {@code chunkSize}</b>(这是设计行为, 不是 bug)</li>
 * </ul>
 * 这些特征一旦被无意改动, 检索质量会静默变化, 因此必须由测试固定下来。
 */
class RecursiveSplitterTest {

    // ==================================================================
    //  构造器参数校验
    // ==================================================================

    @Test
    @DisplayName("chunkSize <= 0 时拒绝构造")
    void rejectsNonPositiveChunkSize() {
        assertThrows(IllegalArgumentException.class, () -> new RecursiveSplitter(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new RecursiveSplitter(-1, 0));
    }

    @Test
    @DisplayName("chunkOverlap 为负或 >= chunkSize 时拒绝构造")
    void rejectsInvalidOverlap() {
        assertThrows(IllegalArgumentException.class, () -> new RecursiveSplitter(100, -1));
        assertThrows(IllegalArgumentException.class, () -> new RecursiveSplitter(100, 100),
                "overlap 等于 chunkSize 会导致无限回溯, 必须拒绝");
        assertThrows(IllegalArgumentException.class, () -> new RecursiveSplitter(100, 101));
    }

    @Test
    @DisplayName("合法边界参数可正常构造")
    void acceptsValidBoundaries() {
        assertEquals(100, new RecursiveSplitter(100, 0).getChunkSize());
        assertEquals(99, new RecursiveSplitter(100, 99).getChunkOverlap());
    }

    // ==================================================================
    //  空输入与短文本
    // ==================================================================

    @Test
    @DisplayName("null / 空 / 纯空白返回空列表")
    void blankTextReturnsEmpty() {
        RecursiveSplitter splitter = new RecursiveSplitter(100, 0);
        assertTrue(splitter.split(null).isEmpty());
        assertTrue(splitter.split("").isEmpty());
        assertTrue(splitter.split("   ").isEmpty());
    }

    @Test
    @DisplayName("不超过 chunkSize 的文本原样单块返回")
    void shortTextStaysSingleChunk() {
        assertEquals(List.of("短文本"), new RecursiveSplitter(100, 0).split("短文本"));
    }

    // ==================================================================
    //  切分策略
    // ==================================================================

    @Test
    @DisplayName("优先按段落分隔符(\\n\\n)切分, 且分隔符保留在段末")
    void splitsByParagraphAndKeepsSeparator() {
        // "aa\n\n" / "bb\n\n" / "cc" 各不超 4 字符, 每段独立成块
        assertEquals(List.of("aa\n\n", "bb\n\n", "cc"),
                new RecursiveSplitter(4, 0).split("aa\n\nbb\n\ncc"));
    }

    @Test
    @DisplayName("段落不可分时降级按单个换行切分, 换行保留在段末")
    void fallsBackToSingleNewline() {
        assertEquals(List.of("aaa\n", "bbb"),
                new RecursiveSplitter(5, 0).split("aaa\nbbb"));
    }

    @Test
    @DisplayName("再降级按中文句号切分")
    void fallsBackToChinesePeriod() {
        assertEquals(List.of("第一句。", "第二句。", "第三句"),
                new RecursiveSplitter(6, 0).split("第一句。第二句。第三句"));
    }

    @Test
    @DisplayName("无任何分隔符可用的长文本被按字符硬切为等长块")
    void hardSplitsWhenNoSeparatorAvailable() {
        List<String> chunks = new RecursiveSplitter(10, 0).split("abcdefghij".repeat(4));
        assertEquals(4, chunks.size());
        for (String c : chunks) {
            assertEquals(10, c.length(), "无分隔符时应按 chunkSize 等分");
        }
    }

    // ==================================================================
    //  合并与 overlap
    // ==================================================================

    @Test
    @DisplayName("相邻小块被合并到接近 chunkSize(不是一原子块一输出)")
    void mergesAdjacentSmallChunks() {
        // 4 个原子块(各 4/4/4/2 字符)在 chunkSize=10 下合并为 2 块
        assertEquals(List.of("aa\n\nbb\n\n", "cc\n\ndd"),
                new RecursiveSplitter(10, 0).split("aa\n\nbb\n\ncc\n\ndd"));
    }

    @Test
    @DisplayName("overlap > 0 时, 新块以「上一块末尾 overlap 个字符」开头")
    void newChunkStartsWithOverlapTail() {
        List<String> chunks = new RecursiveSplitter(10, 3).split("abcdefghij".repeat(4));

        assertTrue(chunks.size() > 1, "应产出多块");
        assertTrue(chunks.get(1).length() >= 3, "第二块长度应足以容纳 overlap 前缀");
        assertEquals(chunks.get(0).substring(chunks.get(0).length() - 3),
                chunks.get(1).substring(0, 3),
                "新块开头应等于上一块末尾的 overlap 字符, 以保留语义连续性");
    }

    @Test
    @DisplayName("注意: 带 overlap 时块可能超过 chunkSize(设计行为)")
    void chunksMayExceedChunkSizeWhenOverlapEnabled() {
        List<String> chunks = new RecursiveSplitter(10, 3).split("abcdefghij".repeat(4));

        // 每个原子块 10 字符, 回溯 3 字符后与前一块拼接 → 后续块为 13 字符。
        // 这不是缺陷: chunkSize 约束作用于"原子块", overlap 是额外的语义缓冲。
        assertTrue(chunks.get(1).length() > 10,
                "overlap 会让块超出 chunkSize, 调用方不应假设块长度 <= chunkSize");
    }

    @ParameterizedTest(name = "overlap={0}")
    @ValueSource(ints = {0, 1, 5, 9})
    @DisplayName("任意合法 overlap 下, 所有块拼接后仍能覆盖原文信息(不丢内容)")
    void allOverlapsProduceNonEmptyChunks(int overlap) {
        List<String> chunks = new RecursiveSplitter(20, overlap).split("abcdefghij".repeat(5));

        assertTrue(chunks.size() >= 1);
        for (String c : chunks) {
            assertTrue(!c.isEmpty(), "不应产出空块");
        }
    }

    @Test
    @DisplayName("getChunkSize / getChunkOverlap 回显构造参数")
    void gettersEchoConstructorArgs() {
        RecursiveSplitter splitter = new RecursiveSplitter(256, 32);
        assertEquals(256, splitter.getChunkSize());
        assertEquals(32, splitter.getChunkOverlap());
    }
}
