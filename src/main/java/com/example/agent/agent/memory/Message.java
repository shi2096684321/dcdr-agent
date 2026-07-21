package com.example.agent.agent.memory;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对话消息(记忆层使用)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Message {

    /** 角色: user / assistant / system */
    private String role;
    /** 消息内容 */
    private String content;
    /** 使用的模型(assistant消息) */
    private String model;

    public static Message system(String content) {
        return new Message("system", content, null);
    }

    public static Message user(String content) {
        return new Message("user", content, null);
    }

    public static Message assistant(String content, String model) {
        return new Message("assistant", content, model);
    }
}
