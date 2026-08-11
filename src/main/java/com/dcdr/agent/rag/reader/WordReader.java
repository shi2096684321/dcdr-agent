package com.dcdr.agent.rag.reader;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/**
 * Word(.docx) 解析器 (V2.1 RAG), 基于 Apache POI XWPF。
 * <p>
 * <b>仅支持 docx</b>(OOXML 格式); 旧版 .doc(HWPF) 不支持, 调用方应拒绝 .doc 上传。
 */
@Slf4j
public class WordReader implements DocumentReader {

    private static final Set<String> EXTENSIONS = Set.of("docx");

    @Override
    public String read(InputStream input) {
        try (XWPFDocument doc = new XWPFDocument(input);
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            String text = extractor.getText();
            log.debug("Word 解析完成: {} 字符", text.length());
            return text;
        } catch (IOException e) {
            throw new RuntimeException("解析 Word 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Set<String> supportedExtensions() {
        return EXTENSIONS;
    }
}
