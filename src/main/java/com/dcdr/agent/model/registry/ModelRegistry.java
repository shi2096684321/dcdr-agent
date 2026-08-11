package com.dcdr.agent.model.registry;

import com.dcdr.agent.common.constant.ModelType;
import com.dcdr.agent.common.constant.UIMode;
import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.dto.ModelInfo;
import com.dcdr.agent.model.provider.AudioModelProvider;
import com.dcdr.agent.model.provider.ChatModelProvider;
import com.dcdr.agent.model.provider.ImageModelProvider;
import com.dcdr.agent.model.provider.VideoModelProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型注册中心 - 管理所有 Provider
 * <p>
 * 遵循开闭原则: 新增模型只需实现接口 + 注册, 不改此类。
 */
@Slf4j
@Component
public class ModelRegistry {

    private final Map<String, ChatModelProvider> chatProviders = new ConcurrentHashMap<>();
    private final Map<String, ImageModelProvider> imageProviders = new ConcurrentHashMap<>();
    private final Map<String, VideoModelProvider> videoProviders = new ConcurrentHashMap<>();
    private final Map<String, AudioModelProvider> audioProviders = new ConcurrentHashMap<>();

    private String defaultChatProvider;

    // ===== 注册方法 =====

    public void registerChatProvider(ChatModelProvider provider) {
        chatProviders.put(provider.getName(), provider);
        log.info("注册对话模型: {} ({})", provider.getName(), provider.getLabel());
    }

    public void registerImageProvider(ImageModelProvider provider) {
        imageProviders.put(provider.getName(), provider);
        log.info("注册图片模型: {} ({})", provider.getName(), provider.getLabel());
    }

    public void registerVideoProvider(VideoModelProvider provider) {
        videoProviders.put(provider.getName(), provider);
        log.info("注册视频模型: {} ({})", provider.getName(), provider.getLabel());
    }

    public void registerAudioProvider(AudioModelProvider provider) {
        audioProviders.put(provider.getName(), provider);
        log.info("注册语音模型: {} ({})", provider.getName(), provider.getLabel());
    }

    public void setDefaultChatProvider(String name) {
        this.defaultChatProvider = name;
    }

    // ===== 获取方法 =====

    public ChatModelProvider getChatProvider(String name) {
        ChatModelProvider p = chatProviders.get(name);
        if (p == null) {
            throw new BusinessException(404, "对话模型不存在: " + name, "MODEL_NOT_FOUND");
        }
        return p;
    }

    public ImageModelProvider getImageProvider(String name) {
        ImageModelProvider p = imageProviders.get(name);
        if (p == null) {
            throw new BusinessException(404, "图片模型不存在: " + name, "MODEL_NOT_FOUND");
        }
        return p;
    }

    /** 默认图片模型: 返回已配置的第一个图片 Provider(复用创作模式已配模型) */
    public ImageModelProvider getDefaultImageProvider() {
        return imageProviders.values().stream().findFirst().orElse(null);
    }

    public VideoModelProvider getVideoProvider(String name) {
        VideoModelProvider p = videoProviders.get(name);
        if (p == null) {
            throw new BusinessException(404, "视频模型不存在: " + name, "MODEL_NOT_FOUND");
        }
        return p;
    }

    public AudioModelProvider getAudioProvider(String name) {
        AudioModelProvider p = audioProviders.get(name);
        if (p == null) {
            throw new BusinessException(404, "语音模型不存在: " + name, "MODEL_NOT_FOUND");
        }
        return p;
    }

    /** 默认语音模型: 返回已配置的第一个语音 Provider */
    public AudioModelProvider getDefaultAudioProvider() {
        return audioProviders.values().stream().findFirst().orElse(null);
    }

    public ChatModelProvider getDefaultChatProvider() {
        if (defaultChatProvider != null && chatProviders.containsKey(defaultChatProvider)) {
            return chatProviders.get(defaultChatProvider);
        }
        return chatProviders.values().stream().findFirst()
                .orElseThrow(() -> new BusinessException(500, "没有可用的对话模型"));
    }

    /** 获取任一支持视觉的对话模型(用于图片识别降级路由) */
    public ChatModelProvider getFirstVisionChatProvider() {
        return chatProviders.values().stream()
                .filter(ChatModelProvider::supportsVision)
                .findFirst().orElse(null);
    }

    /**
     * 按名称获取任意类型的 Provider
     */
    public Object getProvider(String name) {
        if (chatProviders.containsKey(name)) return chatProviders.get(name);
        if (imageProviders.containsKey(name)) return imageProviders.get(name);
        if (videoProviders.containsKey(name)) return videoProviders.get(name);
        if (audioProviders.containsKey(name)) return audioProviders.get(name);
        throw new BusinessException(404, "模型不存在: " + name, "MODEL_NOT_FOUND");
    }

    /**
     * 列出所有可用模型(按类型分组, 返回给前端)
     */
    public List<ModelInfo> listModels() {
        List<ModelInfo> list = new ArrayList<>();
        chatProviders.values().forEach(p -> list.add(toModelInfo(p.getName(), p.getType(), p.getProvider(), p.getLabel(), null, isDefaultChat(p.getName()))));
        imageProviders.values().forEach(p -> list.add(toModelInfo(p.getName(), p.getType(), p.getProvider(), p.getLabel(), null, false)));
        videoProviders.values().forEach(p -> list.add(toModelInfo(p.getName(), p.getType(), p.getProvider(), p.getLabel(), p.capabilities(), false)));
        audioProviders.values().forEach(p -> list.add(toModelInfo(p.getName(), p.getType(), p.getProvider(), p.getLabel(), null, false)));
        return list;
    }

    private boolean isDefaultChat(String name) {
        return defaultChatProvider != null && defaultChatProvider.equals(name);
    }

    private ModelInfo toModelInfo(String name, ModelType type, String provider, String label, List<String> capabilities, boolean isDefault) {
        ModelInfo info = new ModelInfo();
        info.setName(name);
        info.setType(type);
        info.setProvider(provider);
        info.setLabel(label);
        info.setCapabilities(capabilities != null ? capabilities : Collections.emptyList());
        info.setEnabled(true);
        info.setUiMode(UIMode.fromModelType(type).getValue());
        info.setDefault(isDefault);
        return info;
    }

    /** 获取模型类型 */
    public ModelType getModelType(String name) {
        if (chatProviders.containsKey(name)) return ModelType.CHAT;
        if (imageProviders.containsKey(name)) return ModelType.IMAGE;
        if (videoProviders.containsKey(name)) return ModelType.VIDEO;
        if (audioProviders.containsKey(name)) return ModelType.AUDIO;
        throw new BusinessException(404, "模型不存在: " + name, "MODEL_NOT_FOUND");
    }
}
