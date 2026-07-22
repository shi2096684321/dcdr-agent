package com.example.agent.agent.memory;

import com.example.agent.config.AgentProperties;
import com.example.agent.entity.MessageEntity;
import com.example.agent.repository.MessageRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 JPA 持久化的对话记忆
 * <p>
 * 按会话隔离，限制最大消息数(上下文窗口控制)。
 * 消息持久化到 messages 表，应用重启后历史不丢失。
 */
@Component
public class InMemoryChatMemory implements ChatMemory {

    private final int maxMessages;
    private final MessageRepository messageRepository;

    public InMemoryChatMemory(AgentProperties properties, MessageRepository messageRepository) {
        this.maxMessages = properties.getMemory().getMaxMessages();
        this.messageRepository = messageRepository;
    }

    @Override
    public List<Message> load(String sessionId) {
        List<MessageEntity> entities = messageRepository.findBySessionIdOrderBySeqAsc(sessionId);
        List<Message> messages = new ArrayList<>(entities.size());
        for (MessageEntity e : entities) {
            messages.add(new Message(e.getRole(), e.getContent(), e.getModel(), e.getReasoningContent()));
        }
        return messages;
    }

    @Override
    @Transactional
    public void save(String sessionId, String userMessage, String assistantMessage, String model) {
        save(sessionId, Message.user(userMessage));
        save(sessionId, Message.assistant(assistantMessage, model));
    }

    @Override
    @Transactional
    public void save(String sessionId, Message message) {
        int nextSeq = messageRepository.findTopBySessionIdOrderBySeqDesc(sessionId)
                .map(MessageEntity::getSeq).orElse(-1) + 1;

        MessageEntity entity = new MessageEntity();
        entity.setSessionId(sessionId);
        entity.setSeq(nextSeq);
        entity.setRole(message.getRole());
        entity.setType("text");
        entity.setContent(message.getContent());
        entity.setModel(message.getModel());
        entity.setCreatedAt(LocalDateTime.now());
        entity.setReasoningContent(message.getReasoningContent());
        messageRepository.save(entity);

        // 上下文窗口控制: 超出时截断最早的非 system 消息
        long count = messageRepository.countBySessionId(sessionId);
        if (count > maxMessages) {
            List<MessageEntity> all = messageRepository.findBySessionIdOrderBySeqAsc(sessionId);
            int toRemove = (int) (count - maxMessages);
            for (MessageEntity e : all) {
                if (toRemove <= 0) break;
                // 始终保留 system 消息
                if (!"system".equals(e.getRole())) {
                    messageRepository.delete(e);
                    toRemove--;
                }
            }
        }
    }

    @Override
    @Transactional
    public void clear(String sessionId) {
        messageRepository.deleteBySessionId(sessionId);
    }
}
