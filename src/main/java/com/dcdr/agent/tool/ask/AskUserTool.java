package com.dcdr.agent.tool.ask;

import com.dcdr.agent.tool.AbstractTool;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 向用户提问工具(需要用户确认 / 补充信息时使用)。
 *
 * <p><b>这不是一个"会被执行"的工具</b>: 编排层在 {@code executeTools} 之前就把它拦下,
 * 转成 {@code ChatChunk.user_input_request} 下发, 然后本轮立即结束。{@link #execute} 只在
 * "参数不合 schema(问题列表为空)"时才会被调用, 返回一句可读的失败让模型重试。
 *
 * <p>为什么用工具而不是让模型自己输出一段 JSON 标记: 工具定义能把"问题几个、选项几个、
 * 每个字段叫什么"约束死, 弱模型也不会跑偏; 而自定义标记只能靠提示词, 一旦格式跑偏前端就解析不出来。
 *
 * <p>为什么选项数限制在 2-4: 少于 2 个不叫"选项"(用户直接说就行), 多于 4 个卡片会失去可扫读性;
 * label 限长同理 —— 不限长的选项会把卡片撑爆。
 */
@Component
public class AskUserTool extends AbstractTool {

    /** 工具名: 编排层对本名有硬编码拦截, 改名必须同步 {@code AgentOrchestrator} */
    public static final String NAME = "ask_user";

    /** 单次最多提问数: 超过 4 个, 用户要在一张卡片里回答的问题就多得没法看 */
    private static final int MAX_QUESTIONS = 4;
    /** 单个问题最多选项数 */
    private static final int MAX_OPTIONS = 4;
    /** label 落库/下发前的硬截断长度 */
    private static final int MAX_LABEL_LEN = 50;
    private static final int MAX_HEADER_LEN = 20;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "向用户提问并给出若干可点选的选项, 用于「缺少关键信息、无法从上下文推断」或「操作有风险需要确认」的场景。"
                + "调用后本轮对话立即结束, 用户在界面上选择或自行输入, 你的下一轮会收到答案。\n"
                + "使用要求:\n"
                + "1) 调用前先用一句话说明「当前进度 + 问完准备做什么」—— 用户回答后你是在新的上下文中继续, "
                + "正文里没有的信息你就当没想过; \n"
                + "2) 能从上下文推断、或能用已有工具查到的, 不要问;\n"
                + "3) 同一个问题只问一次, 用户已经明确回答过的不要再问;\n"
                + "4) 每次 1-4 个问题, 每个问题 2-4 个选项, 选项要互斥且可直接执行(不要出现「其他」这类选项, 用户本来就能自己输入);\n"
                + "5) 不要用它做开放式闲聊式提问, 也不要用它代替正常的追问。";
    }

    @Override
    public JsonObjectSchema getParameters() {
        return JsonObjectSchema.builder()
                .addProperty("questions", JsonArraySchema.builder()
                        .items(JsonObjectSchema.builder()
                                .addProperty("question", JsonStringSchema.builder()
                                        .description("要问用户的完整问题, 一句话说清, 不要包含选项内容").build())
                                .addProperty("header", JsonStringSchema.builder()
                                        .description("问题的短标签(如「部署方式」), 用于卡片标题, 不超过 12 个字").build())
                                .addProperty("options", JsonArraySchema.builder()
                                        .items(JsonObjectSchema.builder()
                                                .addProperty("label", JsonStringSchema.builder()
                                                        .description("选项文案, 简短明确, 不超过 50 个字").build())
                                                .addProperty("description", JsonStringSchema.builder()
                                                        .description("选项的一句话说明(可选), 帮助用户判断, 不要与 label 重复").build())
                                                .build())
                                        .description("可点选的选项, 2-4 个").build())
                                .addProperty("multiSelect", JsonBooleanSchema.builder()
                                        .description("是否允许多选, 默认 false(单选)").build())
                                .build())
                        .description("要问用户的问题列表, 1-4 个").build())
                .required(List.of("questions"))
                .build();
    }

    /**
     * 正常链路不会走到这里: 编排层在工具执行前就把本工具转成了提问卡片。
     *
     * <p>走到这里只有一种情况 —— 参数不合 schema(问题列表为空/解析失败)。此时返回一句可读的
     * 失败原因, 由通用工具回灌链路交回模型重试; <b>不要抛异常</b>: 异常会被工具桥接包装成
     * 一大段堆栈文本, 模型既看不懂也不知道该怎么改。
     */
    @Override
    public String execute(Map<String, Object> params) {
        return "提问失败: questions 不能为空, 每个问题都要有 question 字段。请重新组织问题后再调用一次。";
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    /**
     * 把模型给的 arguments 归一化成前端可直接渲染的问题列表。
     *
     * <p>做三件事: ① 补服务端生成的稳定 id(q1/q2…)——不能让模型自己编, 否则它两次调用
     * 生成的 id 不同, 用户回传的答案就配不上; ② 把选项统一成 {@code {value,label,description}}
     * (模型可能给纯字符串, 也可能给对象); ③ 截断超长文案, 防止卡片被一段话撑爆。
     *
     * @param argsJson 工具调用的 arguments 原文
     * @return 归一化后的问题列表; 解析不出来时返回空列表(调用方据此放弃提问、走普通工具链路)
     */
    public static List<Map<String, Object>> parseQuestions(String argsJson) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (argsJson == null || argsJson.isBlank()) return result;

        Map<String, Object> args;
        try {
            args = MAPPER.readValue(argsJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return result;
        }
        Object rawQuestions = args.get("questions");
        if (!(rawQuestions instanceof List<?> list)) return result;

        int index = 0;
        for (Object item : list) {
            if (index >= MAX_QUESTIONS) break;
            if (!(item instanceof Map<?, ?> q)) continue;
            String question = asText(q.get("question"));
            if (question == null || question.isBlank()) continue;

            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("id", "q" + (index + 1));
            normalized.put("question", question);
            String header = asText(q.get("header"));
            normalized.put("header", header == null ? null : truncate(header, MAX_HEADER_LEN));
            normalized.put("options", normalizeOptions(q.get("options")));
            normalized.put("multiSelect", Boolean.TRUE.equals(q.get("multiSelect")));
            result.add(normalized);
            index++;
        }
        return result;
    }

    /** 选项归一化: 兼容纯字符串与 {label/description/value} 两种形态 */
    private static List<Map<String, Object>> normalizeOptions(Object rawOptions) {
        List<Map<String, Object>> options = new ArrayList<>();
        if (!(rawOptions instanceof List<?> list)) return options;

        for (Object item : list) {
            if (options.size() >= MAX_OPTIONS) break;
            String label;
            String description = null;
            String value;
            if (item instanceof Map<?, ?> m) {
                label = asText(m.get("label"));
                if (label == null || label.isBlank()) label = asText(m.get("text"));
                if (label == null || label.isBlank()) label = asText(m.get("value"));
                description = asText(m.get("description"));
                value = asText(m.get("value"));
            } else {
                label = asText(item);
                value = null;
            }
            if (label == null || label.isBlank()) continue;
            label = truncate(label, MAX_LABEL_LEN);
            Map<String, Object> option = new LinkedHashMap<>();
            // value 与 label 分开: label 要给用户看(可能很长), value 要回传(要稳定)。
            // 模型通常不给 value, 此时以 label 作为 value —— 用户回传后再由修复器原样交给模型。
            option.put("value", (value == null || value.isBlank()) ? label : value);
            option.put("label", label);
            if (description != null && !description.isBlank()) {
                option.put("description", truncate(description, 100));
            }
            options.add(option);
        }
        return options;
    }

    private static String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
