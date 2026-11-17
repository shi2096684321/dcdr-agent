package com.dcdr.agent.rag.splitter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link FixedSplitter} 单元测试。 */
class FixedSplitterTest {

    @Test
    @DisplayName("按字符数等长切分, 不关心句子边界")
    void splitsByExactCharCount() {
        List<String> chunks = new FixedSplitter(5, 0).split("abcdefghij");
        assertEquals(List.of("abcde", "fghij"), chunks);
    }

    @Test
    @DisplayName("overlap 让新块以上一块尾部字符开头(块可超过 chunkSize)")
    void overlapCarriesTailToNextChunk() {
        List<String> chunks = new FixedSplitter(5, 2).split("abcdefghij");
        assertEquals(2, chunks.size());
        assertEquals("abcde", chunks.get(0));
        assertTrue(chunks.get(1).startsWith("de"), "第二块应以 'de' 开头");
        assertTrue(chunks.get(1).length() > 5, "overlap 使块超出 chunkSize 是设计行为");
    }

    @Test
    @DisplayName("null / 空 / 纯空白返回空列表")
    void blankTextReturnsEmpty() {
        FixedSplitter splitter = new FixedSplitter(10, 2);
        assertTrue(splitter.split(null).isEmpty());
        assertTrue(splitter.split("").isEmpty());
        assertTrue(splitter.split("  \n ").isEmpty());
    }

    @Test
    @DisplayName("短文本单块原样返回")
    void shortTextStaysSingleChunk() {
        assertEquals(List.of("短文本"), new FixedSplitter(10, 2).split("短文本"));
    }

    @Test
    @DisplayName("非法参数被拒绝, 合法参数回显")
    void validatesAndEchoesParameters() {
        assertThrows(IllegalArgumentException.class, () -> new FixedSplitter(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new FixedSplitter(10, 10));
        FixedSplitter splitter = new FixedSplitter(256, 32);
        assertEquals(256, splitter.getChunkSize());
        assertEquals(32, splitter.getChunkOverlap());
    }
}
