package com.dcdr.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 分层 System Prompt 配置。
 * <p>
 * 层级（优先级从低到高，后者追加/覆盖前者）：
 * <ol>
 *   <li>L1 全局默认 {@link #defaultPrompt}：所有场景兜底</li>
 *   <li>L2 场景级 {@link #scenes}：按 chat / text-to-image / image-to-image 场景注入</li>
 *   <li>L3 会话级（{@code SessionEntity.systemPrompt}，已有）：聊天场景保留，运行时覆盖 L2/chat</li>
 * </ol>
 * <p>
 * 聊天场景：解析后作为一条 {@code role=system} 消息插入 history 头部（OpenAI 兼容协议）。
 * 文生图 / 图生图场景：模型协议不支持 system 消息，解析后作为<b>用户提示词的前置增强</b>拼到 {@code ImageRequest.prompt} 前。
 */
@Data
@ConfigurationProperties(prefix = "agent.system-prompt")
public class SystemPromptProperties {

    /** L1 全局默认 system prompt（所有场景兜底，可为空） */
    private String defaultPrompt;

    /** L2 场景级配置 */
    private Scenes scenes = new Scenes();

    @Data
    public static class Scenes {
        /** 聊天场景 system prompt（与 L1 全局合并；会话级非空时覆盖本项） */
        private String chat;

        /** 文生图场景增强提示词（拼到用户 prompt 前） */
        private String textToImage;

        /** 图生图场景增强提示词（拼到用户 prompt 前，通常强调"保持参考图主体"） */
        private String imageToImage;
    }
}
