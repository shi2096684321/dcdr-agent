package com.dcdr.agent.agent.prompt;

import com.dcdr.agent.config.SystemPromptProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 维度一（Base System Prompt）统一解析器。
 * <p>
 * 负责把「全局默认 + 场景级 + 会话级」三层 prompt 合并为最终注入文本，
 * 屏蔽各场景调用方对层级细节的感知。长文本优先取自 classpath 文件
 * （{@code scenes.chatLocation} 等），内联字段（{@code scenes.chat} 等）优先于文件。
 * <p>
 * 合并规则：
 * <ul>
 *   <li>聊天场景：L1 + L2/chat（会话级非空时由调用方覆盖 L2/chat，本方法只产出 L1+L2/chat）。
 *       两层均非空时用换行拼接，L2 在后（更具体，语义靠后）。</li>
 *   <li>文生图 / 图生图：L1 + L2/对应场景。同样换行拼接。</li>
 * </ul>
 * <p>
 * 设计：无状态、线程安全；所有方法纯查询，无副作用。
 */
@Slf4j
@Component
public class SystemPromptResolver {

    private final SystemPromptProperties properties;
    private final PromptTemplateLoader templateLoader;

    public SystemPromptResolver(SystemPromptProperties properties, PromptTemplateLoader templateLoader) {
        this.properties = properties;
        this.templateLoader = templateLoader;
    }

    /**
     * 解析聊天场景的 system prompt（L1 全局 + L2/chat 场景级）。
     * <p>
     * 注意：会话级 prompt（{@code SessionEntity.systemPrompt}）由 {@code AgentOrchestrator}
     * 在调用本方法后按"非空则覆盖 L2/chat"的规则处理，本方法不感知会话。
     *
     * @return 合并后的 system prompt；若 L1/L2 均为空则返回 null（调用方据此决定是否注入）
     */
    public String resolveChat() {
        return merge(properties.getDefaultPrompt(),
                resolveScene(properties.getScenes() != null ? properties.getScenes().getChat() : null,
                        properties.getScenes() != null ? properties.getScenes().getChatLocation() : null,
                        "classpath:prompts/chat.md"));
    }

    /**
     * 仅解析 L1 全局默认（用于会话级已有 system 时的补充注入）。
     *
     * @return L1 全局 prompt；为空返回 null
     */
    public String resolveDefaultOnly() {
        String d = properties.getDefaultPrompt();
        return (d != null && !d.isBlank()) ? d.strip() : null;
    }

    /**
     * 解析文生图场景的增强提示词（L1 全局 + L2/text-to-image）。
     *
     * @return 合并后的增强提示词；若均为空返回 null
     */
    public String resolveTextToImage() {
        return merge(properties.getDefaultPrompt(),
                resolveScene(properties.getScenes() != null ? properties.getScenes().getTextToImage() : null,
                        null, "classpath:prompts/text-to-image.md"));
    }

    /**
     * 解析图生图场景的增强提示词（L1 全局 + L2/image-to-image）。
     *
     * @return 合并后的增强提示词；若均为空返回 null
     */
    public String resolveImageToImage() {
        return merge(properties.getDefaultPrompt(),
                resolveScene(properties.getScenes() != null ? properties.getScenes().getImageToImage() : null,
                        null, "classpath:prompts/image-to-image.md"));
    }

    /**
     * 解析单条场景级 prompt：内联文本优先，否则从 {@code location} 文件读取；文件缺省回退到内置默认位置。
     */
    private String resolveScene(String inline, String location, String defaultLocation) {
        if (inline != null && !inline.isBlank()) {
            return inline;
        }
        String text = templateLoader.load(location != null ? location : defaultLocation);
        return text != null ? text.strip() : null;
    }

    /**
     * 把场景级 system prompt 作为前置增强拼到用户 prompt 前。
     * <p>
     * 用于文生图 / 图生图：图像模型协议不支持 system 消息，所以把 system prompt
     * 作为一个"质量/风格约束前缀"拼到 {@code ImageRequest.prompt} 前面。
     * <p>
     * 示例：system="高质量，细节丰富" userPrompt="一只猫" → "高质量，细节丰富\n一只猫"
     *
     * @param sceneSystemPrompt 场景级 system prompt（可为 null，为 null 时原样返回用户 prompt）
     * @param userPrompt        用户原始 prompt
     * @return 增强后的 prompt；若 sceneSystemPrompt 为空则原样返回 userPrompt
     */
    public String enhanceImagePrompt(String sceneSystemPrompt, String userPrompt) {
        if (sceneSystemPrompt == null || sceneSystemPrompt.isBlank()) {
            return userPrompt;
        }
        if (userPrompt == null || userPrompt.isBlank()) {
            return sceneSystemPrompt;
        }
        return sceneSystemPrompt.strip() + "\n" + userPrompt;
    }

    /**
     * 合并两层 prompt：均非空时换行拼接（L1 在前、L2 在后）；任一为空取另一；均空返回 null。
     */
    private String merge(String l1, String l2) {
        boolean hasL1 = l1 != null && !l1.isBlank();
        boolean hasL2 = l2 != null && !l2.isBlank();
        if (hasL1 && hasL2) {
            return l1.strip() + "\n" + l2.strip();
        }
        if (hasL1) return l1.strip();
        if (hasL2) return l2.strip();
        return null;
    }
}
