package com.dcdr.agent.rag.service;

import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.rag.config.RagProperties;
import com.dcdr.agent.rag.dto.SplitPreviewResult;
import com.dcdr.agent.rag.splitter.SplitterFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link SplitPreviewService} 单元测试。 */
class SplitPreviewServiceTest {

    private SplitPreviewService service;

    @BeforeEach
    void setUp() {
        RagProperties properties = new RagProperties();
        properties.getSplitter().setStrategy("recursive");
        properties.getSplitter().setChunkSize(1000);
        properties.getSplitter().setChunkOverlap(200);
        service = new SplitPreviewService(new SplitterFactory(properties));
    }

    @Test
    @DisplayName("统计信息与片段列表一致")
    void reportsStats() {
        SplitPreviewResult result = service.preview("第一句。第二句。", "recursive", 5, 0, null);

        assertEquals(2, result.getChunkCount());
        assertEquals(8, result.getTotalChars());
        assertEquals(4, result.getAvgChars());
        assertEquals(4, result.getMinChars());
        assertEquals(4, result.getMaxChars());
        assertFalse(result.isTruncated());
        assertEquals(2, result.getChunks().size());
        assertEquals("第一句。", result.getChunks().get(0).getContent());
    }

    @Test
    @DisplayName("片段条数超过上限时截断, 但总块数仍报真实值")
    void truncatesChunkListButKeepsRealCount() {
        SplitPreviewResult result = service.preview("abcdefghij".repeat(10), "fixed", 10, 0, 3);

        assertEquals(10, result.getChunkCount());
        assertEquals(3, result.getChunks().size());
        assertTrue(result.isTruncated());
    }

    @Test
    @DisplayName("单块过大时只返回预览片段并标记截断")
    void truncatesLongChunkContent() {
        SplitPreviewResult result = service.preview("a".repeat(5000), "fixed", 2000, 0, null);

        SplitPreviewResult.ChunkPreview first = result.getChunks().get(0);
        assertEquals(2000, first.getLength());
        assertEquals(1200, first.getContent().length());
        assertTrue(first.isContentTruncated());
    }

    @Test
    @DisplayName("空文本与超长文本返回 400")
    void rejectsInvalidText() {
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.preview("  ", "recursive", 1000, 200, null)).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.preview("a".repeat(200_001), "recursive", 1000, 200, null)).getCode());
    }

    @Test
    @DisplayName("非法策略沿用工厂的 400 口径")
    void rejectsUnknownStrategy() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.preview("正文", "semantic", 1000, 200, null));
        assertEquals(400, e.getCode());
    }
}
