package com.dcdr.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文占用明细 —— 回答"这一轮到底把多少 token 花在了什么上"(V3.4)。
 * <p>
 * <b>为什么要有它</b>: 此前用户只看得到两条极端信息 —— 平时什么也看不到, 直到接近上限才弹一句
 * "建议开启新对话"。既不知道已经用了多少, 也不知道占用是花在系统提示词、工具定义还是历史对话上,
 * 于是没法判断"该开新会话"还是"该关掉几个工具/技能"。
 * <p>
 * <b>口径</b>: 全部是<b>估算</b>(jtokkit cl100k BPE, 见 {@code TokenEstimator})。分类依据是
 * "实际发给模型的东西": system 消息按注入片段的 name 归类, 工具按注册类型区分 MCP/插件,
 * 因此五段之和 ≈ 下一轮请求的 prompt 规模, 但不是厂商返回的 usage(各家分词器不同)。
 * 界面必须标注"估算", 不能让用户拿它当账单。
 * <p>
 * 同一结构三处复用(SSE 实时块 / 会话快照 / 接口返回), 因此字段名即对外契约。
 */
@Data
@Schema(description = "上下文占用明细（估算值，按分段统计实际发给模型的内容）")
public class ContextUsage {

    /**
     * 是否有可信数据。
     * <p>新会话在发出第一条消息前不存在快照 —— 此时 {@code available=false},
     * 界面显示空态而不是"已用 0 / 上限 X"(后者会被误读成"上下文是空的")。
     */
    @Schema(description = "是否有可信数据（新会话未发起过对话时为 false）")
    private boolean available;

    /** 已用 token 合计(各分段之和) */
    @Schema(description = "已用 token（估算，各分段之和）")
    private int totalTokens;

    /** 当前生效的总上下文上限(用户设置优先, 未设置跟随部署默认) */
    @Schema(description = "当前生效的总上下文上限")
    private int limitTokens;

    /** 已用 / 上限, 0~100 保留一位小数; 超过 100 按原值返回, 由界面决定如何呈现 */
    @Schema(description = "占用百分比（0~100，保留一位小数；超出上限时按原值返回）")
    private double percent;

    /** 分段明细, 顺序固定: 系统提示词 / 工具及子智能体 / 对话消息 / 连接器与 MCP / 技能 */
    @Schema(description = "分段明细")
    private List<Segment> segments = new ArrayList<>();

    /**
     * 本轮模型消耗(输入 / 输出 / 合计)。
     * <p>
     * <b>为什么不并进 {@link #totalTokens}</b>: 两者是不同的账。{@code totalTokens} 是"这一轮结束后
     * 窗口里躺着多少"(下一轮要重发的规模), 会随历史增长; 这里是"刚才这一轮实际烧了多少", 每轮独立。
     * 混成一个数会让用户没法判断"是历史太长还是这轮太长"。
     * <p>
     * 调用模型<b>前</b>下发时为空(还不知道答案有多长), 收尾时补一次。
     */
    @Schema(description = "本轮模型消耗（收尾时才有）")
    private Round lastRound;

    @Data
    @Schema(description = "单轮 token 消耗")
    public static class Round {

        @Schema(description = "输入(prompt) token")
        private int prompt;

        @Schema(description = "输出(completion) token")
        private int completion;

        @Schema(description = "合计")
        private int total;

        /**
         * 是否模型实测。
         * <p>{@code false} = 模型没回 usage(部分中转/自建端点不返回), 这里是用 BPE 估算的 ——
         * 界面必须据此标注, 不能把估算当账单。
         */
        @Schema(description = "是否来自模型返回的真实 usage")
        private boolean measured;
    }

    @Data
    @Schema(description = "上下文分段")
    public static class Segment {

        /** 稳定标识(前端按它取色/排序, 不要用 label 匹配) */
        @Schema(description = "分段标识", example = "system")
        private String key;

        @Schema(description = "分段中文名", example = "系统提示词")
        private String label;

        @Schema(description = "该分段 token（估算）")
        private int tokens;

        /** 该分段占已用总量的比例(0~100), 供分段条绘制 —— 由后端算好, 避免前后端各算一遍 */
        @Schema(description = "占已用总量的百分比")
        private double percent;

        /** 段内明细(按同名片段/同名工具合并) */
        @Schema(description = "段内明细")
        private List<Item> items = new ArrayList<>();
    }

    @Data
    @Schema(description = "分段内的单条明细")
    public static class Item {

        @Schema(description = "明细名（片段用途或工具名）", example = "长期记忆")
        private String label;

        @Schema(description = "token（估算）")
        private int tokens;

        /** 合并了几项(如 3 条用户消息); 1 表示单项, 界面可省略后缀 */
        @Schema(description = "合并项数")
        private int count;
    }
}
