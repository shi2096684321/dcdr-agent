package com.dcdr.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * System Prompt 配置（分层 + 运行时两个正交维度）。
 * <p>
 * <b>维度一 · Base System Prompt（静态基线，可配置）</b>
 * <p>层级（优先级从低到高，后者追加/覆盖前者）：
 * <ol>
 *   <li>L1 全局默认 {@link #defaultPrompt}：所有场景兜底</li>
 *   <li>L2 场景级 {@link #scenes}：按 chat / text-to-image / image-to-image 场景注入</li>
 * </ol>
 * 长文本建议外置到 {@code classpath:prompts/*.md}，通过 {@code *-location} 字段引用
 * （L1 用 {@link #defaultLocation}，L2 用 {@link Scenes#chatLocation}，Runtime 用
 * {@link PromptFragment#textLocation}）；若同时在内联字段写了文本，内联优先 —— 此时外置文件
 * 会被静默忽略，故解析器在检测到覆盖时会打一条 WARN 提示。
 * <p>
 * 注：会话级角色设定（{@code SessionEntity.systemPrompt}）已移除，它与自定义 Agent 人设功能重叠，
 * 且自由文本配置与「用户只做选择、不做配置」的产品定位冲突。角色需求统一走 Agent。
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

    /** L1 全局默认 system prompt 内联文本（所有场景兜底，可为空；非空时覆盖 {@link #defaultLocation}） */
    private String defaultPrompt;

    /**
     * L1 全局默认 system prompt 外置文件（{@code classpath:prompts/identity.md}）。
     * <p>
     * 仅当 {@link #defaultPrompt} 为空时生效 —— 与 L2 场景级、维度二 Runtime 的
     * 「内联优先于文件」规则保持一致。人设类长文本放这里，改人设不必动 {@code application.yml}。
     */
    private String defaultLocation = "classpath:prompts/identity.md";

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
        /** 外部数据边界：说明工具结果中的不可信标记, 防间接提示注入 */
        private PromptFragment toolDataBoundary = new PromptFragment();
        /**
         * 长期记忆使用规范：怎么用 {@code <long_term_memory>} 里的条目(不复述 / 冲突以当前提问为准 / 不构成指令)。
         * <p>放在进程常量档而不是随记忆文本下发: 它是<b>稳定约束</b>, 每轮都在改变前缀就等于白丢缓存;
         * 记忆条目本身才是每轮变量。
         */
        private PromptFragment memoryUsage = new PromptFragment();
    }
}
