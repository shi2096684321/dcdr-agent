package com.dcdr.agent.agent.prompt;

import com.dcdr.agent.config.SystemPromptProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 维度一（Base System Prompt）统一解析器。
 * <p>
 * 负责把「全局默认 + 场景级」两层 prompt 合并为最终注入文本，屏蔽各场景调用方对层级细节的感知。
 * 长文本优先取自 classpath 文件（{@code defaultLocation} / {@code scenes.chatLocation} 等），
 * 内联字段优先于文件。
 * <p>
 * 合并规则：
 * <ul>
 *   <li>聊天场景：L1 + L2/chat。两层均非空时用空行分隔拼接，L2 在后（更具体，语义靠后）。</li>
 *   <li>文生图 / 图生图：<b>只取</b> L2 对应场景，不拼 L1 —— L1 是对话助手的人设
 *       （名字 / 自称规则 / 风格），对图像模型是纯干扰：它不支持 system 消息，
 *       L1 会被当作用户画面描述的一部分（"你叫 dcdr，不要说自我介绍"会被画进图里）。</li>
 * </ul>
 * <p>
 * 设计：所有解析方法纯查询、无副作用、线程安全。唯一的实例状态是
 * {@code warnedInlineOverride}，仅用于把「内联覆盖外置文件」这条告警去重成一次
 * （否则每轮对话刷一条 WARN），不参与任何解析结果。
 */
@Slf4j
@Component
public class SystemPromptResolver {

    private final SystemPromptProperties properties;
    private final PromptTemplateLoader templateLoader;

    /** 已告警过的「内联覆盖文件」location，避免每轮对话重复刷日志 */
    private final Set<String> warnedInlineOverride = ConcurrentHashMap.newKeySet();

    public SystemPromptResolver(SystemPromptProperties properties, PromptTemplateLoader templateLoader) {
        this.properties = properties;
        this.templateLoader = templateLoader;
    }

    /**
     * 解析聊天场景的 system prompt（L1 全局 + L2/chat 场景级）。
     *
     * @return 合并后的 system prompt；若 L1/L2 均为空则返回 null（调用方据此决定是否注入）
     */
    public String resolveChat() {
        return merge(resolveL1(),
                resolveScene(properties.getScenes() != null ? properties.getScenes().getChat() : null,
                        properties.getScenes() != null ? properties.getScenes().getChatLocation() : null,
                        "classpath:prompts/chat.md"));
    }

    /**
     * 仅解析 L1 全局默认（用于只注入人设、不叠加场景规范的场合）。
     *
     * @return L1 全局 prompt；为空返回 null
     */
    public String resolveDefaultOnly() {
        return resolveL1();
    }

    /**
     * 解析文生图场景的增强提示词。
     * <p>
     * <b>不拼 L1</b>：L1 是对话人设（名字 / 自称规则 / 风格），而图像模型不支持 system 消息，
     * {@code CreativeEngine} / {@code ChatImageService} 会把它当作用户画面描述的前缀拼进去 ——
     * "你叫 dcdr，不要说自我介绍"这类句子会被当成画面内容。
     *
     * @return 合并后的增强提示词；若未配置返回 null
     */
    public String resolveTextToImage() {
        return resolveScene(properties.getScenes() != null ? properties.getScenes().getTextToImage() : null,
                null, "classpath:prompts/text-to-image.md");
    }

    /**
     * 解析图生图场景的增强提示词（L2/image-to-image）。
     * <p>
     * <b>不拼 L1</b>：同 {@link #resolveTextToImage()}。
     *
     * @return 合并后的增强提示词；若未配置返回 null
     */
    public String resolveImageToImage() {
        return resolveScene(properties.getScenes() != null ? properties.getScenes().getImageToImage() : null,
                null, "classpath:prompts/image-to-image.md");
    }

    /**
     * 解析 L1 全局默认人设：内联 {@code default-prompt} 优先，否则读 {@code default-location} 文件。
     *
     * @return L1 文本；内联与文件均不可用返回 null
     */
    private String resolveL1() {
        return resolveScene(properties.getDefaultPrompt(),
                properties.getDefaultLocation(),
                "classpath:prompts/identity.md");
    }

    /**
     * 解析单条场景级 prompt：内联文本优先，否则从 {@code location} 文件读取；文件缺省回退到内置默认位置。
     * <p>
     * 内联优先是<b>静默</b>的（只要内联非空就完全不读文件），"改了 md 却不生效"这类问题极难排查，
     * 故此处显式告警指明「哪个文件被覆盖了」；按 location 去重，只打一次，避免每轮对话刷屏。
     */
    private String resolveScene(String inline, String location, String defaultLocation) {
        if (inline != null && !inline.isBlank()) {
            String overridden = location != null && !location.isBlank() ? location : defaultLocation;
            if (overridden != null && !overridden.isBlank() && warnedInlineOverride.add(overridden)) {
                log.warn("[SystemPrompt] 已配置内联文本, 外置文件不会生效: {} —— 如需改文件请先清空配置里的内联值",
                        overridden);
            }
            return inline.strip();
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
     * 合并两层 prompt：均非空时用空行分隔拼接（L1 在前、L2 在后）；任一为空取另一；均空返回 null。
     * <p>
     * 用空行而非单个换行：L1（身份/硬约束/默认偏好）与 L2（本轮任务与输出规范）是两段性质不同的指令，
     * 单换行会让末尾一条规则和下一段标题粘成一行，模型容易读成同一段规则的延续。
     */
    private String merge(String l1, String l2) {
        boolean hasL1 = l1 != null && !l1.isBlank();
        boolean hasL2 = l2 != null && !l2.isBlank();
        if (hasL1 && hasL2) {
            return l1.strip() + "\n\n" + l2.strip();
        }
        if (hasL1) return l1.strip();
        if (hasL2) return l2.strip();
        return null;
    }
}
