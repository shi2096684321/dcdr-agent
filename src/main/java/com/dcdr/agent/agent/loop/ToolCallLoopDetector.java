package com.dcdr.agent.agent.loop;

import com.dcdr.agent.agent.memory.Message;
import com.dcdr.agent.dto.ChatChunk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具调用循环检测(纯静态, 无状态)。
 *
 * <p><b>解决什么问题</b>: 编排层此前只有"同一轮内参数完全相同的调用只执行一次"这一道拦截,
 * 它<b>跨轮无效</b> —— 模型在第 2、3、4 轮反复发起同一个检索时不会被打断, 只能等
 * {@code MAX_TOOL_ROUNDS=10} 兜底。表现是"一直在搜同一个东西", 用户看到的是一条条重复的工具卡片。
 *
 * <p><b>为什么不直接抄"连续 N 次就掐断"</b>: 正常的多轮检索(搜 → 读到不够 → 换词再搜)
 * 与病态循环(反复发同一个 query)在次数上无法区分, 只能靠<b>签名是否相同</b>区分。
 * 所以判定条件是"连续 N 次<b>相同签名</b>", 换了关键词就不算循环。
 *
 * <p><b>为什么签名要先把参数 JSON 归一化</b>: 模型两次调用可能写成
 * {@code {"query":"a","maxResults":8}} 与 {@code { "maxResults": 8, "query": "a" }} ——
 * 字段顺序与空白不同, 语义完全一致。直接拿原始字符串比对会漏判, 于是循环检测形同虚设。
 *
 * <p><b>为什么不持有状态</b>: 响应式链路里线程会切, 有状态就只能靠 ThreadLocal 或往
 * {@code executeTools} 再加参数。而连续调用序列本来就能从 {@code history} 里读出来
 * (assistant 消息带着 {@code toolCalls}), 反推即可 —— 少一个状态就少一处不一致。
 *
 * <p>阈值 4 的出处: 与本项目对标的实现一致(连续 4 次相同调用才介入)。放宽到 4 是为了
 * 不打扰正常重试: 搜一次失败、再搜一次、再确认一次, 都属于合理行为。
 *
 * <p><b>已知的弱保证</b>: 读的是 {@code history}({@code ArrayList}), 写入发生在上一个
 * {@code boundedElastic} 线程, 读取发生在下一个 —— 严格说没有 happens-before。
 * Reactor 内部的内存屏障让实际出错概率极低, 且这里最坏的结果是"某次循环没被检测到"
 * (退回改造前的行为), 不会误拦。所以刻意不加锁: 为这个概率上锁会拖慢每一轮工具调用。
 * 若将来出现"循环检测偶发不生效", 先怀疑这里。
 */
public final class ToolCallLoopDetector {

    /** 连续相同签名的阈值: 达到该次数后, 后续重复调用被拦截 */
    public static final int CONSECUTIVE_THRESHOLD = 4;

    /** 参数超过该长度就不做 JSON 归一化(归一化要解析+重建, 超大参数下纯属浪费) */
    private static final int MAX_NORMALIZE_CHARS = 65536;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolCallLoopDetector() {
    }

    /**
     * 找出本轮里应当被拦截的重复调用。
     *
     * @param history      到此为止的对话历史(含前几轮的 assistant(tool_calls) 消息), 可为 null
     * @param currentCalls 本轮待执行的调用
     * @return 需要拦截的调用 ID 集合; 为空表示没有循环
     */
    public static Set<String> detectRepeats(List<Message> history, List<ChatChunk> currentCalls) {
        if (currentCalls == null || currentCalls.isEmpty()) {
            return Set.of();
        }
        // 历史里已经发生过的调用签名, 按时间正序
        List<String> sequence = new ArrayList<>();
        if (history != null) {
            for (Message m : history) {
                if (m.getToolCalls() == null || m.getToolCalls().isEmpty()) {
                    continue;
                }
                for (Map<String, Object> call : m.getToolCalls()) {
                    sequence.add(signature(str(call.get("name")), str(call.get("arguments"))));
                }
            }
        }
        // 本轮调用接在历史后面, 形成一条完整的调用序列(只放签名, 不复制参数体)
        int base = sequence.size();
        for (ChatChunk tc : currentCalls) {
            sequence.add(signature(tc.getTool(), tc.getArgs()));
        }

        Set<String> repeated = new LinkedHashSet<>();
        for (int k = 0; k < currentCalls.size(); k++) {
            int idx = base + k;
            String sig = sequence.get(idx);
            int streak = 0;
            for (int i = idx; i >= 0; i--) {
                if (!sequence.get(i).equals(sig)) {
                    break;
                }
                streak++;
            }
            // 允许前 THRESHOLD 次: 搜一次、失败再搜一次、再确认一次都属于合理行为,
            // 超过才判定为卡在同一个调用上
            if (streak > CONSECUTIVE_THRESHOLD) {
                repeated.add(currentCalls.get(k).getId());
            }
        }
        return repeated;
    }

    /** 调用签名: 工具名 + 归一化参数。同名不同参不算循环, 参数语义相同才算 */
    public static String signature(String tool, String args) {
        return (tool == null ? "" : tool) + '\u0000' + canonicalJson(args);
    }

    /**
     * 参数 JSON 归一化: 递归按 key 排序后重新序列化。
     * <p>数组<b>不</b>排序 —— 顺序在多数工具参数里有语义(如 URL 列表的优先级)。
     * 解析失败时退回去掉首尾空白的原文: 宁可漏判也不能因为脏参数抛异常打断对话。
     */
    public static String canonicalJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return "{}";
        }
        if (raw.length() > MAX_NORMALIZE_CHARS) {
            return raw.trim();
        }
        try {
            return MAPPER.writeValueAsString(sortKeys(MAPPER.readTree(raw)));
        } catch (Exception e) {
            return raw.trim();
        }
    }

    private static JsonNode sortKeys(JsonNode node) {
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            ObjectNode sorted = MAPPER.createObjectNode();
            for (String name : names) {
                sorted.set(name, sortKeys(node.get(name)));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode arr = MAPPER.createArrayNode();
            node.forEach(e -> arr.add(sortKeys(e)));
            return arr;
        }
        return node;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
