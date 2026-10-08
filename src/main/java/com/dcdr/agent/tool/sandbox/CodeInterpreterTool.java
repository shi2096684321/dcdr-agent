package com.dcdr.agent.tool.sandbox;

import com.dcdr.agent.audit.AuditService;
import com.dcdr.agent.config.ToolsProperties;
import com.dcdr.agent.tool.AbstractTool;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 代码解释器工具(V3.1 F-43) —— 在受控沙箱中执行模型生成的 Python 代码。
 * <p>
 * <b>场景</b>: 精确计算 / 多步数值运算 / 统计聚合 —— 模型口算不可靠, 用户又需要可信结果。
 * 一期<b>只做纯计算</b>: 代码经文件投递到沙箱, 不挂载任何数据目录(见 TODO.md 的场景收敛结论)。
 * <p>
 * <b>五道闸</b>(顺序固定, 任一不过即拒绝):
 * <ol>
 *   <li>开关({@code agent-tools.sandbox.enabled}, 默认 false, 仅管理员可开)</li>
 *   <li>代码长度上限</li>
 *   <li>{@link CodeGuard} 静态审查(辅助防线)</li>
 *   <li>沙箱执行(禁网 / 资源配额 / 非 root / 单命令超时, 由 OpenSandbox 侧实施)</li>
 *   <li>审计留痕 + 输出截断</li>
 * </ol>
 * <p>
 * <b>审计只记代码摘要</b>(SHA-256 前 16 位 + 前 200 字符), 不落全文 ——
 * 防止用户数据/密钥经由代码内容进入审计表。
 * <p>
 * 工具实现类被 {@code ToolAutoConfiguration} 自动注册; 是否对某次对话可见,
 * 由 Agent 白名单({@code AgentDefinition.tools})与全局开关共同决定。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CodeInterpreterTool extends AbstractTool {

    /** 一期仅支持 Python */
    private static final String LANGUAGE = "python";

    /** 审计动作名(与 chat / chat_image 同一命名风格) */
    private static final String AUDIT_ACTION = "sandbox_exec";

    /** 代码摘要入库长度 */
    private static final int SNIPPET_MAX_LEN = 200;

    /** 审计中代码哈希的截断长度 */
    private static final int HASH_MAX_LEN = 16;

    private final ToolsProperties toolsProperties;
    private final OpenSandboxService sandboxService;
    private final AuditService auditService;

    @Override
    public String getName() {
        return "code_interpreter";
    }

    @Override
    public String getDescription() {
        return "Python 代码执行工具(受控沙箱, 无网络访问)。"
                + "适用于需要精确计算、多步数值运算、统计汇总等不信任心算结果的场景; "
                + "执行结果通过 print 输出。沙箱内无法访问网络、外部文件与数据目录。";
    }

    @Override
    public JsonObjectSchema getParameters() {
        return JsonObjectSchema.builder()
                .addProperty("code", JsonStringSchema.builder()
                        .description("要执行的 Python 代码, 需要用 print 输出结果")
                        .build())
                .required(List.of("code"))
                .build();
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public String execute(Map<String, Object> params) {
        ToolsProperties.SandboxConfig config = toolsProperties.getSandbox();

        // 闸1: 开关(默认关闭)
        if (!config.isEnabled()) {
            return "代码执行沙箱未启用";
        }

        String code = getString(params, "code");

        // 闸2: 代码非空 + 长度上限
        if (code == null || code.isBlank()) {
            return "请提供要执行的代码";
        }
        if (code.length() > config.getMaxCodeLength()) {
            return "代码长度超限(" + code.length() + " 字符 > 上限 "
                    + config.getMaxCodeLength() + " 字符)";
        }

        // 闸3: 静态审查(辅助防线, 主要边界在沙箱侧)
        String rejectReason = CodeGuard.check(LANGUAGE, code);
        if (rejectReason != null) {
            log.warn("沙箱代码被静态审查拒绝: {}", rejectReason);
            recordAudit(code, null, rejectReason);
            return "代码被安全策略拒绝: " + rejectReason;
        }

        // 闸4: 沙箱执行
        OpenSandboxService.SandboxExecResult result = sandboxService.execute(code);

        // 闸5: 审计 + 输出截断
        recordAudit(code, result, null);
        return truncate(result.output(), config.getMaxOutputLength());
    }

    // ===== 内部方法 =====

    /** 记录审计: 只记摘要(哈希 + 片段), 不落全文代码 */
    private void recordAudit(String code, OpenSandboxService.SandboxExecResult result, String rejectReason) {
        try {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("language", LANGUAGE);
            detail.put("codeHash", sha256Prefix(code));
            detail.put("codeSnippet", snippet(code));
            if (rejectReason != null) {
                detail.put("rejected", true);
                detail.put("reason", rejectReason);
            } else if (result != null) {
                detail.put("rejected", result.rejected());
                detail.put("ok", result.ok());
                detail.put("exitCode", result.exitCode());
                detail.put("durationMs", result.durationMs());
                detail.put("outputLength", result.output() == null ? 0 : result.output().length());
                if (result.message() != null) {
                    detail.put("message", result.message());
                }
            }
            // userId 必须显式传入(审计异步线程读不到请求上下文)
            auditService.record(AUDIT_ACTION, "session",
                    SandboxContextHolder.sessionId(), detail, SandboxContextHolder.userId());
        } catch (Exception e) {
            // 审计失败不影响工具结果
            log.warn("沙箱审计记录失败: {}", e.getMessage());
        }
    }

    /** SHA-256 前 16 位(用于跨记录关联同一段代码, 同时不泄露内容) */
    private static String sha256Prefix(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(hash);
            return hex.substring(0, HASH_MAX_LEN);
        } catch (NoSuchAlgorithmException e) {
            return "unavailable";
        }
    }

    /** 代码片段(前 N 字符, 超长加省略标记) */
    private static String snippet(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= SNIPPET_MAX_LEN
                ? text
                : text.substring(0, SNIPPET_MAX_LEN) + "...";
    }

    /** 输出截断: 沙箱自身的结果上限, 与回灌治理的 agent.tools.result.max-length 口径保持一致 */
    private static String truncate(String text, int maxLen) {
        if (text == null || text.isEmpty()) {
            return "(无输出)";
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "\n...(输出过长已截断)";
    }
}
