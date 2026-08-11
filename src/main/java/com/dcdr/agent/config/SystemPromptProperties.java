package com.dcdr.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * System Prompt 配置（分层 + 运行时两个正交维度）。
 * <p>
 * <b>维度一 · Base System Prompt（静态基线，可配置/可持久化）</b>
 * <p>层级（优先级从低到高，后者追加/覆盖前者）：
 * <ol>
 *   <li>L1 全局默认 {@link #defaultPrompt}：所有场景兜底</li>
 *   <li>L2 场景级 {@link #scenes}：按 chat / text-to-image / image-to-image 场景注入</li>
 *   <li>L3 会话级（{@code SessionEntity.systemPrompt}，持久化于 DB）：聊天场景用户可定制，运行时覆盖 L2/chat</li>
 * </ol>
 * 长文本建议外置到 {@code classpath:prompts/*.md}，通过 {@code *-location} 字段引用；
 * 若同时在内联字段（如 {@link Scenes#chat}）写了文本，内联优先。
 * <p>
 * <b>维度二 · Runtime 运行时约束（平台动态增强，不持久化）</b>
 * <p>与 Base 层级<b>正交</b>，不属于 L1/L2/L3 任何一层。由平台根据运行时状态动态决定注入
 * （是否开启联网搜索、是否为多轮会话、是否注入生图工具等）。
 * 每项结构为 {@code name}(注入去重键) + {@code text} / {@code text-location}(注入内容)。
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

    /**
     * 维度二 · Runtime 运行时约束（平台级动态增强，不持久化，与 Base 层级正交）。
     * 内容上作为可管理的配置资产，避免硬编码在业务代码里。
     */
    private RuntimePrompts runtime = new RuntimePrompts();

    @Data
    public static class Scenes {
        /** 聊天场景 system prompt 内联文本（与 L1 全局合并；L3 会话级非空时覆盖本项）。长文本建议改用 {@link #chatLocation} */
        private String chat;
        /** 聊天场景 system prompt 外置文件路径（classpath:prompts/chat.md）。内联 {@link #chat} 优先 */
        private String chatLocation = "classpath:prompts/chat.md";

        /** 文生图场景增强提示词（拼到用户 prompt 前） */
        private String textToImage;
        /** 图生图场景增强提示词（拼到用户 prompt 前，通常强调"保持参考图主体"） */
        private String imageToImage;
    }

    /**
     * 维度二的单条运行时约束。
     * 注入去重由 {@code name} 保证（幂等）；实际内容取 {@code text} 内联或 {@code textLocation} 文件，二者皆空则跳过注入。
     */
    @Data
    public static class PromptFragment {
        /** 注入去重键（如 "runtime:web-search"）；注入时按此键判断 history 是否已存在 */
        private String name;
        /** 注入内容内联文本（优先于 {@link #textLocation}） */
        private String text;
        /** 注入内容外置文件路径（classpath:prompts/xxx.md）；内联 {@link #text} 为空时回退到此 */
        private String textLocation;
    }

    @Data
    public static class RuntimePrompts {
        /** 生图策略：约束只生成本轮新请求的图片，不重复历史已完成的图片 */
        private PromptFragment imagePolicy = new PromptFragment();
        /** 联网搜索引导：开启联网搜索时的检索与引用规范 */
        private PromptFragment webSearch = new PromptFragment();
        /** 多轮对话规范：只回答当前提问、历史仅作参考，避免重复历史答案 */
        private PromptFragment multiTurn = new PromptFragment();
        /** RAG 知识库检索增强引导：用户选择知识库后, 指导模型基于检索片段作答与引用规范 */
        private PromptFragment ragContext = new PromptFragment();
    }
}
