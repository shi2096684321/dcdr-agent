package com.dcdr.agent.rag.reader;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/**
 * PDF 解析器 (V2.1 RAG), 基于 Apache PDFBox 3.x。
 * <p>
 * 支持文字版 PDF 直接抽取文本; <b>不支持</b>扫描版(图片型) PDF 的 OCR,
 * 此类文档会得到空文本, 调用方应据此把文档状态置为 failed 并提示用户。
 */
@Slf4j
public class PdfReader implements DocumentReader {

    private static final Set<String> EXTENSIONS = Set.of("pdf");

    @Override
    public String read(InputStream input) {
        try {
            byte[] bytes = input.readAllBytes();
            try (PDDocument doc = Loader.loadPDF(bytes)) {
                PDFTextStripper stripper = new PDFTextStripper();
                // 按页输出, 页间以换行分隔, 保留段落结构
                String text = stripper.getText(doc);
                log.debug("PDF 解析完成: {} 页, {} 字符", doc.getNumberOfPages(), text.length());
                return text;
            }
        } catch (IOException e) {
            throw new RuntimeException("解析 PDF 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Set<String> supportedExtensions() {
        return EXTENSIONS;
    }
}
