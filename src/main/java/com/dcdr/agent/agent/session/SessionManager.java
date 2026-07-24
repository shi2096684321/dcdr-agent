package com.dcdr.agent.agent.session;

import com.dcdr.agent.agent.memory.ChatMemory;
import com.dcdr.agent.agent.memory.Message;
import com.dcdr.agent.dto.MessageDTO;
import com.dcdr.agent.dto.SessionDTO;
import com.dcdr.agent.entity.MessageEntity;
import com.dcdr.agent.entity.SessionEntity;
import com.dcdr.agent.repository.MessageRepository;
import com.dcdr.agent.repository.SessionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 会话管理器 (对话模式) - 管理会话的创建、切换、删除
 * <p>
 * 基于 JPA 持久化，会话元数据存 sessions 表，消息历史存 messages 表。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionManager {

    /** 默认会话标题，首个用户问题到达时会被替换 */
    private static final String DEFAULT_TITLE = "新对话";

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final ChatMemory memory;

    /** 创建新会话 */
    @Transactional
    public SessionDTO createSession(String title, String model, String systemPrompt) {
        String id = "sess_" + System.currentTimeMillis();
        LocalDateTime now = LocalDateTime.now();
        SessionEntity entity = new SessionEntity();
        entity.setId(id);
        entity.setTitle(title != null ? title : DEFAULT_TITLE);
        entity.setTitleAuto(true);
        entity.setModel(model);
        entity.setSystemPrompt(systemPrompt);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        sessionRepository.save(entity);

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            memory.save(id, Message.system(systemPrompt));
        }
        log.info("创建会话: {} ({})", id, entity.getTitle());
        return toDTO(entity);
    }

    /** 获取会话 */
    public SessionDTO getSession(String id) {
        return sessionRepository.findById(id).map(SessionManager::toDTO).orElse(null);
    }

    /** 获取所有会话列表 */
    public List<SessionDTO> listSessions() {
        return sessionRepository.findAllByOrderByUpdatedAtDesc().stream()
                .map(SessionManager::toDTO)
                .toList();
    }

    /** 删除会话 */
    @Transactional
    public void deleteSession(String id) {
        sessionRepository.deleteById(id);
        messageRepository.deleteBySessionId(id);
        memory.clear(id);
        log.info("删除会话: {}", id);
    }

    /** 获取会话历史消息 */
    public List<MessageDTO> getMessages(String sessionId) {
        List<Message> messages = memory.load(sessionId);
        // 优先用记忆模块(含窗口截断逻辑)；若为空则回退查库
        if (!messages.isEmpty()) {
            List<MessageDTO> dtos = new java.util.ArrayList<>();
            int idx = 0;
            for (Message msg : messages) {
                if ("system".equals(msg.getRole())) continue;
                MessageDTO dto = new MessageDTO();
                dto.setId(sessionId + "_" + (++idx));
                dto.setSessionId(sessionId);
                dto.setRole(msg.getRole());
                dto.setType("text");
                dto.setContent(msg.getContent());
                dto.setReasoningContent(msg.getReasoningContent());
                dto.setModel(msg.getModel());
                dto.setSeq(msg.getSeq());
                dto.setError(msg.getError());
                dto.setImages(msg.getImages());
                dto.setCreatedAt(LocalDateTime.now());
                dtos.add(dto);
            }
            return dtos;
        }
        // 回退: 直接查 messages 表
        return messageRepository.findBySessionIdOrderBySeqAsc(sessionId).stream()
                .filter(m -> !"system".equals(m.getRole()))
                .map(SessionManager::toMessageDTO)
                .toList();
    }

    /** 更新会话标题 */
    @Transactional
    public void updateTitle(String sessionId, String title) {
        sessionRepository.findById(sessionId).ifPresent(s -> {
            s.setTitle(title);
            s.setTitleAuto(false);
            s.setUpdatedAt(LocalDateTime.now());
            sessionRepository.save(s);
        });
    }

    /** 以首个用户问题作为会话标题（仅在标题仍为自动默认时生效，避免覆盖用户自定义标题） */
    @Transactional
    public void autoTitleFromFirstMessage(String sessionId, String userMessage) {
        sessionRepository.findById(sessionId).ifPresent(s -> {
            if (!Boolean.TRUE.equals(s.getTitleAuto())) return;
            String title = deriveTitle(userMessage);
            s.setTitle(title);
            s.setTitleAuto(false);
            s.setUpdatedAt(LocalDateTime.now());
            sessionRepository.save(s);
            log.info("会话 {} 自动命名标题: {}", sessionId, title);
        });
    }

    /** 从消息内容派生标题: 去除多余空白并截断 */
    private static String deriveTitle(String text) {
        if (text == null) return DEFAULT_TITLE;
        String clean = text.replaceAll("\\s+", " ").trim();
        if (clean.isEmpty()) return DEFAULT_TITLE;
        return clean.length() > 30 ? clean.substring(0, 30) + "…" : clean;
    }

    /** 更新会话时间(供编排器调用) */
    @Transactional
    public void touch(String sessionId) {
        sessionRepository.findById(sessionId).ifPresent(s -> {
            s.setUpdatedAt(LocalDateTime.now());
            sessionRepository.save(s);
        });
    }

    // ===== 转换 =====

    static SessionDTO toDTO(SessionEntity e) {
        SessionDTO dto = new SessionDTO();
        dto.setId(e.getId());
        dto.setTitle(e.getTitle());
        dto.setTitleAuto(Boolean.TRUE.equals(e.getTitleAuto()));
        dto.setModel(e.getModel());
        dto.setSystemPrompt(e.getSystemPrompt());
        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());
        return dto;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<String> parseImages(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    static MessageDTO toMessageDTO(MessageEntity m) {
        MessageDTO dto = new MessageDTO();
        dto.setId(m.getSessionId() + "_" + m.getSeq());
        dto.setSessionId(m.getSessionId());
        dto.setRole(m.getRole());
        dto.setType(m.getType());
        dto.setContent(m.getContent());
        dto.setReasoningContent(m.getReasoningContent());
        dto.setImages(parseImages(m.getImages()));
        dto.setModel(m.getModel());
        dto.setToolsUsed(m.getToolsUsed());
        dto.setTokenUsage(m.getTokenUsage());
        dto.setSeq(m.getSeq());
        dto.setError(m.getError());
        dto.setInterrupted(m.getInterrupted());
        dto.setCreatedAt(m.getCreatedAt());
        return dto;
    }
}
