package com.dcdr.agent.rag.reader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 纯文本/Markdown 解析器 (V2.1 RAG)。
 * <p>
 * 直接按 UTF-8 读取, 不做转换; Markdown 保留原语法(供 splitter 按段落切分)。
 */
public class PlainTextReader implements DocumentReader {

    private static final Set<String> EXTENSIONS = Set.of("txt", "md", "markdown");

    @Override
    public String read(InputStream input) {
        try {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("读取纯文本失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Set<String> supportedExtensions() {
        return EXTENSIONS;
    }
}
