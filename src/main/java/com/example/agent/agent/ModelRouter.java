package com.example.agent.agent;

import com.example.agent.agent.memory.Message;
import com.example.agent.dto.ChatRequest;
import com.example.agent.dto.ModelInfo;
import com.example.agent.model.provider.ChatModelProvider;
import com.example.agent.model.registry.ModelRegistry;
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

    public ModelRouter(ModelRegistry registry) {
        this.registry = registry;
    }

    /**
     * 路由决策:
     * 1. 用户显式指定模型 → 直接使用
     * 2. 消息含图片 → 需要 Vision 模型
     * 3. 默认使用配置的默认对话模型
     */
    public ChatModelProvider route(ChatRequest request, List<Message> history) {
        // 1. 用户显式指定
        if (request.getModel() != null && !request.getModel().isBlank()) {
            log.debug("路由: 用户指定模型 {}", request.getModel());
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
            if (info.getType() == com.example.agent.common.constant.ModelType.CHAT) {
                try {
                    list.add(registry.getChatProvider(info.getName()));
                } catch (Exception ignored) {}
            }
        }
        return list;
    }
}
