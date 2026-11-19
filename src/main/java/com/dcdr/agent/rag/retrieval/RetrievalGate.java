package com.dcdr.agent.rag.retrieval;

import java.util.List;
import java.util.Locale;

/**
 * 本轮消息是否值得跑一次知识库检索（确定性门控，零成本）。
 *
 * <p><b>为什么需要门控</b>: 会话挂了知识库后是"每轮强制检索"。碰上「谢谢」「好的」这类消息，
 * 既白付一次 embedding + 向量查询，又可能把不相关片段塞进上下文干扰回答。
 *
 * <p><b>为什么只放行寒暄，不拦「继续 / 然后呢」</b>: 检索片段只活在本轮的 volatileTail 里，
 * <b>不落库</b>（见 {@code AgentOrchestrator.retrieveAndInject} 的说明）。也就是说，"跳过检索"
 * 等于这一轮模型手上<b>完全没有资料</b> —— 对上文延续类问题，那不是省成本，是逼它编。
 * 所以这里只拦"与资料无关"的致谢/确认/问候，任何可能依赖上文的表达一律放行。
 *
 * <p>纯函数、无副作用，便于单测锁住这条契约。
 */
public final class RetrievalGate {

    /**
     * 明确的寒暄/致谢/确认：整句（去掉标点、符号与空白后）<b>完全等于</b>其中之一才跳过。
     * <p>用"整句相等"而不是"包含"：包含判断会把「谢谢，那报销流程呢」这类真问题一起拦掉。
     */
    private static final List<String> SMALL_TALK = List.of(
            "你好", "您好", "hi", "hello", "hey", "在吗", "在么",
            "谢谢", "多谢", "感谢", "谢了", "thanks", "thankyou", "thx",
            "好的", "好", "行", "可以", "嗯", "嗯嗯", "哦", "收到", "明白", "了解", "知道了",
            "ok", "okay", "gotit", "太好了", "厉害", "牛"
    );

    private RetrievalGate() {
    }

    /**
     * @param message 用户本轮原话
     * @return true=值得检索; false=纯寒暄/空消息，跳过检索
     */
    public static boolean shouldRetrieve(String message) {
        if (message == null) return false;
        // \p{P}=标点, \p{S}=符号(含 emoji), \s=空白; 中文标点也在这两类里
        String normalized = message.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]", "");
        if (normalized.isEmpty()) return false;
        return !SMALL_TALK.contains(normalized);
    }
}
