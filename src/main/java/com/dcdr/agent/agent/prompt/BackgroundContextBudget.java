package com.dcdr.agent.agent.prompt;

import com.dcdr.agent.config.ContextBudgetProperties;
import com.dcdr.agent.rag.config.RagProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 「背景信息」统一预算器 —— 长期记忆与 RAG 检索片段共用一套账。
 * <p>
 * 两类内容都注入 system 上下文, 但在这次之前是<b>两套互不感知的天花板</b>:
 * 记忆只有条数上限, 检索片段只有自己的字符预算。谁先占、占多少、超了砍谁, 没有规则可依。
 * <p>
 * 记账顺序与注入顺序一致(记忆在 3.1.3、检索片段在 3.3): 记忆先占, 检索片段拿剩下的。
 * 之所以这么排见 {@link ContextBudgetProperties} 的类注释。
 * <p>
 * 无状态、线程安全。
 */
@Component
@RequiredArgsConstructor
public class BackgroundContextBudget {

    private final ContextBudgetProperties properties;
    private final RagProperties ragProperties;

    /** 长期记忆这段最多可用多少字符 */
    public int memoryMaxChars() {
        return properties.getMemoryMaxChars();
    }

    /**
     * 长期记忆已占用 {@code usedChars} 后, 检索片段还能用多少字符。
     * <p>
     * 取两层限制的较小值: ①RAG 自己的注入预算; ②背景总量减去记忆的实际占用。
     * <b>默认配置下结果恒等于 ①</b>(记忆常态只占 1~2k, 总量 9000 减完仍有 7k 以上),
     * 也就是这条规则平时不改变任何行为, 只在记忆变重时才收紧检索片段。
     *
     * @param usedChars 记忆那一段在上下文里实际占用的字符数; 负数按 0 处理
     * @return 检索片段可用的字符预算; <b>恒 &gt;= 1</b>(含义见方法内注释)
     */
    public int ragBudget(int usedChars) {
        int total = properties.getBackgroundMaxTotalChars();
        int ragOwn = injectConfig().getMaxTotalChars();   // <= 0 表示不限制
        if (total <= 0) {
            // 未设总量上限 → 完全沿用检索侧的配置, 不引入任何新的收紧
            return ragOwn;
        }
        int remaining = Math.max(1, total - Math.max(0, usedChars));
        if (ragOwn <= 0) {
            return remaining;
        }
        return Math.min(ragOwn, remaining);
    }

    private RagProperties.InjectConfig injectConfig() {
        return ragProperties.getInject() != null
                ? ragProperties.getInject() : new RagProperties.InjectConfig();
    }
}
