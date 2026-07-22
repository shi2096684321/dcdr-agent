package com.example.agent.agent.memory;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对话消息(记忆层使用)
 */
@Data
@NoArgsConstructor
public class Message {

    /** 角色: user / assistant / system */
    private String role;
    /** 消息内容 */
    private String content;
    /** 使用的模型(assistant消息) */
    private String model;
    /** 推理内容(思维链, assistant消息, 可能为空) */
    private String reasoningContent;

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

    public static Message assistant(String content, String model) {
        return new Message("assistant", content, model);
    }

    public static Message assistant(String content, String reasoningContent, String model) {
        return new Message("assistant", content, model, reasoningContent);
    }
}
