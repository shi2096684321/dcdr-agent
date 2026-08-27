package com.dcdr.agent.agent.memory;

import com.dcdr.agent.config.AgentProperties;
import com.dcdr.agent.entity.MessageEntity;
import com.dcdr.agent.repository.MessageRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public InMemoryChatMemory(AgentProperties properties, MessageRepository messageRepository) {
        this.maxMessages = properties.getMemory().getMaxMessages();
        this.messageRepository = messageRepository;
    }

    private static List<String> parseImages(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private static String toJson(List<String> images) {
        if (images == null || images.isEmpty()) return null;
        try {
            return MAPPER.writeValueAsString(images);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<Map<String, Object>> parseSources(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private static String toJsonSources(List<Map<String, Object>> sources) {
        if (sources == null || sources.isEmpty()) return null;
        try {
            return MAPPER.writeValueAsString(sources);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<Map<String, Object>> parseToolCalls(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private static String toJsonToolCalls(List<Map<String, Object>> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) return null;
        try {
            return MAPPER.writeValueAsString(toolCalls);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public List<Message> load(String sessionId) {
        List<MessageEntity> entities = messageRepository.findBySessionIdOrderBySeqAsc(sessionId);
        List<Message> messages = new ArrayList<>(entities.size());
        for (MessageEntity e : entities) {
            Message m = new Message(e.getRole(), e.getContent(), e.getModel(), e.getReasoningContent());
            m.setTokenUsage(e.getTokenUsage());
            m.setError(e.getError());
            m.setInterrupted(e.getInterrupted());
            m.setSeq(e.getSeq());
            m.setImages(parseImages(e.getImages()));
            m.setSources(parseSources(e.getSources()));
            m.setType(e.getType());
            m.setToolCalls(parseToolCalls(e.getToolCallsJson()));
            m.setToolCallId(e.getToolCallId());
            m.setName(e.getToolName());
            m.setVisionModel(e.getVisionModel());
            m.setImageStatus(e.getImageStatus());
            m.setImageCaption(e.getImageCaption());
            m.setImageTokenUsage(e.getImageTokenUsage());
            messages.add(m);
        }
        return messages;
    }

    @Override
    public List<Message> loadWindow(String sessionId) {
        List<Message> all = load(sessionId);
        // system 消息(会话级提示词/运行时约束)不占窗口配额, 始终保留
        List<Message> systemMsgs = new ArrayList<>();
        List<Message> others = new ArrayList<>();
        for (Message m : all) {
            if ("system".equals(m.getRole())) {
                systemMsgs.add(m);
            } else {
                others.add(m);
            }
        }
        if (others.size() <= maxMessages) {
            return all;
        }
        // 仅截取视图, 不删除数据库记录(区别于旧版直接删库导致会话历史丢失的实现)
        List<Message> windowed = new ArrayList<>(systemMsgs.size() + maxMessages);
        windowed.addAll(systemMsgs);
        windowed.addAll(others.subList(others.size() - maxMessages, others.size()));
        return windowed;
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
        entity.setType(message.getType() != null ? message.getType() : "text");
        entity.setContent(message.getContent());
        entity.setModel(message.getModel());
        entity.setCreatedAt(LocalDateTime.now());
        entity.setReasoningContent(message.getReasoningContent());
        entity.setTokenUsage(message.getTokenUsage());
        entity.setError(message.getError());
        entity.setInterrupted(message.getInterrupted());
        entity.setImages(toJson(message.getImages()));
        entity.setSources(toJsonSources(message.getSources()));
        entity.setToolCallsJson(toJsonToolCalls(message.getToolCalls()));
        entity.setToolCallId(message.getToolCallId());
        entity.setToolName(message.getName());
        entity.setVisionModel(message.getVisionModel());
        entity.setImageStatus(message.getImageStatus());
        entity.setImageCaption(message.getImageCaption());
        entity.setImageTokenUsage(message.getImageTokenUsage());
        messageRepository.save(entity);
    }

    @Override
    @Transactional
    public void clear(String sessionId) {
        messageRepository.deleteBySessionId(sessionId);
    }

    @Override
    @Transactional
    public void deleteFrom(String sessionId, int fromSeq) {
        List<MessageEntity> entities = messageRepository.findBySessionIdOrderBySeqAsc(sessionId);
        for (MessageEntity e : entities) {
            if (e.getSeq() >= fromSeq) {
                messageRepository.delete(e);
            }
        }
    }
}
