package com.dcdr.agent.agent.memory;

import java.util.List;

/**
 * 对话记忆接口 - 管理对话历史和上下文
 */
public interface ChatMemory {

    /** 加载会话历史 */
    List<Message> load(String sessionId);

    /** 保存消息(用户消息 + 助手回复) */
    void save(String sessionId, String userMessage, String assistantMessage, String model);

    /** 保存单条消息 */
    void save(String sessionId, Message message);

    /** 清空会话记忆 */
    void clear(String sessionId);
}
