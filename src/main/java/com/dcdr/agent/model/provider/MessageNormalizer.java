package com.dcdr.agent.model.provider;

import com.dcdr.agent.agent.memory.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * 协议出口的对话消息归一化: 把内部"多条 system 消息"压成上游可接受的单条 system。
 *
 * <p><b>为什么必须有这一层</b>: 本平台在编排层按"片段 + name 去重"注入 system
 * (平台规则、长期记忆、当前时间、技能目录、工作区摘要、外部数据边界等, 见
 * {@code AgentOrchestrator#injectRuntimeSystemPrompts}), 这些片段在历史里是<b>多条</b>
 * system 消息, 且多由 {@code history.add(...)} 追加在列表尾部。
 * OpenAI 官方协议允许多条 system 出现在任意位置, 但自部署的 Qwen3 系模型
 * (vLLM 的 chat_template) 只接受 {@code messages[0]} 一条 system —— 第二条即
 * {@code raise_exception('System message must be at the beginning.')} 返回 400。
 *
 * <p><b>为什么放在 Provider 而不是改编排层</b>: 成熟 agent 客户端(CodeBuddy / WorkBuddy)
 * 的 system 只有一个槽位, 自然不存在该问题; 本平台已是多片段形态, 若回调编排层要动
 * 6 处注入点 + 调用链分段展示 + 上下文占用分段统计。在唯一的协议出口合并, 覆盖面相同
 * (stream / invoke / 工具循环 / 路由 / 摘要全部经过此处), 改动面却最小,
 * 且与它们"在协议转换层兜底"的做法一致。
 *
 * <p><b>合并口径</b>: 按出现顺序拼接(越靠后的片段越具体, 优先级语义不变), 以
 * {@code ---} 分隔便于人读; 合并后的消息固定放在索引 0, 其余消息保持相对顺序 ——
 * 这同时消除了"system 插在 tool 结果之后"这类会让严格端点校验失败的形状。
 */
public final class MessageNormalizer {

    /** system 片段之间的分隔符: 保留原编排层"分段"的可读性(与调用链展示同口径) */
    private static final String SYSTEM_SEPARATOR = "\n\n---\n\n";

    private static final String ROLE_SYSTEM = "system";

    private MessageNormalizer() {
    }

    /**
     * 合并历史中的全部 system 消息为一条并置顶。
     *
     * <p>无需合并时(无 system, 或仅首条 system)直接返回入参列表, 不产生拷贝 ——
     * 该方法是每个请求的必经路径, 避免无意义的列表复制。
     *
     * @param history 业务视角的历史(可能为 null)
     * @return 归一化后的新列表(必要时才新建); 入参不会被修改
     */
    public static List<Message> mergeSystemMessages(List<Message> history) {
        if (history == null) {
            return List.of();
        }
        if (history.isEmpty()) {
            return history;
        }

        int systemCount = 0;
        StringBuilder mergedText = new StringBuilder();
        String mergedName = null;
        for (Message msg : history) {
            if (!ROLE_SYSTEM.equals(msg.getRole())) {
                continue;
            }
            systemCount++;
            // 空内容的 system 片段不参与合并(与 Provider 映射层"空内容不发送"同口径)
            if (msg.getContent() == null || msg.getContent().isBlank()) {
                continue;
            }
            if (mergedText.length() > 0) {
                mergedText.append(SYSTEM_SEPARATOR);
            }
            mergedText.append(msg.getContent());
            if (mergedName == null) {
                mergedName = msg.getName();
            }
        }

        boolean onlyOneAtHead = systemCount == 1 && ROLE_SYSTEM.equals(history.get(0).getRole());
        if (systemCount == 0 || onlyOneAtHead) {
            return history;
        }

        List<Message> merged = new ArrayList<>(history.size());
        if (mergedText.length() > 0) {
            merged.add(Message.system(mergedName, mergedText.toString()));
        }
        for (Message msg : history) {
            if (ROLE_SYSTEM.equals(msg.getRole())) {
                continue;
            }
            merged.add(msg);
        }
        return merged;
    }
}
