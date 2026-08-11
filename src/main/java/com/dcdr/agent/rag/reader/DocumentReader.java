package com.dcdr.agent.rag.reader;

import java.io.InputStream;

/**
 * 文档解析器接口 (V2.1 RAG)。
 * <p>
 * 各格式(PDF/Word/TXT/HTML)实现此接口, 把二进制流转为纯文本, 供 splitter 分块。
 */
public interface DocumentReader {

    /**
     * 解析文档为纯文本。
     *
     * @param input 文档输入流
     * @return 提取的纯文本(去标签/去页眉页脚噪音); 失败抛 RuntimeException
     */
    String read(InputStream input);

    /** 支持的文件扩展名(小写, 不含点), 如 ["pdf"] */
    java.util.Set<String> supportedExtensions();
}
