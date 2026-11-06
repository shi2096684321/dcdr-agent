package com.dcdr.agent.agent.memory.strategy;

import com.dcdr.agent.agent.memory.Message;
import com.dcdr.agent.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 滑动窗口策略(V3.4 之前的唯一实现, 现在作为可选方案保留)。
 *
 * <p><b>规则</b>: 保留全部 system 消息 + 最近 {@code agent.memory.max-messages} 条其它消息,
 * 只裁剪视图、不删库。超出窗口的早期消息对模型而言<b>直接消失</b>。
 *
 * <p><b>什么时候还该用它</b>: 上下文预算极紧、或历史确实只是一次性问答(用完即弃)的场景 ——
 * 它零额外成本、零延迟、行为完全可预测。相比之下摘要压缩要多调一次模型, 且在摘要质量差时
 * 反而丢得更多(被"概括"掉比被"截断"更隐蔽)。
 */
@Component
@RequiredArgsConstructor
public class SlidingWindowStrategy implements ContextStrategy {

    public static final String NAME = "sliding-window";

    private final AgentProperties properties;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public View apply(String sessionId, String userId, List<Message> fullHistory, int contextLimit) {
        if (fullHistory == null || fullHistory.isEmpty()) return View.of(List.of());

        int maxMessages = Math.max(1, properties.getMemory().getMaxMessages());
        List<Message> systemMsgs = new ArrayList<>();
        List<Message> others = new ArrayList<>();
        for (Message m : fullHistory) {
            if ("system".equals(m.getRole())) {
                systemMsgs.add(m);
            } else {
                others.add(m);
            }
        }
        if (others.size() <= maxMessages) return View.of(fullHistory);

        List<Message> windowed = new ArrayList<>(systemMsgs.size() + maxMessages);
        windowed.addAll(systemMsgs);
        windowed.addAll(others.subList(others.size() - maxMessages, others.size()));

        int dropped = others.size() - maxMessages;
        String notice = String.format(
                "对话已达记忆窗口上限，仅保留最近 %d 条消息参与回答（已省略最早 %d 条），完整历史仍可在会话中查看。",
                maxMessages, dropped);
        return new View(windowed, notice);
    }
}
