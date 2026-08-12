package com.dcdr.agent.rag.reader;

import com.dcdr.agent.rag.ocr.QwenOcrClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * OCR 增强型 PDF 解析器 (V2.1)。
 * <p>
 * 在原 {@link PdfReader} 基础上增加扫描版自动降级逻辑:
 * <ol>
 *   <li>先用 PDFBox {@link PDFTextStripper} 抽取文字版文本(快、免费)</li>
 *   <li>计算"每页平均字符数", 低于 {@code thresholdCharsPerPage} 判定为扫描版/图片型</li>
 *   <li>扫描版用 {@link PDFRenderer} 逐页渲染为 PNG, 调 {@link QwenOcrClient} 提取文本</li>
 *   <li>文字版直接返回, 不触发 OCR, 避免无谓成本</li>
 * </ol>
 * <p>
 * <b>为什么逐页渲染而非整 PDF 直传</b>: qwen3.5-ocr 的 OpenAI 兼容接口 {@code /chat/completions}
 * 的 {@code image_url} 字段只接受图片格式(png/jpg), 不接受 PDF data URI。
 * 整 PDF 直传需走 Responses API 的 {@code input_file}(且要公网 URL), 限制较多。
 * 逐页渲染成 PNG 是最通用、最稳的方案, 兼容所有 qwen-vl-ocr 系列模型。
 *
 * @see QwenOcrClient
 */
@Slf4j
public class OcrPdfReader implements DocumentReader {

    private static final Set<String> EXTENSIONS = Set.of("pdf");
    /** 渲染 DPI: 150 兼顾清晰度和文件大小, 普通文档足够 OCR 识别 */
    private static final float RENDER_DPI = 150f;
    private static final String IMAGE_MIME = "image/png";
    private static final String IMAGE_FORMAT = "png";

    private final QwenOcrClient ocrClient;
    /** 每页平均字符数低于此值判定为扫描版, 触发 OCR */
    private final int thresholdCharsPerPage;
    /** 单文档最多 OCR 页数, 超出截断并告警, 防止大文档烧钱 */
    private final int maxPages;

    public OcrPdfReader(QwenOcrClient ocrClient, int thresholdCharsPerPage, int maxPages) {
        this.ocrClient = ocrClient;
        this.thresholdCharsPerPage = thresholdCharsPerPage;
        this.maxPages = maxPages;
    }

    @Override
    public String read(InputStream input) {
        try {
            byte[] bytes = input.readAllBytes();
            String text;
            int pageCount;

            // 1. 先用 PDFBox 抽文字版
            try (PDDocument doc = Loader.loadPDF(bytes)) {
                pageCount = doc.getNumberOfPages();
                PDFTextStripper stripper = new PDFTextStripper();
                text = stripper.getText(doc);
                log.debug("PDF 文字版抽取: {} 页, {} 字符", pageCount, text.length());
            }

            // 2. 判定是否扫描版: 每页平均字符数 < 阈值
            int charsPerPage = pageCount > 0 ? text.length() / pageCount : 0;
            if (charsPerPage >= thresholdCharsPerPage) {
                log.debug("判定为文字版 PDF ({} 字符/页 >= 阈值 {}), 跳过 OCR", charsPerPage, thresholdCharsPerPage);
                return text;
            }

            // 3. 扫描版: 渲染为 PNG → 逐页 OCR
            log.info("判定为扫描版 PDF ({} 页, {} 字符/页 < 阈值 {}), 渲染图片并调 qwen3.5-ocr",
                    pageCount, charsPerPage, thresholdCharsPerPage);

            int pagesToOcr = Math.min(pageCount, maxPages);
            if (pageCount > maxPages) {
                log.warn("PDF 页数({})超过 OCR 上限({}), 仅 OCR 前 {} 页, 建议拆分文档",
                        pageCount, maxPages, maxPages);
            }

            List<byte[]> pageImages = renderPagesToPng(bytes, pagesToOcr);
            if (pageImages.isEmpty()) {
                throw new RuntimeException("PDF 渲染图片失败, 未生成任何页");
            }

            String ocrText = ocrClient.extractFromImages(pageImages, IMAGE_MIME);
            if (ocrText != null && !ocrText.isBlank()) {
                log.info("OCR 提取完成: {} 页 → {} 字符", pageImages.size(), ocrText.length());
                return ocrText;
            }
            // OCR 返回空: 若 PDFBox 抽到了少量文字就降级用之, 否则报错
            if (text != null && !text.isBlank()) {
                log.warn("OCR 返回空, 降级使用 PDFBox 抽取的少量文本({} 字符)", text.length());
                return text;
            }
            throw new RuntimeException("OCR 提取结果为空且 PDFBox 无文本, 可能是空白 PDF");

        } catch (IOException e) {
            throw new RuntimeException("解析 PDF 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 用 PDFBox {@link PDFRenderer} 把 PDF 前 {@code pageCount} 页渲染为 PNG 字节列表。
     * <p>
     * 渲染 DPI 默认 150, 兼顾清晰度(A4 @ 150dpi ≈ 1240x1754)和文件大小(每页 PNG 约 200~500KB)。
     * qwen3.5-ocr 单图建议 &lt; 10MB, 150dpi 完全满足。
     */
    private List<byte[]> renderPagesToPng(byte[] pdfBytes, int pageCount) {
        List<byte[]> images = new ArrayList<>(pageCount);
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            for (int i = 0; i < pageCount; i++) {
                try {
                    BufferedImage img = renderer.renderImageWithDPI(i, RENDER_DPI);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(img, IMAGE_FORMAT, baos);
                    byte[] png = baos.toByteArray();
                    images.add(png);
                    log.debug("渲染第 {}/{} 页: {}x{}, {} 字节",
                            i + 1, pageCount, img.getWidth(), img.getHeight(), png.length);
                } catch (IOException e) {
                    log.warn("渲染第 {}/{} 页失败(跳过): {}", i + 1, pageCount, e.getMessage());
                    // 单页渲染失败跳过, 不影响其他页
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("打开 PDF 失败: " + e.getMessage(), e);
        }
        return images;
    }

    @Override
    public Set<String> supportedExtensions() {
        return EXTENSIONS;
    }
}
