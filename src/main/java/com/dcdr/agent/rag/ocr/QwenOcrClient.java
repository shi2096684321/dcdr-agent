package com.dcdr.agent.rag.ocr;

import com.dcdr.agent.rag.config.RagProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * 通义千问 OCR 客户端 (V2.1)。
 * <p>
 * 调用 DashScope {@code qwen3.5-ocr} 模型, 走 OpenAI 兼容协议
 * {@code POST /compatible-mode/v1/chat/completions}。
 * <p>
 * <b>重要约束</b>: {@code /chat/completions} 的 {@code image_url} 字段只接受图片格式
 * (png/jpg), <b>不接受 PDF data URI</b>。整 PDF 直传会返回 400
 * "The image format is illegal and cannot be opened"。
 * 因此 PDF 必须先用 PDFBox 渲染成 PNG 图片, 再按图片调用本客户端。
 *
 * @see <a href="https://help.aliyun.com/zh/model-studio/qwen-vl-ocr">Qwen-OCR 官方文档</a>
 */
@Slf4j
public class QwenOcrClient {

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RagProperties.OcrConfig config;

    public QwenOcrClient(RagProperties.OcrConfig config) {
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            throw new IllegalArgumentException("OCR api-key 未配置, 请在 agent.rag.ocr.api-key 设置 DASHSCOPE_API_KEY");
        }
        this.config = config;
        this.webClient = WebClient.builder()
                .baseUrl(config.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + config.getApiKey())
                .defaultHeader("Content-Type", "application/json")
                .build();
        log.info("QwenOcrClient 初始化: base-url={}, model={}, timeout={}s",
                config.getBaseUrl(), config.getModel(), config.getTimeoutSeconds());
    }

    /**
     * 对单张图片做 OCR 文本提取。
     *
     * @param imageBytes 图片字节
     * @param mime       MIME 类型, 如 "image/png" / "image/jpeg"
     * @return 提取的纯文本; 失败抛 RuntimeException
     */
    public String extractFromImage(byte[] imageBytes, String mime) {
        String dataUri = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(imageBytes);
        return callOcr(dataUri);
    }

    /**
     * 对多张图片(如 PDF 多页渲染结果)做 OCR, 逐页调用并拼接。
     * <p>
     * 逐页而非整批的原因:
     * <ul>
     *   <li>避免单次请求体过大(多页 base64 易超服务端限制)</li>
     *   <li>单页失败不影响其他页, 容错更好</li>
     *   <li>RAG 入库是异步任务, 串行慢一点可接受</li>
     * </ul>
     *
     * @param pages 图片字节列表
     * @param mime  图片 MIME
     * @return 拼接后的全文, 页间以 {@code \n\n--- Page N ---\n\n} 分隔
     */
    public String extractFromImages(List<byte[]> pages, String mime) {
        if (pages == null || pages.isEmpty()) {
            throw new IllegalArgumentException("图片列表为空");
        }
        StringBuilder sb = new StringBuilder();
        int total = pages.size();
        for (int i = 0; i < total; i++) {
            try {
                String text = extractFromImage(pages.get(i), mime);
                if (i > 0) sb.append("\n\n--- Page ").append(i + 1).append(" ---\n\n");
                sb.append(text);
                log.debug("OCR 第 {}/{} 页完成: {} 字符", i + 1, total, text.length());
            } catch (Exception e) {
                // 单页失败不中断整体, 用占位标记, 上层仍可拿到部分文本
                log.warn("OCR 第 {}/{} 页失败(跳过): {}", i + 1, total, e.getMessage());
                sb.append("\n\n--- Page ").append(i + 1).append(" (OCR 失败) ---\n\n");
            }
        }
        return sb.toString();
    }

    /**
     * 实际调用 chat/completions 端点。
     * <p>
     * 请求体形如:
     * <pre>
     * {
     *   "model": "qwen3.5-ocr",
     *   "messages": [{
     *     "role": "user",
     *     "content": [
     *       {"type": "image_url", "image_url": {"url": "data:image/png;base64,..."}},
     *       {"type": "text", "text": "...可选自定义prompt..."}
     *     ]
     *   }]
     * }
     * </pre>
     * 不传 text 时, 模型使用默认 prompt 输出纯文本。
     */
    private String callOcr(String dataUri) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", config.getModel());

            ArrayNode messages = body.putArray("messages");
            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");

            ArrayNode content = userMsg.putArray("content");
            // image_url 段
            ObjectNode imgNode = content.addObject();
            imgNode.put("type", "image_url");
            ObjectNode imgUrl = imgNode.putObject("image_url");
            imgUrl.put("url", dataUri);

            // 可选自定义 prompt; 不传时模型走默认 OCR 行为
            String prompt = config.getPrompt();
            if (prompt != null && !prompt.isBlank()) {
                ObjectNode textNode = content.addObject();
                textNode.put("type", "text");
                textNode.put("text", prompt);
            }

            String resp = webClient.post()
                    .uri(CHAT_COMPLETIONS_PATH)
                    .bodyValue(body.toString())
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                    .block();

            return parseContent(resp);
        } catch (Exception e) {
            String detail = extractErrorBody(e);
            log.error("qwen3.5-ocr 调用失败: {}{}", e.getMessage(), detail, e);
            throw new RuntimeException("OCR 提取失败: " + e.getMessage() + detail, e);
        }
    }

    /** 解析响应 {@code choices[0].message.content}, 兼容字符串/数组两种返回格式 */
    private String parseContent(String resp) throws Exception {
        if (resp == null || resp.isBlank()) {
            throw new RuntimeException("OCR 返回空响应");
        }
        JsonNode root = mapper.readTree(resp);
        // 错误响应: {"code":"...","message":"..."} 或 {"error":{"message":"..."}}
        JsonNode errCode = root.path("code");
        if (!errCode.isMissingNode() && errCode.asText(null) != null
                && !"null".equals(errCode.asText(null))) {
            String msg = root.path("message").asText(errCode.asText());
            throw new RuntimeException("OCR 服务返回错误: " + msg);
        }
        JsonNode errorNode = root.path("error");
        if (!errorNode.isMissingNode()) {
            String msg = errorNode.path("message").asText("unknown error");
            throw new RuntimeException("OCR 服务返回错误: " + msg);
        }
        JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
        if (contentNode.isMissingNode()) {
            throw new RuntimeException("OCR 响应缺少 choices[0].message.content: " + resp);
        }
        // content 可能是字符串, 也可能是数组(标准 OpenAI 多模态返回)
        if (contentNode.isTextual()) {
            return contentNode.asText();
        }
        if (contentNode.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : contentNode) {
                String t = part.path("text").asText("");
                if (!t.isEmpty()) sb.append(t);
            }
            return sb.toString();
        }
        throw new RuntimeException("OCR 响应 content 类型无法解析: " + contentNode);
    }

    /** 从 WebClient 异常中提取服务端返回的原始错误体, 便于排查 400/401 */
    private String extractErrorBody(Throwable e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof WebClientResponseException wcre) {
                String b = wcre.getResponseBodyAsString();
                if (b != null && !b.isBlank()) {
                    return " [服务端返回: " + b + "]";
                }
                return " [HTTP " + wcre.getStatusCode() + "]";
            }
            t = t.getCause();
        }
        return "";
    }
}
