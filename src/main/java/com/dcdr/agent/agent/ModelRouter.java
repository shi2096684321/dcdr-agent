package com.dcdr.agent.agent;

import com.dcdr.agent.common.constant.ModelType;
import com.dcdr.agent.agent.memory.Message;
import com.dcdr.agent.dto.ChatRequest;
import com.dcdr.agent.dto.ModelInfo;
import com.dcdr.agent.model.provider.ChatModelProvider;
import com.dcdr.agent.model.registry.ModelRegistry;
import com.dcdr.agent.service.UserModelService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 模型路由器 - 根据消息内容决定使用哪个模型
 */
@Slf4j
@Component
public class ModelRouter {

    private final ModelRegistry registry;
    private final UserModelService userModelService;

    public ModelRouter(ModelRegistry registry, UserModelService userModelService) {
        this.registry = registry;
        this.userModelService = userModelService;
    }

    /** 无用户上下文版本: 无 userId 时自定义模型不可解析, 走平台模型 (兼容旧调用) */
    public ChatModelProvider route(ChatRequest request, List<Message> history) {
        return route(request, history, null);
    }

    /**
     * 路由决策 (V3.1 支持用户自定义模型):
     * 1. 用户显式指定模型 → 若带 {@code custom:} 前缀, 走用户自定义模型 (按 userId 解析);
     *    否则走平台注册模型
     * 2. 消息含图片 → 需要 Vision 模型
     * 3. 默认使用配置的默认对话模型
     */
    public ChatModelProvider route(ChatRequest request, List<Message> history, String userId) {
        // 1. 用户显式指定
        if (request.getModel() != null && !request.getModel().isBlank()) {
            log.debug("路由: 用户指定模型 {}", request.getModel());
            if (userModelService.isCustomModelName(request.getModel())) {
                if (userId == null) {
                    throw new com.dcdr.agent.common.exception.BusinessException(
                            401, "未登录用户无法使用自定义模型", "UNAUTHORIZED");
                }
                return userModelService.resolveProvider(userId, request.getModel());
            }
            return registry.getChatProvider(request.getModel());
        }

        // 2. 包含图片 → 需要 Vision 模型
        if (request.getImages() != null && !request.getImages().isEmpty()) {
            log.debug("路由: 消息含图片, 查找Vision模型");
            for (ChatModelProvider p : getAllChatProviders()) {
                if (p.supportsVision()) {
                    return p;
                }
            }
            log.warn("没有可用的Vision模型, 使用默认模型");
        }

        // 3. 默认模型
        return registry.getDefaultChatProvider();
    }

    private List<ChatModelProvider> getAllChatProviders() {
        // 从注册中心获取所有对话模型
        List<ChatModelProvider> list = new java.util.ArrayList<>();
        for (ModelInfo info : registry.listModels()) {
            if (info.getType() == ModelType.CHAT) {
                try {
                    list.add(registry.getChatProvider(info.getName()));
                } catch (Exception ignored) {}
            }
        }
        return list;
    }
}
