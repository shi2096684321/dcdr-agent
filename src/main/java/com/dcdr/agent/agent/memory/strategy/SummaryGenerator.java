package com.dcdr.agent.agent.memory.strategy;

import com.dcdr.agent.agent.memory.Message;

import java.util.List;

/**
 * 摘要生成器 —— 把"更早的一段对话 + 已有摘要"压成一份新的摘要文本。
 *
 * <p><b>为什么抽成接口</b>: 这是整个压缩链路里唯一需要外部模型、唯一可能慢、唯一会花钱的一步。
 * 单独劈出来之后, 压缩策略本身(什么时候压、压到哪、失败怎么办)可以完全不依赖模型做单元测试 ——
 * 否则"超过阈值不压缩""失败要降级"这些关键分支只能靠真调模型来验证, 实际上就没人验证。
 *
 * <p>实现必须<b>永不抛异常</b>: 返回 {@code null} 表示这次压缩没成功, 由调用方决定降级方式。
 */
public interface SummaryGenerator {

    /**
     * @param existingSummary 已有摘要(可为 null; 增量压缩时把新旧合并成一份)
     * @param messages        待压缩的消息(按时间正序)
     * @param userId          当前用户(成本记账用)
     * @param sessionId       会话 ID(成本记账用)
     * @return 新摘要正文; 失败/超时/返回为空时返回 {@code null}
     */
    String summarize(String existingSummary, List<Message> messages, String userId, String sessionId);
}
