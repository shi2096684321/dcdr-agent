package com.example.agent.agent.memory;

import com.example.agent.config.AgentProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启发式 token 估算器 (无真实分词器)
 * <p>
 * - CJK 字符(中日韩)约 1.5 token
 * - 其他字符(英文/数字/标点)约 0.3 token
 * <p>
 * 用于判断会话上下文是否接近模型窗口上限, 触发「建议开启新对话」提示。
 * 估算为近似值, 阈值请在 application.yml 按所用模型微调。
 */
@Component
public class TokenEstimator {

    private final int contextTokenLimit;
    private final double contextWarnRatio;

    public TokenEstimator(AgentProperties properties) {
        this.contextTokenLimit = properties.getMemory().getContextTokenLimit();
        this.contextWarnRatio = properties.getMemory().getContextWarnRatio();
    }

    /** 估算一组消息的总 token 数(含推理内容) */
    public int estimate(List<Message> messages) {
        int tokens = 0;
        for (Message m : messages) {
            tokens += estimate(m.getContent());
            if (m.getReasoningContent() != null) {
                tokens += estimate(m.getReasoningContent());
            }
        }
        return tokens;
    }

    /** 估算单段文本的 token 数 */
    public int estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        double tokens = 0;
        for (int i = 0; i < text.length(); i++) {
            tokens += isCjk(text.charAt(i)) ? 1.5 : 0.3;
        }
        return (int) Math.ceil(tokens);
    }

    /** 上下文是否已达到提示阈值 */
    public boolean isNearLimit(List<Message> messages) {
        return estimate(messages) >= contextTokenLimit * contextWarnRatio;
    }

    public int getContextTokenLimit() {
        return contextTokenLimit;
    }

    private boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || (c >= 0x3040 && c <= 0x30FF)   // 日文假名
                || (c >= 0xAC00 && c <= 0xD7A3);  // 韩文
    }
}
