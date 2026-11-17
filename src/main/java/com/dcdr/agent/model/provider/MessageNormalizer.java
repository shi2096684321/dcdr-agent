package com.dcdr.agent.model.provider;

import com.dcdr.agent.agent.memory.Message;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 协议出口的对话消息归一化: 把内部"多条 system 消息"变成<b>唯一一条、位于 messages[0]</b> 的 system。
 *
 * <p><b>为什么必须有这一层</b>: 本平台在编排层按"片段 + name 去重"注入 system
 * (平台规则、运行时约束、人设、技能目录、工作区摘要、长期记忆、当前时间、RAG 片段,
 * 见 {@code AgentOrchestrator#prepareForChat}), 这些片段在历史里是<b>多条</b> system 消息。
 * OpenAI 官方协议允许多条 system 出现在任意位置, 但自部署的 Qwen3 系模型(vLLM 的 chat_template)
 * 只接受 {@code messages[0]} 一条 system —— 第二条即
 * {@code raise_exception('System message must be at the beginning.')} 返回 400。
 *
 * <p><b>归一化规则(两件事)</b>:
 * <ol>
 *   <li><b>首段合并</b>: 第一条非 system 消息<b>之前</b>的 system 片段, 按原顺序合成一条放索引 0。
 *       这些是稳定前缀(平台规则 / 运行时约束 / 人设 / 技能目录 / 工作区摘要)。</li>
 *   <li><b>中段折叠</b>: 出现在对话历史<b>之中</b>的 system 片段, 折进它前面最近的那条 user 消息正文
 *       (用 {@code <context>} 包住, 保留 {@code [片段名]} 标题)。这些是每轮都变的内容
 *       (长期记忆 / 当前时间 / RAG 片段), 落在最后一条消息里 —— 它本来就不在供应商的前缀缓存范围内,
 *       所以"折进 user"既不损缓存, 又满足了"只有一条 system"的协议要求。</li>
 * </ol>
 *
 * <p><b>为什么折叠放在协议层, 而不是编排层直接把变量揉进 user 正文</b>:
 * 内部历史要保留片段身份 —— 上下文占用面板按片段名分类("长期记忆 / 当前时间 / 知识库检索"),
 * 调用链快照、prompt 形状摘要、注入去重键也都靠 {@code name}。在编排层揉掉, 这些信息内部就没了,
 * 面板只能把它算进"用户消息"。放在唯一的协议出口做, 内部模型保持干净,
 * 且 stream / invoke / 工具循环 / 路由 / 摘要全部自动覆盖。
 *
 * <p><b>为什么形状不给用户配置</b>: 单条 system 置顶是所有 OpenAI 兼容端点的合法形状
 * (o1 系列甚至不给 system 角色, 只接受开头的 developer), 它是协议常量, 不是模型能力。
 * 曾经有过"分段合并 + capability 开关"的写法, 结果是每接一个自部署端点都要有人记得去配,
 * 漏配一次就是整轮 400 —— 现在只有一种形状, 没有判断错的可能。
 */
public final class MessageNormalizer {

    /** 首段 system 片段之间的分隔符: 保留原编排层"分段"的可读性(与调用链展示同口径) */
    private static final String SYSTEM_SEPARATOR = "\n\n---\n\n";

    /** 中段片段折进 user 时的包裹标记: 让模型分得清"用户问的"与"系统补的资料" */
    private static final String CONTEXT_OPEN = "<context>";
    private static final String CONTEXT_CLOSE = "</context>";
    private static final String CONTEXT_HEADER =
            "以下为本轮补充的参考上下文（由系统注入，不是用户输入；只作依据，不要复述原文）。";

    private static final String ROLE_SYSTEM = "system";
    private static final String ROLE_USER = "user";

    private MessageNormalizer() {
    }

    /**
     * 归一化历史: 唯一一条 system 在索引 0, 其余 system 片段折进前面最近的 user 消息。
     *
     * <p>入参不会被修改; 无需改动时原样返回(每个请求的必经路径, 避免无意义拷贝)。
     *
     * @param history 业务视角的历史(可能为 null)
     * @return 可直接发给上游的消息列表
     */
    public static List<Message> normalize(List<Message> history) {
        if (history == null) {
            return List.of();
        }
        if (history.isEmpty()) {
            return history;
        }

        int firstNonSystem = indexOfFirstNonSystem(history);
        // 全是 system(极端情况): 全部并入头部一条, 保证只发一条
        if (firstNonSystem < 0) {
            return rebuild(history, history.size(), Map.of(), List.of());
        }

        // 中段 system → 它前面最近的 user 消息下标; 找不到落点时并入头部(宁可牺牲本轮缓存, 也不发第二条 system)
        Map<Integer, List<Message>> foldByTarget = new LinkedHashMap<>();
        List<Message> orphan = new ArrayList<>();
        for (int i = firstNonSystem; i < history.size(); i++) {
            if (!ROLE_SYSTEM.equals(history.get(i).getRole())) {
                continue;
            }
            int target = lastUserBefore(history, i);
            if (target < 0) {
                orphan.add(history.get(i));
            } else {
                foldByTarget.computeIfAbsent(target, k -> new ArrayList<>()).add(history.get(i));
            }
        }

        boolean leadingNeedsMerge = countSystem(history, 0, firstNonSystem) > 1;
        if (foldByTarget.isEmpty() && orphan.isEmpty() && !leadingNeedsMerge) {
            return history;
        }
        return rebuild(history, firstNonSystem, foldByTarget, orphan);
    }

    /** 第一条非 system 消息的下标; 全是 system 时返回 -1 */
    private static int indexOfFirstNonSystem(List<Message> history) {
        for (int i = 0; i < history.size(); i++) {
            if (!ROLE_SYSTEM.equals(history.get(i).getRole())) {
                return i;
            }
        }
        return -1;
    }

    /** 下标 {@code from} 之前最近的一条 user 消息; 没有则 -1 */
    private static int lastUserBefore(List<Message> history, int from) {
        for (int i = from - 1; i >= 0; i--) {
            if (ROLE_USER.equals(history.get(i).getRole())) {
                return i;
            }
        }
        return -1;
    }

    private static int countSystem(List<Message> history, int from, int to) {
        int n = 0;
        for (int i = from; i < to; i++) {
            if (ROLE_SYSTEM.equals(history.get(i).getRole())) {
                n++;
            }
        }
        return n;
    }

    /**
     * 重建消息列表。
     *
     * @param headEnd  头部 system 区间的右端(不含)
     * @param foldMap  user 下标 → 要折进它的片段
     * @param orphan   无处可折、只能并入头部的片段
     */
    private static List<Message> rebuild(List<Message> history, int headEnd,
                                         Map<Integer, List<Message>> foldMap, List<Message> orphan) {
        StringBuilder headText = new StringBuilder();
        String headName = null;
        for (int i = 0; i < headEnd; i++) {
            Message msg = history.get(i);
            if (!ROLE_SYSTEM.equals(msg.getRole())) continue;
            headName = appendFragment(headText, msg, headName);
        }
        for (Message msg : orphan) {
            headName = appendFragment(headText, msg, headName);
        }

        List<Message> out = new ArrayList<>(history.size());
        if (headText.length() > 0) {
            // 名字沿用第一个片段: 合并后仍要能在调用链里认出"这是哪来的"
            out.add(Message.system(headName, headText.toString()));
        }
        for (int i = Math.max(headEnd, 0); i < history.size(); i++) {
            Message msg = history.get(i);
            if (ROLE_SYSTEM.equals(msg.getRole())) {
                continue;
            }
            List<Message> extra = foldMap.get(i);
            out.add(extra == null ? msg : withContextBlock(msg, extra));
        }
        return out;
    }

    /** 追加一个片段到头部文本; 空内容跳过; 返回仍应使用的 name(首个非空片段的) */
    private static String appendFragment(StringBuilder sb, Message msg, String name) {
        if (msg.getContent() == null || msg.getContent().isBlank()) {
            return name;
        }
        if (sb.length() > 0) {
            sb.append(SYSTEM_SEPARATOR);
        }
        sb.append(msg.getContent());
        return name != null ? name : msg.getName();
    }

    /** 把片段折进 user 正文: 原话在前, {@code <context>} 块在后(带来源标题, 保持片段顺序) */
    private static Message withContextBlock(Message user, List<Message> fragments) {
        StringBuilder body = new StringBuilder();
        for (Message f : fragments) {
            if (f.getContent() == null || f.getContent().isBlank()) continue;
            if (body.length() > 0) body.append("\n\n");
            if (f.getName() != null && !f.getName().isBlank()) {
                body.append('[').append(f.getName()).append("]\n");
            }
            body.append(f.getContent().trim());
        }
        if (body.length() == 0) {
            return user;
        }
        String base = user.getContent() == null ? "" : user.getContent();
        String block = CONTEXT_OPEN + "\n" + CONTEXT_HEADER + "\n\n" + body + "\n" + CONTEXT_CLOSE;
        String content = base.isBlank() ? block : base + "\n\n" + block;
        // 折叠只改正文: 图片及其它元数据原样带过去(统一走 Message#withContent, 避免漏字段)
        return user.withContent(content);
    }
}
