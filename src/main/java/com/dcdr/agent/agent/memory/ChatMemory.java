package com.dcdr.agent.agent.memory;

import java.util.List;

/**
 * 对话记忆接口 - 管理对话历史和上下文
 */
public interface ChatMemory {

    /** 加载会话历史(全量, 用于前端展示) */
    List<Message> load(String sessionId);

    /**
     * 加载发送给 LLM 的窗口化历史: 保留全部 system 消息 + 最近 maxMessages 条非 system 消息。
     * <p>仅截取视图, 不删除数据库记录(完整历史仍可通过会话界面查看)。
     * 返回列表条数 < load() 条数时表示已触发记忆窗口截断。
     */
    List<Message> loadWindow(String sessionId);

    /** 保存消息(用户消息 + 助手回复) */
    void save(String sessionId, String userMessage, String assistantMessage, String model);

    /** 保存单条消息 */
    void save(String sessionId, Message message);

    /** 清空会话记忆 */
    void clear(String sessionId);

    /** 删除会话中 seq >= fromSeq 的消息(用于编辑/重试时截断历史) */
    void deleteFrom(String sessionId, int fromSeq);
}
