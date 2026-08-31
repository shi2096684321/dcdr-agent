package com.dcdr.agent.agent.memory;

import com.dcdr.agent.config.AgentProperties;
import com.dcdr.agent.dto.ChatResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * token 估算器 (jtokkit BPE 分词)
 * <p>
 * 内核为 jtokkit 的 cl100k_base 编码(jtokkit 是 langchain4j-open-ai 的 compile 传递依赖,
 * 无需额外引入), 替代早期「CJK=1.0 / 其他=0.3」字符启发式, 中英混排场景精度显著提升。
 * 对 Qwen 等非 OpenAI 模型仍为近似值(各家分词器不同)。
 * <p>
 * 用于判断会话上下文是否接近模型窗口上限, 触发「建议开启新对话」提示,
 * 以及流式中断/模型未返回 usage 时的 token 兜底估算。
 * 估算为近似值, 阈值请在 application.yml 按所用模型微调。
 */
@Component
public class TokenEstimator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** cl100k_base 编码(GPT-3.5/4 系); Encoding 无状态且线程安全, 全局单例即可 */
    private static final Encoding ENCODING =
            Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);

    private final int contextTokenLimit;
    private final double contextWarnRatio;

    public TokenEstimator(AgentProperties properties) {
        this.contextTokenLimit = properties.getMemory().getContextTokenLimit();
        this.contextWarnRatio = properties.getMemory().getContextWarnRatio();
    }

    /** 估算一组消息的总 token 数(含推理内容、工具调用往返) */
    public int estimate(List<Message> messages) {
        int tokens = 0;
        for (Message m : messages) {
            tokens += estimate(m.getContent());
            if (m.getReasoningContent() != null) {
                tokens += estimate(m.getReasoningContent());
            }
            // 工具往返: assistant 的 tool_calls 结构化字段与 tool 角色的名称/调用 id 也都占 token
            if (m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {
                try {
                    tokens += estimate(MAPPER.writeValueAsString(m.getToolCalls()));
                } catch (Exception ignored) {
                    tokens += estimate(m.getToolCalls().toString());
                }
            }
            if (m.getToolCallId() != null) tokens += estimate(m.getToolCallId());
            if (m.getName() != null) tokens += estimate(m.getName());
        }
        return tokens;
    }

    /** 估算单段文本的 token 数 (BPE 分词) */
    public int estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        return ENCODING.countTokens(text);
    }

    /** 上下文是否已达到提示阈值 */
    public boolean isNearLimit(List<Message> messages) {
        return estimate(messages) >= contextTokenLimit * contextWarnRatio;
    }

    /**
     * 统一的「真实 usage 优先, 估算兜底」收敛入口。
     * <p>模型返回了 usage (total>0) 时直接透传; 否则用 BPE 估算:
     * prompt = 上下文消息, completion = 正文 + 思维链。
     * 供 AgentOrchestrator 的流式收尾/中断路径共用, 避免兜底逻辑散落多处。
     *
     * @param usage         模型真实返回的 usage (可空)
     * @param context       发给模型的上下文消息 (prompt 估算用)
     * @param content       已生成的正文
     * @param reasoning     已生成的思维链 (可空)
     */
    public ChatResponse.TokenUsage resolveUsage(ChatResponse.TokenUsage usage,
            List<Message> context, String content, String reasoning) {
        if (usage != null && usage.getTotal() > 0) return usage;
        ChatResponse.TokenUsage r = new ChatResponse.TokenUsage();
        r.setPrompt(estimate(context));
        r.setCompletion(estimate(content) + estimate(reasoning));
        r.setTotal(r.getPrompt() + r.getCompletion());
        return r;
    }

    public int getContextTokenLimit() {
        return contextTokenLimit;
    }
}
