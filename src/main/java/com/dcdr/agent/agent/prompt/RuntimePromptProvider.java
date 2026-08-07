package com.dcdr.agent.agent.prompt;

import com.dcdr.agent.config.SystemPromptProperties;
import com.dcdr.agent.config.SystemPromptProperties.PromptFragment;
import com.dcdr.agent.config.SystemPromptProperties.RuntimePrompts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 平台级运行时约束 Prompt 的集中提供方（统一渲染层）。
 * <p>
 * 职责：把分散在 {@code AgentOrchestrator} 里的运行时约束收口到配置
 * （{@code agent.system-prompt.runtime}），本类作为<strong>唯一来源</strong>对外提供。
 * 每个约束是一个 {@link PromptFragment}（name=去重键，text=注入内容）。
 * <p>
 * 内容解析优先级（不降级为「无约束」）：
 * <ol>
 *   <li>配置内联 {@code text}（yml 直接写）；</li>
 *   <li>配置外置文件 {@code text-location}（classpath:prompts/xxx.md，长文本推荐）；</li>
 *   <li>内置默认值（硬编码兜底，保证基础约束始终生效）。</li>
 * </ol>
 * 无状态、线程安全。
 */
@Slf4j
@Component
public class RuntimePromptProvider {

    private final RuntimePrompts runtime;
    private final PromptTemplateLoader templateLoader;

    public RuntimePromptProvider(SystemPromptProperties properties, PromptTemplateLoader templateLoader) {
        this.runtime = properties.getRuntime() != null ? properties.getRuntime() : new RuntimePrompts();
        this.templateLoader = templateLoader;
    }

    /** @return 生图策略约束（name + text）；text 为空返回 null */
    public PromptFragment imagePolicy() {
        return resolve(runtime.getImagePolicy(),
                "runtime:image-policy",
                "classpath:prompts/runtime-image-policy.md",
                "文生图策略：只生成用户本轮明确新请求的图片。历史对话中已生成过的图片视为已完成，不要重复生成；若用户本轮要求对历史图片进行修改，应明确说明修改意图后再生成。");
    }

    /** @return 联网搜索引导约束（name + text）；text 为空返回 null */
    public PromptFragment webSearch() {
        return resolve(runtime.getWebSearch(),
                "runtime:web-search",
                "classpath:prompts/runtime-web-search.md",
                "用户已开启「联网搜索」。对于时效性信息(如最新新闻、实时数据、股价/天气)、需要引用外部来源、或你的知识可能过时的提问，请优先调用 web_search 工具检索，并基于检索结果作答、标注信息来源。\n"
                        + "引用规范：在正文中事实后紧跟 [[n]] 标记(n 为检索结果末尾资料序号)，同一事实多个来源可并列 [[n]][[m]]。\n"
                        + "结尾用「### 参考来源」列出所有 [[n]] 对应的标题与链接。优先保证信息准确与可读性，不要堆砌来源清单原文。\n\n"
                        + "【工具使用规范】：\n"
                        + "1. 按需选择搜索类型：调用 web_search 时，如果查询意图是时事、新闻、热点，请设置 topic: \"news\"；如果是技术问题、知识科普、历史事件等，请保持 topic: \"general\"(默认)。\n"
                        + "2. 智能过滤低效源：如果查询目的是获取文字解答，优先选择官方文档、博客、技术社区等文字型来源，避免返回纯视频/电商页面。\n"
                        + "3. 拒绝冗余调用：绝对不要在同一轮对话中使用完全相同的参数重复调用同一个工具。如果需要搜索多个方面，请使用不同的 query 参数。");
    }

    /** @return 多轮对话规范约束（name + text）；text 为空返回 null */
    public PromptFragment multiTurn() {
        return resolve(runtime.getMultiTurn(),
                "runtime:multi-turn",
                "classpath:prompts/runtime-multi-turn.md",
                "【多轮对话规范】：本会话包含多轮对话。请严格遵守以下要求：\n"
                        + "1. 你只需针对用户「当前最后一条」提问作答，不要复述或重复历史对话中已经给出过的回答。\n"
                        + "2. 历史消息(之前的用户提问与你的回答)仅作为背景上下文参考，帮助你理解当前问题的语境，绝不作为本轮需要重新输出的内容。\n"
                        + "3. 如果用户提出了与历史相关的新问题或追问，请基于上下文补充新信息，而不是把旧回答原样再来一遍。\n"
                        + "4. 如果用户的问题与历史无关，请直接回答新问题，忽略历史内容。\n"
                        + "5. 不要使用「如上所述」「如我之前提到的」这类暗示重复输出整段历史的话术；如需引用，只引用最小必要的片段。");
    }

    /**
     * @return 当前真实时间约束（name + text）；每轮调用动态生成，保证时间是「当下」。
     * 内容直接基于本机当前时间渲染（东八区 + 中文星期），不依赖模型是否调用 time 工具，
     * 从根上杜绝模型用训练知识臆造日期导致「差几个月」的问题。
     */
    public PromptFragment currentTime() {
        java.time.LocalDateTime now = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        java.time.format.DateTimeFormatter fmt =
                java.time.format.DateTimeFormatter.ofPattern("yyyy年MM月dd日 EEEE HH:mm:ss", java.util.Locale.CHINA);
        String text = "当前真实日期时间: " + now.format(fmt) + " (北京时间, Asia/Shanghai)。"
                + "回答任何涉及「今天/现在/当前日期/几号/星期几」的问题时，必须以这条时间为准，不要使用你的训练知识臆造日期。";
        PromptFragment f = new PromptFragment();
        f.setName("runtime:current-time");
        f.setText(text);
        return f;
    }

    /**
     * 解析单条约束：内联 text > 文件 text-location > 内置默认；name 未配置时用默认去重键。
     * 三者皆空返回 null（视为不注入）。
     */
    private PromptFragment resolve(PromptFragment configured, String defaultName, String defaultLocation, String defaultText) {
        String name = defaultName;
        String text = null;

        if (configured != null) {
            if (configured.getName() != null && !configured.getName().isBlank()) {
                name = configured.getName();
            }
            if (configured.getText() != null && !configured.getText().isBlank()) {
                text = configured.getText().strip();          // 优先级 1：内联
            } else if (configured.getTextLocation() != null && !configured.getTextLocation().isBlank()) {
                text = templateLoader.load(configured.getTextLocation()); // 优先级 2：文件
                if (text != null) {
                    text = text.strip();
                }
            }
        }
        if (text == null || text.isBlank()) {
            text = defaultText;                                // 优先级 3：内置默认
        }

        PromptFragment f = new PromptFragment();
        f.setName(name);
        f.setText(text);
        return f;
    }
}
