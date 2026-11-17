package com.dcdr.agent.rag.splitter;

import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.rag.config.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@link SplitterFactory} 单元测试。 */
class SplitterFactoryTest {

    private SplitterFactory factory;

    @BeforeEach
    void setUp() {
        RagProperties properties = new RagProperties();
        properties.getSplitter().setStrategy("recursive");
        properties.getSplitter().setChunkSize(1000);
        properties.getSplitter().setChunkOverlap(200);
        factory = new SplitterFactory(properties);
    }

    @Test
    @DisplayName("四种策略分别构造对应实现")
    void createsEveryStrategy() {
        assertInstanceOf(FixedSplitter.class, factory.create("fixed", 100, 10));
        assertInstanceOf(RecursiveSplitter.class, factory.create("recursive", 100, 10));
        assertInstanceOf(SentenceSplitter.class, factory.create("sentence", 100, 10));
        assertInstanceOf(MarkdownSplitter.class, factory.create("markdown", 100, 10));
    }

    @Test
    @DisplayName("code 大小写/空白不敏感, 落库时归一化")
    void normalizesStrategyCode() {
        SplitterFactory.SplitOptions options = factory.resolve("  MARKDOWN ", null, null);
        assertEquals(ChunkStrategy.MARKDOWN, options.strategy());
        assertEquals("markdown", options.strategy().getCode());
        assertEquals(1000, options.chunkSize());
        assertEquals(200, options.chunkOverlap());
    }

    @Test
    @DisplayName("空策略与空参数回退到全局默认(兼容存量知识库)")
    void fallsBackToGlobalDefaults() {
        TextSplitter splitter = factory.create(null, null, null);
        assertInstanceOf(RecursiveSplitter.class, splitter);
        assertEquals(1000, splitter.getChunkSize());
        assertEquals(200, splitter.getChunkOverlap());
    }

    @Test
    @DisplayName("未知策略与非法参数返回 400 业务异常")
    void rejectsInvalidInput() {
        BusinessException badStrategy = assertThrows(BusinessException.class,
                () -> factory.create("semantic", 100, 10));
        assertEquals(400, badStrategy.getCode());

        assertThrows(BusinessException.class, () -> factory.create("fixed", 0, 0));
        assertThrows(BusinessException.class, () -> factory.create("fixed", 100, 100));
    }
}
