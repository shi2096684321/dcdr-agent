package com.dcdr.agent.rag.splitter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link SentenceSplitter} 单元测试。 */
class SentenceSplitterTest {

    @Test
    @DisplayName("句子恰好装得下时, 不会在句中截断(固定长度策略则会)")
    void keepsSentencesIntact() {
        String text = "你好世界。你好世界。";
        assertEquals(List.of("你好世界。", "你好世界。"), new SentenceSplitter(5, 0).split(text));

        // 对照: 固定长度策略在块大小不是句子整数倍时会把句子切开
        assertEquals("你好世界。你好", new FixedSplitter(7, 0).split(text).get(0));
    }

    @Test
    @DisplayName("中英文句子边界都能识别")
    void recognizesChineseAndEnglishTerminators() {
        assertEquals(List.of("第一句。", "第二句！", "Third?"),
                new SentenceSplitter(6, 0).split("第一句。第二句！Third?"));
    }

    @Test
    @DisplayName("单句超过 chunkSize 时降级为字符硬切, 不产出超长块")
    void hardSplitsOversizedSentence() {
        List<String> chunks = new SentenceSplitter(6, 0).split("abcdefghijklmnop");
        assertEquals(3, chunks.size());
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= 6, "单句过长时仍需按 chunkSize 硬切");
        }
    }

    @Test
    @DisplayName("overlap 与默认策略语义一致")
    void overlapCarriesTail() {
        List<String> chunks = new SentenceSplitter(5, 2).split("你好世界。你好世界。");
        assertTrue(chunks.size() > 1);
        assertEquals(chunks.get(0).substring(chunks.get(0).length() - 2), chunks.get(1).substring(0, 2));
    }
}
