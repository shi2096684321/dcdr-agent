package com.dcdr.agent.agent.memory;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 对话消息(记忆层使用)
 */
@Data
@NoArgsConstructor
public class Message {

    /** 角色: user / assistant / system */
    private String role;
    /** 会话内序号(用于按 seq 截断历史) */
    private Integer seq;
    /** 消息内容 */
    private String content;
    /** 使用的模型(assistant消息) */
    private String model;
    /** 消息类型: text / image / video */
    private String type;
    /** 推理内容(思维链, assistant消息, 可能为空) */
    private String reasoningContent;
    /** token 消耗(assistant消息, 仅统计用, 可为空) */
    private Integer tokenUsage;

    /** 是否为失败(错误)消息 */
    private Boolean error;
    /** 是否被用户手动中断(产生不完整的回复) */
    private Boolean interrupted;
    /** 用户上传的图片URL列表(多模态, 可为空) */
    private List<String> images;

    /**
     * 助手消息携带的函数调用列表(OpenAI 工具调用往返)。
     * 形如 [{id, type:"function", function:{name, arguments}}]。
     * 仅 role=assistant 且发起工具调用时非空。
     */
    private List<Map<String, Object>> toolCalls;

    /** 工具结果消息对应的 tool_call id(role=tool 时), 用于匹配 assistant 的 tool_calls[].id */
    private String toolCallId;

    /** 工具名(role=tool 时填充, 便于 API 配对) */
    private String name;

    /** 引用来源(如联网搜索结果, 仅 assistant 消息), 供前端渲染引用卡片并随历史持久化 */
    private List<Map<String, Object>> sources;

    /**
     * 时间线：本轮「思考」与「工具调用」的先后顺序。
     * <p>形如 {@code [{"t":"reason","n":812},{"t":"tool","id":"call_1"},{"t":"reason","n":340}]}。
     * <p>为什么不是直接存每段的思考文本：{@link #reasoningContent} 存的已经是本轮所有轮次思考的拼接
     * （几百到上万字符），再存一份文本等于整段复制。<b>n</b> 只记"这段思考有多少字符"，
     * 前端按累加偏移去切 {@code reasoningContent} 即可还原分段；tool 项按 id 引用 {@link #toolCalls}，
     * 参数与结果不重复落库。
     * <p>缺省为 null（老数据）：前端此时退回"整段思考 + 全部工具"的旧排法，不会报错。
     */
    private List<Map<String, Object>> trace;

    /** 做图片识别的视觉模型名(降级场景, 非降级为 null) */
    private String visionModel;

    /** 图片识别状态: none(无图/视觉模型直接看) / recognized(降级识别成功) / failed(降级识别失败) */
    private String imageStatus;

    /** 图片识别结果原文(结构化存储, 与用户原话分离) */
    private String imageCaption;

    /** 图片识别消耗的 token(JSON: {"prompt":x,"completion":y,"total":z}, 可能为 null) */
    private String imageTokenUsage;

    public Message(String role, String content, String model) {
        this(role, content, model, null);
    }

    public Message(String role, String content, String model, String reasoningContent) {
        this.role = role;
        this.content = content;
        this.model = model;
        this.reasoningContent = reasoningContent;
    }

    /**
     * 复制本条消息并只替换正文，其它字段原样带过去。
     * <p>协议出口折叠上下文时需要换掉 user 正文（见 {@code MessageNormalizer#withContextBlock}）。
     * 逐字段手工复制太容易漏 —— 尤其之后往 Message 里加新字段时，集中在这里复制就不会静默丢数据。
     */
    public Message withContent(String newContent) {
        Message copy = new Message();
        copy.role = role;
        copy.seq = seq;
        copy.content = newContent;
        copy.model = model;
        copy.type = type;
        copy.reasoningContent = reasoningContent;
        copy.tokenUsage = tokenUsage;
        copy.error = error;
        copy.interrupted = interrupted;
        copy.images = images;
        copy.toolCalls = toolCalls;
        copy.toolCallId = toolCallId;
        copy.name = name;
        copy.sources = sources;
        copy.trace = trace;
        copy.visionModel = visionModel;
        copy.imageStatus = imageStatus;
        copy.imageCaption = imageCaption;
        copy.imageTokenUsage = imageTokenUsage;
        return copy;
    }

    public static Message system(String content) {
        return new Message("system", content, null);
    }

    /**
     * 带去重键的 system 消息: name 用于平台级运行时约束的注入幂等判断(按 name 去重, 而非内容比较)。
     *
     * @param name    去重键(如 "runtime:image-policy")，可为 null
     * @param content 消息内容
     */
    public static Message system(String name, String content) {
        Message m = new Message("system", content, null);
        m.setName(name);
        return m;
    }

    public static Message user(String content) {
        return new Message("user", content, null);
    }

    public static Message user(String content, List<String> images) {
        Message m = new Message("user", content, null);
        m.setImages(images);
        return m;
    }

    public static Message assistant(String content, String model) {
        return new Message("assistant", content, model);
    }

    /** 文生图结果消息: assistant 角色, 携带图片 URL 列表, type=image */
    public static Message assistantImage(List<String> imageUrls, String content, String model) {
        Message m = new Message("assistant", content, model);
        m.setImages(imageUrls);
        m.setType("image");
        return m;
    }

    public static Message assistant(String content, String reasoningContent, String model) {
        return new Message("assistant", content, model, reasoningContent);
    }

    public static Message assistant(String content, String reasoningContent, String model, Integer tokenUsage) {
        Message m = new Message("assistant", content, model, reasoningContent);
        m.setTokenUsage(tokenUsage);
        return m;
    }
}
