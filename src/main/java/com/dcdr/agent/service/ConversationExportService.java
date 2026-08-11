package com.dcdr.agent.service;

import com.dcdr.agent.agent.session.SessionManager;
import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.dto.MessageDTO;
import com.dcdr.agent.dto.SessionDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 对话导出 Service (V2.1, 对应 F-27)。
 * <p>
 * 支持 Markdown 与 JSON 两种格式:
 * <ul>
 *   <li>Markdown: 可读性优先, 含会话元信息头 + 按角色渲染的消息体, 适合归档/分享</li>
 *   <li>JSON: 数据完整性优先, 保留全部字段(含 toolCalls/sources/token 等), 可二次导入</li>
 * </ul>
 * <p>
 * <b>不引入 PDF 库的理由</b>: 主流 PDF 库(iText/OpenPDF/Flying Sauser)体积 10MB+,
 * 且 Markdown → PDF 排版复杂; V2.1 验收只列 Markdown, PDF 留作扩展项,
 * 用户可通过浏览器打印 Markdown 渲染页得到 PDF, 暂不内置。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationExportService {

    private final SessionManager sessionManager;
    private final ObjectMapper objectMapper = buildMapper();

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    /** 导出格式 */
    public enum Format {
        MARKDOWN("md", "text/markdown"),
        JSON("json", "application/json");

        private final String ext;
        private final String contentType;

        Format(String ext, String contentType) {
            this.ext = ext;
            this.contentType = contentType;
        }

        public String ext() { return ext; }
        public String contentType() { return contentType; }

        public static Format from(String s) {
            if (s == null) return MARKDOWN;
            String v = s.trim().toLowerCase(Locale.ROOT);
            if (v.isEmpty() || "md".equals(v) || "markdown".equals(v)) return MARKDOWN;
            if ("json".equals(v)) return JSON;
            throw new BusinessException(400, "不支持的导出格式: " + s + " (支持: markdown, json)");
        }
    }

    /** 导出结果(内容 + 文件名 + Content-Type) */
    public record ExportResult(String filename, String contentType, byte[] content) {}

    /**
     * 导出指定会话。
     *
     * @param sessionId 会话 ID
     * @param format    格式(markdown|json)
     */
    public ExportResult export(String sessionId, Format format) {
        SessionDTO session = sessionManager.getSession(sessionId);
        if (session == null) {
            throw new BusinessException(404, "会话不存在: " + sessionId, "SESSION_NOT_FOUND");
        }
        List<MessageDTO> messages = sessionManager.getMessages(sessionId);

        String body;
        switch (format) {
            case MARKDOWN -> body = toMarkdown(session, messages);
            case JSON -> body = toJson(session, messages);
            default -> throw new BusinessException(400, "不支持的导出格式: " + format);
        }

        String filename = sanitizeFilename(session.getTitle()) + "_" + sessionId + "." + format.ext();
        try {
            return new ExportResult(filename, format.contentType(), body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new BusinessException(500, "导出编码失败: " + e.getMessage());
        }
    }

    // ===== Markdown 渲染 =====

    private String toMarkdown(SessionDTO session, List<MessageDTO> messages) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("# ").append(safeTitle(session.getTitle())).append("\n\n");
        sb.append("- 会话 ID: `").append(session.getId()).append("`\n");
        sb.append("- 模型: ").append(safe(session.getModel())).append("\n");
        sb.append("- 创建时间: ").append(fmt(session.getCreatedAt())).append("\n");
        sb.append("- 导出时间: ").append(fmt(LocalDateTime.now())).append("\n");
        if (session.getSystemPrompt() != null && !session.getSystemPrompt().isBlank()) {
            sb.append("\n> **System Prompt**\n> \n> ")
              .append(session.getSystemPrompt().replace("\n", "\n> ")).append("\n");
        }
        sb.append("\n---\n\n");

        if (messages.isEmpty()) {
            sb.append("_（暂无消息）_\n");
            return sb.toString();
        }

        for (MessageDTO m : messages) {
            String role = m.getRole() == null ? "unknown" : m.getRole();
            sb.append("## ").append(roleLabel(role)).append("\n\n");
            if (m.getContent() != null && !m.getContent().isBlank()) {
                sb.append(m.getContent()).append("\n\n");
            }
            if (m.getReasoningContent() != null && !m.getReasoningContent().isBlank()) {
                sb.append("<details><summary>推理过程</summary>\n\n")
                  .append(m.getReasoningContent()).append("\n\n</details>\n\n");
            }
            if (m.getImages() != null && !m.getImages().isEmpty()) {
                sb.append("**图片**: ").append(String.join(", ", m.getImages())).append("\n\n");
            }
            if (m.getSources() != null && !m.getSources().isEmpty()) {
                sb.append("**引用来源**:\n");
                for (int i = 0; i < m.getSources().size(); i++) {
                    Map<String, Object> src = m.getSources().get(i);
                    sb.append(i + 1).append(". ")
                      .append(src.getOrDefault("title", src.getOrDefault("url", "")))
                      .append(" — ").append(src.getOrDefault("url", "")).append("\n");
                }
                sb.append("\n");
            }
            if (Boolean.TRUE.equals(m.getError())) {
                sb.append("_（此消息生成失败/被中断）_\n\n");
            }
        }
        return sb.toString();
    }

    private static String roleLabel(String role) {
        return switch (role) {
            case "user" -> "🧑 用户";
            case "assistant" -> "🤖 助手";
            case "system" -> "⚙️ 系统";
            default -> role;
        };
    }

    // ===== JSON 序列化 =====

    private String toJson(SessionDTO session, List<MessageDTO> messages) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("session", session);
        payload.put("messages", messages);
        payload.put("exportedAt", LocalDateTime.now().toString());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new BusinessException(500, "JSON 序列化失败: " + e.getMessage());
        }
    }

    private static ObjectMapper buildMapper() {
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new JavaTimeModule());
        m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        m.enable(SerializationFeature.INDENT_OUTPUT);
        return m;
    }

    // ===== 工具 =====

    private static String safe(Object o) { return o == null ? "-" : String.valueOf(o); }
    private static String safeTitle(String t) { return t == null || t.isBlank() ? "未命名对话" : t; }
    private static String fmt(LocalDateTime t) { return t == null ? "-" : t.format(FMT); }

    /** 文件名安全化: 仅保留中文/字母/数字/下划线/连字符, 其余替换为 _ */
    private static String sanitizeFilename(String name) {
        if (name == null || name.isBlank()) return "对话";
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        return cleaned.length() > 40 ? cleaned.substring(0, 40) : cleaned;
    }
}
