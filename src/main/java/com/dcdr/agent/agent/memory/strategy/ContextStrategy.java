package com.dcdr.agent.agent.memory.strategy;

import com.dcdr.agent.agent.memory.Message;

import java.util.List;

/**
 * 上下文策略(V3.4) —— 决定"这一轮到底把哪些历史发给模型"。
 *
 * <p><b>为什么抽成策略</b>: 此前这件事写死在 {@code ChatMemory#loadWindow} 里(只保留最近 N 条),
 * 想换一种做法只能改那个方法, 而它同时承担"存储"与"组装"两件事。拆开之后:
 * 存储(读写 messages 表)留在 {@code ChatMemory}; "发什么"由本接口的实现决定,
 * 用 {@code agent.memory.strategy} 选。两种做法的取舍不同, 没有哪一种永远更优 ——
 * 滑动窗口零成本零延迟但会断崖丢信息, 摘要压缩要额外调一次模型但能保留结论。
 *
 * <p><b>约束</b>: 实现必须是<b>自包含且可失败</b>的 —— 它会跑在每轮对话的准备阶段,
 * 任何异常或超时都不能让对话失败(见 {@link SummaryCompactionStrategy} 的降级路径)。
 */
public interface ContextStrategy {

    /** 策略标识, 与 {@code agent.memory.strategy} 取值一致 */
    String name();

    /**
     * 组装本轮发给模型的上下文视图。
     *
     * @param sessionId   会话 ID(摘要等持久化状态按会话存)
     * @param userId      当前用户(摘要调用的成本记账用; 可为 null)
     * @param fullHistory   该会话的全量历史(按 seq 升序); 实现只做视图裁剪, 不得删库
     * @param contextLimit  本轮生效的上下文上限(用户设置优先), 供按比例决定预算的策略使用
     * @return 视图 + 需要告知用户的说明(如"已压缩""已截断"), 无说明时 notice 为 {@code null}
     */
    View apply(String sessionId, String userId, List<Message> fullHistory, int contextLimit);

    /**
     * 组装结果。
     *
     * @param messages 实际发给模型的消息(含 system)
     * @param notice   需要下发给用户的提示; {@code null} 表示本轮没有任何裁剪/压缩
     */
    record View(List<Message> messages, String notice) {

        public static View of(List<Message> messages) {
            return new View(messages, null);
        }
    }
}
