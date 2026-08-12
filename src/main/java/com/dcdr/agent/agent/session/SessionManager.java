package com.dcdr.agent.agent.session;

import com.dcdr.agent.agent.memory.ChatMemory;
import com.dcdr.agent.agent.memory.Message;
import com.dcdr.agent.config.AgentProperties;
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
import java.util.Map;

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

    /** 会话级 system prompt 字符上限(超过抛异常而非静默截断) */
    public static final int MAX_SYSTEM_PROMPT_LEN = 8000;

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final ChatMemory memory;
    private final AgentProperties properties;

    /** 创建新会话 */
    @Transactional
    public SessionDTO createSession(String title, String model, String systemPrompt) {
        return createSession(title, model, systemPrompt, null);
    }

    /** 创建新会话(带 userId, V2.2 数据隔离) */
    @Transactional
    public SessionDTO createSession(String title, String model, String systemPrompt, String userId) {
        String id = "sess_" + System.currentTimeMillis();
        LocalDateTime now = LocalDateTime.now();
        SessionEntity entity = new SessionEntity();
        entity.setId(id);
        entity.setTitle(title != null ? title : DEFAULT_TITLE);
        entity.setTitleAuto(true);
        entity.setModel(model != null ? model : properties.getModels().getDefaultChat());
        if (systemPrompt != null && systemPrompt.length() > MAX_SYSTEM_PROMPT_LEN) {
            throw new IllegalArgumentException(
                    "systemPrompt 过长(上限 " + MAX_SYSTEM_PROMPT_LEN + " 字符), 当前 " + systemPrompt.length());
        }
        entity.setSystemPrompt(systemPrompt);
        entity.setUserId(userId);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        sessionRepository.save(entity);

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            memory.save(id, Message.system(systemPrompt));
        }
        log.info("创建会话: {} (user={}, title={})", id, userId, entity.getTitle());
        return toDTO(entity);
    }

    /** 获取会话 */
    public SessionDTO getSession(String id) {
        return sessionRepository.findById(id).map(SessionManager::toDTO).orElse(null);
    }

    /** 获取会话(按 userId 隔离, V2.2) */
    public SessionDTO getSession(String id, String userId) {
        if (userId == null) return getSession(id);
        return sessionRepository.findByIdAndUserId(id, userId).map(SessionManager::toDTO).orElse(null);
    }

    /** 获取所有会话列表 */
    public List<SessionDTO> listSessions() {
        return sessionRepository.findAllByOrderByUpdatedAtDesc().stream()
                .map(SessionManager::toDTO)
                .toList();
    }

    /** 获取当前用户的会话列表(V2.2 数据隔离) */
    public List<SessionDTO> listSessions(String userId) {
        if (userId == null) return listSessions();
        return sessionRepository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
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
            // 累积被隐藏消息携带的工具调用, 挂到下一个可见消息(通常是最终 assistant)
            java.util.List<Map<String, Object>> pendingInvocations = new java.util.ArrayList<>();
            for (Message msg : messages) {
                if ("system".equals(msg.getRole())) continue;
                // 抓取被隐藏工具消息里的工具调用明细(assistant(tool_calls) 携带 name+arguments)
                if (isHiddenToolMessage(msg) && msg.getToolCalls() != null) {
                    pendingInvocations.addAll(msg.getToolCalls());
                }
                // 过滤对用户无意义的工具往返中间消息(模型内部决策, 非给用户看的回答)
                if (isHiddenToolMessage(msg)) continue;
                MessageDTO dto = new MessageDTO();
                dto.setId(sessionId + "_" + (++idx));
                dto.setSessionId(sessionId);
                dto.setRole(msg.getRole());
                dto.setType(msg.getType() != null ? msg.getType() : "text");
                dto.setContent(msg.getContent());
                dto.setReasoningContent(msg.getReasoningContent());
                dto.setModel(msg.getModel());
                dto.setSeq(msg.getSeq());
                dto.setError(msg.getError());
                dto.setTokenUsage(msg.getTokenUsage());
                dto.setImages(msg.getImages());
                dto.setSources(msg.getSources());
                dto.setToolCalls(msg.getToolCalls());
                dto.setToolCallId(msg.getToolCallId());
                dto.setName(msg.getName());
                // 把累积的工具调用清单挂到可见消息(回答)上, 供前端折叠展示
                if (!pendingInvocations.isEmpty()) {
                    dto.setToolInvocations(new java.util.ArrayList<>(pendingInvocations));
                    pendingInvocations.clear();
                }
                dto.setCreatedAt(LocalDateTime.now());
                dtos.add(dto);
            }
            return dtos;
        }
        // 回退: 直接查 messages 表
        List<MessageEntity> entities = messageRepository.findBySessionIdOrderBySeqAsc(sessionId);
        java.util.List<Map<String, Object>> pend = new java.util.ArrayList<>();
        List<MessageDTO> result = new java.util.ArrayList<>();
        for (MessageEntity m : entities) {
            if ("system".equals(m.getRole())) continue;
            if (isHiddenToolMessage(m) && m.getToolCallsJson() != null && !m.getToolCallsJson().isBlank()) {
                List<Map<String, Object>> tcs = parseToolCalls(m.getToolCallsJson());
                if (tcs != null) pend.addAll(tcs);
            }
            if (isHiddenToolMessage(m)) continue;
            MessageDTO dto = toMessageDTO(m);
            if (!pend.isEmpty()) {
                dto.setToolInvocations(new java.util.ArrayList<>(pend));
                pend.clear();
            }
            result.add(dto);
        }
        return result;
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

    /**
     * 更新会话级 system prompt，并保证「落库字段」与「消息历史中的 system 消息」两份数据一致。
     * <p>
     * 修复项：原先仅在创建会话时写入一次，之后无入口更新，导致修改不生效。
     * 本方法同时更新 {@code sessions.system_prompt} 字段与 {@code messages} 表中该会话首条
     * role=system 的消息内容；新值为空/空白时删除该 system 消息(不再注入)。
     *
     * @param sessionId    会话 ID
     * @param systemPrompt 新的 system prompt(可为空, 表示清除)
     * @throws IllegalArgumentException 会话不存在或超长时抛出
     */
    @Transactional
    public void updateSystemPrompt(String sessionId, String systemPrompt) {
        SessionEntity entity = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + sessionId));
        if (systemPrompt != null && systemPrompt.length() > MAX_SYSTEM_PROMPT_LEN) {
            throw new IllegalArgumentException(
                    "systemPrompt 过长(上限 " + MAX_SYSTEM_PROMPT_LEN + " 字符), 当前 " + systemPrompt.length());
        }

        String trimmed = (systemPrompt == null || systemPrompt.isBlank()) ? null : systemPrompt;
        entity.setSystemPrompt(trimmed);
        entity.setUpdatedAt(LocalDateTime.now());
        sessionRepository.save(entity);

        // 同步更新/删除消息历史中的 system 消息(保证两份数据一致)
        List<MessageEntity> sysList = messageRepository.findBySessionIdOrderBySeqAsc(sessionId).stream()
                .filter(m -> "system".equals(m.getRole()))
                .toList();
        if (trimmed == null) {
            // 清除: 删除所有 system 消息
            sysList.forEach(messageRepository::delete);
            log.info("会话 {} 已清除 system prompt", sessionId);
        } else if (!sysList.isEmpty()) {
            // 更新首条 system 消息(后续多余的 system 消息一并清理, 保持唯一)
            MessageEntity sys = sysList.get(0);
            sys.setContent(trimmed);
            messageRepository.save(sys);
            for (int i = 1; i < sysList.size(); i++) {
                messageRepository.delete(sysList.get(i));
            }
            log.info("会话 {} 更新 system prompt", sessionId);
        } else {
            // 原本没有 system 消息: 补写一条(使用最小 seq, 确保位于历史最前)
            MessageEntity sys = new MessageEntity();
            sys.setSessionId(sessionId);
            sys.setSeq(messageRepository.findTopBySessionIdOrderBySeqDesc(sessionId)
                    .map(MessageEntity::getSeq).orElse(-1) + 1);
            sys.setRole("system");
            sys.setType("text");
            sys.setContent(trimmed);
            sys.setCreatedAt(LocalDateTime.now());
            messageRepository.save(sys);
            log.info("会话 {} 新增 system prompt", sessionId);
        }
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

    /**
     * 判断内存态消息是否应对用户隐藏(工具往返中间消息)。
     * - role=tool: 工具结果原文, 用户无需直接看
     * - role=assistant 且仅有 toolCalls、无正文/推理: 模型调用工具的内部决策, 非回答
     */
    private static boolean isHiddenToolMessage(Message m) {
        if ("tool".equals(m.getRole())) return true;
        if ("assistant".equals(m.getRole())
                && m.getToolCalls() != null && !m.getToolCalls().isEmpty()
                && (m.getContent() == null || m.getContent().isBlank())
                && (m.getReasoningContent() == null || m.getReasoningContent().isBlank())) {
            return true;
        }
        return false;
    }

    /**
     * 判断持久化态消息是否应对用户隐藏(同 {@link #isHiddenToolMessage(Message)}，基于 MessageEntity)。
     */
    private static boolean isHiddenToolMessage(MessageEntity m) {
        if ("tool".equals(m.getRole())) return true;
        if ("assistant".equals(m.getRole())
                && m.getToolCallsJson() != null && !m.getToolCallsJson().isBlank()
                && (m.getContent() == null || m.getContent().isBlank())
                && (m.getReasoningContent() == null || m.getReasoningContent().isBlank())) {
            return true;
        }
        return false;
    }

    private static List<Map<String, Object>> parseSources(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
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

    static MessageDTO toMessageDTO(MessageEntity m) {
        MessageDTO dto = new MessageDTO();
        dto.setId(m.getSessionId() + "_" + m.getSeq());
        dto.setSessionId(m.getSessionId());
        dto.setRole(m.getRole());
        dto.setType(m.getType());
        dto.setContent(m.getContent());
        dto.setReasoningContent(m.getReasoningContent());
        dto.setImages(parseImages(m.getImages()));
        dto.setSources(parseSources(m.getSources()));
        dto.setToolCalls(parseToolCalls(m.getToolCallsJson()));
        dto.setToolCallId(m.getToolCallId());
        dto.setName(m.getToolName());
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
