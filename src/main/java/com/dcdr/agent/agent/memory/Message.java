package com.dcdr.agent.agent.memory;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

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

    public static Message system(String content) {
        return new Message("system", content, null);
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
