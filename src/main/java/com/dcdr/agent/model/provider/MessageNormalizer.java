package com.dcdr.agent.model.provider;

import com.dcdr.agent.agent.memory.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * 协议出口的对话消息归一化: 把内部"多条 system 消息"压成上游可接受的形态。
 *
 * <p><b>为什么必须有这一层</b>: 本平台在编排层按"片段 + name 去重"注入 system
 * (平台规则、运行时约束、人设、技能目录、工作区摘要、长期记忆、当前时间、RAG 片段,
 * 见 {@code AgentOrchestrator#prepareForChat}), 这些片段在历史里是<b>多条</b> system 消息。
 * OpenAI 官方协议允许多条 system 出现在任意位置, 但自部署的 Qwen3 系模型
 * (vLLM 的 chat_template) 只接受 {@code messages[0]} 一条 system —— 第二条即
 * {@code raise_exception('System message must be at the beginning.')} 返回 400。
 *
 * <p><b>为什么放在 Provider 而不是改编排层</b>: 成熟 agent 客户端(CodeBuddy / WorkBuddy)
 * 的 system 只有一个槽位, 自然不存在该问题; 本平台已是多片段形态, 若回调编排层要动
 * 多处注入点 + 调用链分段展示 + 上下文占用分段统计。在唯一的协议出口合并, 覆盖面相同
 * (stream / invoke / 工具循环 / 路由 / 摘要全部经过此处), 改动面却最小,
 * 且与它们"在协议转换层兜底"的做法一致。
 *
 * <h2>两种合并口径</h2>
 * <ul>
 *   <li><b>hoist(全量置顶)</b>: 所有 system 片段合成一条放在索引 0 —— 严格端点(vLLM Qwen3)
 *       唯一能接受的形状。代价是"每轮变化"的片段(记忆/时间/RAG)也被提到最前面,
 *       前缀缓存的公共前缀只能延续到这些片段开始的位置, 其后的全部对话历史都无法复用。</li>
 *   <li><b>split(分段合并, 默认)</b>: 首个非 system 消息<b>之前</b>的 system 合成一条放索引 0;
 *       <b>之后</b>的 system 合成一条放列表末尾。这样"稳定前缀 + 完整历史 + 本轮提问"
 *       构成一条长公共前缀, 只有末尾那段每轮变化的片段不可复用 —— 这是让前缀缓存
 *       真正生效的前提。模型侧用 capability {@value #CAPABILITY_SINGLE_SYSTEM} 声明自己只吃 hoist。</li>
 * </ul>
 */
public final class MessageNormalizer {

    /**
     * 模型能力标记: 该端点只接受「一条 system 且在 messages[0]」。
     * <p>自部署 vLLM / Qwen3 系需要在模型配置的 capabilities 里声明; 主流云厂商
     * (OpenAI / DeepSeek / 通义 / 智谱)不需要。
     */
    public static final String CAPABILITY_SINGLE_SYSTEM = "single_system";

    /** system 片段之间的分隔符: 保留原编排层"分段"的可读性(与调用链展示同口径) */
    private static final String SYSTEM_SEPARATOR = "\n\n---\n\n";

    private static final String ROLE_SYSTEM = "system";

    private MessageNormalizer() {
    }

    /**
     * 合并历史中的全部 system 消息为一条并置顶(hoist 口径, 严格端点兼容)。
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

    /**
     * 按合并口径归一化。
     *
     * @param hoistAll true = 全部 system 置顶(严格端点); false = 分段合并, 尾部 system 留在末尾
     */
    public static List<Message> mergeSystemMessages(List<Message> history, boolean hoistAll) {
        return hoistAll ? mergeSystemMessages(history) : splitSystemMessages(history);
    }

    /**
     * 分段合并: 首个非 system 之前的 system 合成一条置顶, 之后的合成一条放在末尾。
     * <p>
     * <b>为什么这样排</b>: 供应商的前缀缓存只认「从头开始的最长公共前缀」。每轮变化的内容
     * (长期记忆召回 / 当前时间 / RAG 片段)如果落在 system 里被提到最前面, 公共前缀就在那里
     * 断掉, 其后的全部对话历史都不可复用; 留在末尾则「稳定前缀 + 完整历史 + 本轮提问」
     * 整段可复用。
     * <p>无需改动时(头部至多一条 system 且尾部无 system)原样返回入参, 不产生拷贝。
     */
    private static List<Message> splitSystemMessages(List<Message> history) {
        if (history == null) {
            return List.of();
        }
        if (history.isEmpty()) {
            return history;
        }

        int firstNonSystem = -1;
        for (int i = 0; i < history.size(); i++) {
            if (!ROLE_SYSTEM.equals(history.get(i).getRole())) {
                firstNonSystem = i;
                break;
            }
        }
        // 全是 system(极端情况, 正常请求不会发生): 退回全量置顶, 保证只发一条
        if (firstNonSystem < 0) {
            return mergeSystemMessages(history);
        }

        int headSystems = firstNonSystem;
        int tailSystems = 0;
        for (int i = firstNonSystem; i < history.size(); i++) {
            if (ROLE_SYSTEM.equals(history.get(i).getRole())) tailSystems++;
        }
        // 头部至多一条 system 且没有尾部 system → 已经是对的形状
        if (headSystems <= 1 && tailSystems == 0) {
            return history;
        }

        String headText = joinSystemText(history, 0, firstNonSystem);
        String headName = firstSystemName(history, 0, firstNonSystem);
        String tailText = joinSystemText(history, firstNonSystem, history.size());
        String tailName = firstSystemName(history, firstNonSystem, history.size());

        List<Message> normalized = new ArrayList<>(history.size());
        if (headText != null) {
            normalized.add(Message.system(headName, headText));
        }
        for (int i = firstNonSystem; i < history.size(); i++) {
            Message msg = history.get(i);
            if (ROLE_SYSTEM.equals(msg.getRole())) continue;
            normalized.add(msg);
        }
        if (tailText != null) {
            normalized.add(Message.system(tailName, tailText));
        }
        return normalized;
    }

    /** 拼接 [from, to) 区间内的非空 system 文本; 全空返回 null(该段不产生消息) */
    private static String joinSystemText(List<Message> history, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            Message msg = history.get(i);
            if (!ROLE_SYSTEM.equals(msg.getRole())) continue;
            if (msg.getContent() == null || msg.getContent().isBlank()) continue;
            if (sb.length() > 0) sb.append(SYSTEM_SEPARATOR);
            sb.append(msg.getContent());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 区间内第一条非空 system 的 name(合并后沿用, 便于调用链与日志追溯来源) */
    private static String firstSystemName(List<Message> history, int from, int to) {
        for (int i = from; i < to; i++) {
            Message msg = history.get(i);
            if (ROLE_SYSTEM.equals(msg.getRole())
                    && msg.getContent() != null && !msg.getContent().isBlank()) {
                return msg.getName();
            }
        }
        return null;
    }
}
