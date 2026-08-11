package com.dcdr.agent.rag.reader;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;

import java.io.InputStream;
import java.util.Set;

/**
 * HTML 解析器 (V2.1 RAG), 基于 Jsoup。
 * <p>
 * 策略: 移除 script/style/nav/footer 等噪音标签后提取正文, 保留段落换行,
 * 避免把导航/广告塞进知识库污染检索。
 */
@Slf4j
public class HtmlReader implements DocumentReader {

    private static final Set<String> EXTENSIONS = Set.of("html", "htm");

    @Override
    public String read(InputStream input) {
        try {
            // Jsoup 自动按 BOM/charset 推断编码
            Document doc = Jsoup.parse(input, null, "");
            // 移除明显噪音标签
            doc.select("script,style,nav,footer,header,noscript,iframe,form,button").remove();
            // 用 text() 拿纯文本, 块级元素自动加换行
            String text = doc.body() != null ? doc.body().text() : "";
            // 用 Safelist 做二次清洗防御(剥离残留标签)
            text = Jsoup.clean(text, "", Safelist.none(), new Document.OutputSettings());
            log.debug("HTML 解析完成: {} 字符", text.length());
            return text;
        } catch (Exception e) {
            throw new RuntimeException("解析 HTML 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Set<String> supportedExtensions() {
        return EXTENSIONS;
    }
}
