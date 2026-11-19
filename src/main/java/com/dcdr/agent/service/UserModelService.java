package com.dcdr.agent.service;

import com.dcdr.agent.auth.ownership.OwnershipGuard;
import com.dcdr.agent.auth.ownership.ResourceType;
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

    /**
     * 输出上限(max_tokens)的健康下限, 低于它只告警不阻断。
     * <p>
     * 4096 的依据: 思考型模型单轮思维链常见 1~3K token, 上限小于它时预算会被思考吃光,
     * 表现为"正文一个字都没输出、finish_reason=length"。这里<b>不拦</b>是因为"就要极短回答"
     * 是合法用法, 硬性拒绝会把正常需求挡在门外 —— 只留一条日志, 出问题时能回溯到是谁配的。
     */
    private static final int HEALTHY_OUTPUT_TOKENS = 4096;

    private final UserModelConfigRepository repository;
    private final ApiKeyCipher cipher;
    private final LlmCircuitBreaker circuitBreaker;
    private final LlmMetrics metrics;
    /** V3.1 F-41: 管理入口的归属判定统一收敛到 OwnershipGuard (越权返回 404) */
    private final OwnershipGuard ownershipGuard;

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
    public UserModelConfigDTO update(String userId, String id, UserModelConfigDTO dto, boolean isAdmin) {
        ownershipGuard.check(ResourceType.USER_MODEL, id, userId, isAdmin, true);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime touched = updatedAt(dto, now);
        UserModelConfigEntity e = repository.findById(id)
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
    public void delete(String userId, String id, boolean isAdmin) {
        ownershipGuard.check(ResourceType.USER_MODEL, id, userId, isAdmin, true);
        repository.deleteById(id);
        log.info("用户 {} 删除自定义模型: {}", userId, id);
    }

    @Transactional(readOnly = true)
    public List<ModelInfo> listModels(String userId) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toModelInfo)
                .toList();
    }

    /**
     * 管理页展示用: 返回配置 DTO 列表 (apiKey 掩码)。
     * <p>
     * V3.1 F-41: 管理员返回全部用户的配置; 普通用户仅自己的。
     */
    @Transactional(readOnly = true)
    public List<UserModelConfigDTO> listConfigs(String userId, boolean isAdmin) {
        List<UserModelConfigEntity> entities = isAdmin
                ? repository.findAllByOrderByCreatedAtDesc()
                : repository.findByUserIdOrderByCreatedAtDesc(userId);
        return entities.stream()
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
        return wrap(buildProvider(loadUsable(userId, modelName)));
    }

    /**
     * 只校验可用性(存在 + 归属 + 未禁用), <b>不</b>构建 provider。
     * <p>给"保存时校验模型名"这类场景用: 那里只需要判断能不能用, 没必要解密 apiKey、
     * new 一个客户端 —— 构造有成本, 也让一次纯校验带上了副作用。
     */
    public void assertUsable(String userId, String modelName) {
        loadUsable(userId, modelName);
    }

    /** 取出"归属当前用户且已启用"的自定义模型, 不满足抛 404(与越权统一口径) */
    private UserModelConfigEntity loadUsable(String userId, String modelName) {
        String id = modelName.substring(CUSTOM_PREFIX.length());
        UserModelConfigEntity e = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(404,
                        "自定义模型不存在或不属于当前用户: " + modelName, "USER_MODEL_NOT_FOUND"));
        if (!e.isEnabled()) {
            throw new BusinessException(404, "自定义模型已禁用: " + modelName, "USER_MODEL_NOT_FOUND");
        }
        return e;
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
        // 与 toModelInfo 的对外口径对齐: 自定义模型是 OpenAI 兼容对话端点, 必须真的把 tools 发出去。
        // 此前漏设导致 supportsFunctionCalling() 恒为 false, Provider 会静默丢弃工具定义 ——
        // 模型拿不到 schema 只能按训练习惯瞎猜参数名(如把 maxResults 猜成 count), 表现为
        // "看似在调工具、实际一个都没执行"。
        config.setCapabilities(List.of("function_calling"));
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
        if (dto.getMaxTokens() != null && dto.getMaxTokens() < HEALTHY_OUTPUT_TOKENS) {
            // 思考型模型的思维链与正文共用这份预算: 过小会"想完就没了"(正文零输出 + finish_reason=length),
            // 用户只会看到一句没有数字的截断提示, 然后去改"总上下文"—— 那与此毫无关系, 改了也没用
            log.warn("自定义模型输出上限偏小: model={}, max_tokens={} (< {}), "
                            + "思考型模型可能在思考阶段就耗尽预算、正文无法输出",
                    e.getModel(), dto.getMaxTokens(), HEALTHY_OUTPUT_TOKENS);
        }
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