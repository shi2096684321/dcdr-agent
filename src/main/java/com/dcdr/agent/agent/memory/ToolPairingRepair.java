package com.dcdr.agent.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具调用配对修复器: 给历史里「没有结果的 assistant.tool_calls」补一条 tool 消息。
 *
 * <p><b>为什么必须有这层</b>: OpenAI 兼容协议要求每个 {@code tool_calls[].id} 都有一条
 * {@code role=tool} 的回灌消息, 否则下一轮请求直接被上游 400 拒掉
 * (messages with role 'tool' must be a response to a preceding message with 'tool_calls' 的反面:
 * tool_calls 后面必须跟 tool 结果)。而本项目里至少有四条路径会留下悬空调用:
 * <ul>
 *   <li>向用户提问(ask_user): 刻意不执行, 等用户下一条消息回答 —— 这是本类存在的主因;</li>
 *   <li>用户点「停止」: 上游被取消, 结果永远回不来;</li>
 *   <li>模型调用报错 / 进程重启: 落库了 assistant(tool_calls) 但没走到回灌那一步;</li>
 *   <li>同一批调用里前面的执行了、后面的因预算/上限被拦下。</li>
 * </ul>
 *
 * <p><b>为什么每次请求都重算, 而不把补出来的 tool 消息落库</b>:
 * <ol>
 *   <li>协议要求 tool 消息紧跟对应的 assistant 消息, 但 {messages} 表是 seq 追加的 ——
 *       补写只会追加到会话末尾, 顺序天然是错的, 下次加载依旧得修, 等于白写;</li>
 *   <li>它在语义上是「派生数据」: 给定历史 + 本次输入, 答案唯一确定, 每次算一遍即可;</li>
 *   <li>于是天然幂等、天然自愈 —— 中途失败一次不影响下次, 也不需要事务去兜。</li>
 * </ol>
 * 代价是历史里会多出一条「模型看到过、但用户看不到」的 tool 消息, 这是预期的:
 * 它对用户没有展示价值, 对模型是必需的上下文。
 */
public final class ToolPairingRepair {

    /** 向用户提问工具名(与 {@code AskUserTool.NAME} 一致, 此处不反向依赖工具包) */
    private static final String ASK_USER = "ask_user";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 非提问类的悬空调用: 明说没执行, 避免模型以为调用成功、拿空结果继续推理 */
    private static final String NOT_EXECUTED = "该工具未执行完成（本轮被中断或提前结束），没有产生结果。";
    /** 更早的提问没有等到回答(用户跳过了, 或隔了几轮才继续) */
    private static final String ASK_SKIPPED = "用户未回答这个问题（已跳过）。";

    private ToolPairingRepair() {
    }

    /**
     * 修复历史中的悬空工具调用。
     *
     * @param history        已落库的历史视图(不含本轮用户消息)
     * @param userInput      本轮用户输入(作为最后一个提问的答案原文)
     * @param questionAnswer 前端回传的结构化答案(questionId → 选项值/值列表), 可为 null
     * @return 修复后的历史; 无需修复时原样返回同一个对象(调用方可以靠引用相等跳过拷贝)
     */
    public static List<Message> repair(List<Message> history, String userInput,
                                       Map<String, Object> questionAnswer) {
        if (history == null || history.isEmpty()) return history;

        Set<String> resolved = new HashSet<>();
        for (Message m : history) {
            if ("tool".equals(m.getRole()) && m.getToolCallId() != null) {
                resolved.add(m.getToolCallId());
            }
        }

        List<Message> repaired = null;
        for (int i = 0; i < history.size(); i++) {
            Message m = history.get(i);
            List<Map<String, Object>> toolCalls = m.getToolCalls();
            if (!"assistant".equals(m.getRole()) || toolCalls == null || toolCalls.isEmpty()) {
                if (repaired != null) repaired.add(m);
                continue;
            }
            // 这条 assistant 是不是历史最后一条: 是 → 提问还没有人回答, 答案就是本轮这条输入;
            // 不是 → 用户早就答过了, 答案要从它<b>后面紧邻的那条 user 消息</b>取。
            // 这条判定不能省: 修复结果不落库, 每次请求都会重算一遍, 若一律拿"本轮输入"当答案,
            // 用户下一轮随便说句话就会把上一次的答案覆盖掉。
            boolean pendingNow = i == history.size() - 1;
            Message follower = pendingNow ? null : history.get(i + 1);
            boolean followerIsAnswer = follower != null && "user".equals(follower.getRole());

            List<Message> missing = new ArrayList<>();
            for (Map<String, Object> tc : toolCalls) {
                String id = asText(tc.get("id"));
                if (id == null || resolved.contains(id)) continue;
                boolean isAsk = ASK_USER.equals(toolName(tc));
                String content;
                if (!isAsk) {
                    content = NOT_EXECUTED;
                } else if (pendingNow) {
                    content = answerPayload(userInput, questionAnswer);
                } else if (followerIsAnswer) {
                    content = answerPayload(follower.getContent(), null);
                } else {
                    // 提问之后紧跟的不是用户消息(如被压缩/被删) → 明确说"没答", 别让模型自己脑补
                    content = ASK_SKIPPED;
                }
                Message tool = new Message("tool", content, null);
                tool.setToolCallId(id);
                tool.setName(toolName(tc));
                missing.add(tool);
            }
            if (missing.isEmpty()) {
                if (repaired != null) repaired.add(m);
                continue;
            }
            if (repaired == null) {
                repaired = new ArrayList<>(history.size() + missing.size());
                repaired.addAll(history.subList(0, i));
            }
            repaired.add(m);
            // 同一批调用的结果必须紧跟 assistant 消息, 顺序与 tool_calls 一致
            repaired.addAll(missing);
        }

        return repaired != null ? repaired : history;
    }

    /**
     * 把用户这一轮的输入包装成提问的工具结果。
     * <p>
     * 结构化答案优先: 多问题场景下, 纯文本「Docker Compose」根本看不出回答的是哪一题;
     * 拿不到结构化答案(用户没点选项、直接打了一句话)时才退化成文本。
     */
    private static String answerPayload(String userInput, Map<String, Object> questionAnswer) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "user_answer");
        Map<String, List<String>> answers = normalizeAnswer(questionAnswer);
        if (!answers.isEmpty()) {
            payload.put("answers", answers);
        }
        if (userInput != null && !userInput.isBlank()) {
            payload.put("message", userInput);
        }
        if (answers.isEmpty() && (userInput == null || userInput.isBlank())) {
            payload.put("skipped", true);
        }
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            // 序列化失败不该让整轮对话失败: 退化成纯文本, 模型仍能读懂
            return userInput == null ? ASK_SKIPPED : userInput;
        }
    }

    /** 兼容三种入参形态: {q1: ["a"]} / {q1: "a"} / {questions:[{id, answers:[...]}]} */
    private static Map<String, List<String>> normalizeAnswer(Map<String, Object> raw) {
        Map<String, List<String>> answers = new HashMap<>();
        if (raw == null || raw.isEmpty()) return answers;

        Object questions = raw.get("questions");
        if (questions instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> q)) continue;
                String id = asText(q.get("id"));
                if (id == null) continue;
                List<String> values = toValues(q.get("answers"));
                if (!values.isEmpty()) answers.put(id, values);
            }
            if (!answers.isEmpty()) return answers;
        }
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            if ("questions".equals(e.getKey())) continue;
            List<String> values = toValues(e.getValue());
            if (!values.isEmpty()) answers.put(e.getKey(), values);
        }
        return answers;
    }

    private static List<String> toValues(Object raw) {
        List<String> values = new ArrayList<>();
        if (raw == null) return values;
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                String text = asText(item);
                if (text != null && !text.isBlank()) values.add(text);
            }
            return values;
        }
        String text = asText(raw);
        if (text != null && !text.isBlank()) values.add(text);
        return values;
    }

    /** tool_calls 元素可能是扁平的 {id,name} 也可能是 {id,function:{name}}, 两种都要认 */
    private static String toolName(Map<String, Object> toolCall) {
        String name = asText(toolCall.get("name"));
        if (name != null) return name;
        Object fn = toolCall.get("function");
        if (fn instanceof Map<?, ?> map) return asText(map.get("name"));
        return null;
    }

    private static String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
