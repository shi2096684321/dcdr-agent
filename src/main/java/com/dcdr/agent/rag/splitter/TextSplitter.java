package com.dcdr.agent.rag.splitter;

import java.util.List;

/**
 * 文本分块器接口 (V2.1 RAG)。
 * <p>
 * 将长文档切分为合适大小的文本块, 供向量化与检索使用。
 */
public interface TextSplitter {

    /**
     * 将文本切分为多个块。
     *
     * @param text 原始文本, 不能为 null
     * @return 文本块列表, 每块长度约等于 chunkSize (含 overlap)
     */
    List<String> split(String text);

    /** 当前分块目标字符数 */
    int getChunkSize();

    /** 当前分块重叠字符数 */
    int getChunkOverlap();
}
