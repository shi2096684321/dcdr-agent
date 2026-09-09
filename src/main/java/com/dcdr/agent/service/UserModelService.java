package com.dcdr.agent.service;

import com.dcdr.agent.common.constant.ModelType;
import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.common.util.ApiKeyCipher;
import com.dcdr.agent.config.AgentProperties;
import com.dcdr.agent.config.LlmMetrics;
import com.dcdr.agent.dto.ModelInfo;
import com.dcdr.agent.dto.UserModelConfigDTO;
import com.dcdr.agent.entity.UserModelConfigEntity;
import com.dcdr.agent.model.circuitbreaker.CircuitBreakerChatProvider;
import com.dcdr.agent.model.circuitbreaker.LlmCircuitBreaker;
import com.dcdr.agent.model.langchain4j.LangChain4jChatProvider;
import com.dcdr.agent.model.provider.ChatModelProvider;
import com.dcdr.agent.repository.UserModelConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * V3.1 用户自定义模型服务: CRUD + 按需动态构建 ChatModelProvider。
 * <p>
 * 用户自定义模型走系统内置的 LangChain4j OpenAI 兼容内核 (base-url/api-key/model 三要素),
 * 覆盖 DeepSeek/通义/智谱/Kimi/中转站等一切 OpenAI 兼容端点, 无需逐个厂商适配。
 * 与平台内置模型(yaml/env, 启动时注册)互补, 用户模型按 userId 隔离、动态构建。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserModelService {

    /** 用户自定义对话模型的命名前缀, 路由层据此区分平台模型与用户模型 */
    public static final String CUSTOM_PREFIX = "custom:";

    private final UserModelConfigRepository repository;
    private final ApiKeyCipher cipher;
    private final LlmCircuitBreaker circuitBreaker;
    private final LlmMetrics metrics;

    // ===== CRUD =====

    @Transactional
    public UserModelConfigDTO create(String userId, UserModelConfigDTO dto) {
        validateFields(dto);
        UserModelConfigEntity e = new UserModelConfigEntity();
        e.setId(UUID.randomUUID().toString());
        e.setUserId(userId);
        applyDto(e, dto, true);
        e.setCreatedAt(LocalDateTime.now());
        e.setUpdatedAt(LocalDateTime.now());
        repository.save(e);
        log.info("用户 {} 新增自定义模型: {} ({})", userId, e.getName(), e.getModel());
        return toDto(e);
    }

    @Transactional
    public UserModelConfigDTO update(String userId, String id, UserModelConfigDTO dto) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime touched = updatedAt(dto, now);
        UserModelConfigEntity e = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(404, "自定义模型不存在: " + id, "USER_MODEL_NOT_FOUND"));
        String prevName = e.getName();
        // apiKey 为空视为保留原值(不支持置空, 湖: 变更 key 需重新提交新 key)
        applyDto(e, dto, dto.getApiKey() != null && !dto.getApiKey().isBlank());
        e.setUpdatedAt(touched);
        repository.save(e);
        log.info("用户 {} 更新自定义模型: {} -> {} ({})", userId, prevName, e.getName(), e.getModel());
        return toDto(e);
    }

    @Transactional
    public void delete(String userId, String id) {
        if (repository.findByIdAndUserId(id, userId).isEmpty()) {
            throw new BusinessException(404, "自定义模型不存在: " + id, "USER_MODEL_NOT_FOUND");
        }
        repository.deleteByIdAndUserId(id, userId);
        log.info("用户 {} 删除自定义模型: {}", userId, id);
    }

    @Transactional(readOnly = true)
    public List<ModelInfo> listModels(String userId) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toModelInfo)
                .toList();
    }

    /** 管理页展示用: 返回配置 DTO 列表 (apiKey 掩码) */
    @Transactional(readOnly = true)
    public List<UserModelConfigDTO> listConfigs(String userId) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toDto)
                .toList();
    }

    // ===== 路由支持 =====

    /** 模型名是否属于用户自定义模型 (格式: custom:<id>) */
    public boolean isCustomModelName(String modelName) {
        return modelName != null && modelName.startsWith(CUSTOM_PREFIX);
    }

    /**
     * 按模型名(带 custom: 前缀)解析用户自定义模型, 归属校验 + 动态构建 provider (含熔断装饰)。
     * 找不到或未启用抛 404, 由上层转为不可用。
     */
    public ChatModelProvider resolveProvider(String userId, String modelName) {
        String id = modelName.substring(CUSTOM_PREFIX.length());
        UserModelConfigEntity e = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(404,
                        "自定义模型不存在或不属于当前用户: " + modelName, "USER_MODEL_NOT_FOUND"));
        if (!e.isEnabled()) {
            throw new BusinessException(404, "自定义模型已禁用: " + modelName, "USER_MODEL_NOT_FOUND");
        }
        return wrap(buildProvider(e));
    }

    /** 构建底层 ChatModelProvider (langchain4j 内核) */
    private ChatModelProvider buildProvider(UserModelConfigEntity e) {
        AgentProperties.ModelConfig config = new AgentProperties.ModelConfig();
        config.setName(CUSTOM_PREFIX + e.getId());
        config.setProvider("custom");
        config.setEngine("langchain4j");
        config.setApiKey(cipher.decrypt(e.getApiKeyEncrypted()));
        config.setBaseUrl(e.getBaseUrl());
        config.setModel(e.getModel());
        config.setTemperature(e.getTemperature());
        config.setMaxTokens(e.getMaxTokens());
        return new LangChain4jChatProvider(config);
    }

    /** 套熔断/打点装饰器, 与平台内置模型一致 (F-40.4 / F-40.2) */
    private ChatModelProvider wrap(ChatModelProvider provider) {
        return new CircuitBreakerChatProvider(provider, circuitBreaker, metrics);
    }

    // ===== 字段映射 =====

    private void applyDto(UserModelConfigEntity e, UserModelConfigDTO dto, boolean applyApiKey) {
        if (dto.getName() == null || dto.getName().isBlank()) {
            throw new BusinessException(400, "展示名称不能为空", "BAD_REQUEST");
        }
        if (dto.getBaseUrl() == null || dto.getBaseUrl().isBlank()) {
            throw new BusinessException(400, "base-url 不能为空", "BAD_REQUEST");
        }
        if (dto.getModel() == null || dto.getModel().isBlank()) {
            throw new BusinessException(400, "model 不能为空", "BAD_REQUEST");
        }
        e.setName(dto.getName().trim());
        e.setProvider(dto.getProvider() != null && !dto.getProvider().isBlank() ? dto.getProvider() : "openai-compatible");
        e.setBaseUrl(dto.getBaseUrl().trim());
        e.setModel(dto.getModel().trim());
        e.setTemperature(dto.getTemperature());
        e.setMaxTokens(dto.getMaxTokens());
        e.setEnabled(dto.isEnabled());
        if (applyApiKey) {
            e.setApiKeyEncrypted(cipher.encrypt(dto.getApiKey()));
        }
    }

    private static void validateFields(UserModelConfigDTO dto) {
        if (dto.getApiKey() == null || dto.getApiKey().isBlank()) {
            throw new BusinessException(400, "apiKey 不能为空", "BAD_REQUEST");
        }
        if (dto.getBaseUrl() == null || dto.getBaseUrl().isBlank()) {
            throw new BusinessException(400, "base-url 不能为空", "BAD_REQUEST");
        }
        if (dto.getModel() == null || dto.getModel().isBlank()) {
            throw new BusinessException(400, "model 不能为空", "BAD_REQUEST");
        }
    }

    private static LocalDateTime updatedAt(UserModelConfigDTO dto, LocalDateTime fallback) {
        return dto.getUpdatedAt() != null ? dto.getUpdatedAt() : fallback;
    }

    /** 转模型列表项 (供前端下拉选择用户自定义模型) */
    public ModelInfo toModelInfo(UserModelConfigEntity e) {
        ModelInfo info = new ModelInfo();
        info.setName(CUSTOM_PREFIX + e.getId());
        info.setType(ModelType.CHAT);
        info.setProvider("custom");
        info.setModel(e.getModel());
        info.setLabel(e.getName() != null ? e.getName() : e.getModel());
        info.setCapabilities(List.of("function_calling"));
        info.setEnabled(e.isEnabled());
        info.setUiMode("chat");
        info.setDefault(false);
        return info;
    }

    /** 转展示 DTO (apiKey 恒置 null, 不回显) */
    public UserModelConfigDTO toDto(UserModelConfigEntity e) {
        UserModelConfigDTO dto = new UserModelConfigDTO();
        dto.setId(e.getId());
        dto.setName(e.getName());
        dto.setProvider(e.getProvider());
        dto.setBaseUrl(e.getBaseUrl());
        dto.setModel(e.getModel());
        dto.setApiKey(null);
        dto.setHasApiKey(e.getApiKeyEncrypted() != null && !e.getApiKeyEncrypted().isBlank());
        dto.setTemperature(e.getTemperature());
        dto.setMaxTokens(e.getMaxTokens());
        dto.setEnabled(e.isEnabled());
        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());
        return dto;
    }
}