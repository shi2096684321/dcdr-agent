package com.dcdr.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 会话历史摘要(V3.4) —— 让"被压缩掉的那段对话"对用户可见、可改、可删。
 *
 * <p><b>为什么必须能看见</b>: 摘要直接影响模型"记得什么"。只存在库里的话,
 * 用户会觉得这个会话突然开始胡言乱语, 而没有任何地方能看到它当前记住的是哪一版。
 *
 * <p><b>为什么必须能改</b>: 摘要是模型生成的, 会出错、会漏掉关键约束。用户一眼看到
 * "它把'不要动生产库'漏了", 直接补上比重新开一轮对话便宜得多。
 *
 * <p><b>为什么必须能删</b>: 删掉后下一轮会从完整原文重新压缩 —— 这是摘要质量的兜底手段,
 * 也是"摘要被污染(比如历史里混进了错误结论)"时的唯一出口。
 */
@Data
@Schema(description = "会话历史摘要")
public class SessionSummaryView {

    /** 是否已压缩过; false 时其余字段无意义 */
    @Schema(description = "是否已压缩过历史")
    private boolean available;

    @Schema(description = "摘要正文")
    private String text;

    /** 覆盖到的最后一条消息 seq; 组装上下文时只丢弃 seq &lt;= 它的消息 */
    @Schema(description = "摘要覆盖到的最后一条消息序号")
    private Integer uptoSeq;

    /** 被压缩掉的消息条数(不是 seq 差值 —— 工具往返会让条数与 seq 不对齐) */
    @Schema(description = "被压缩掉的消息条数")
    private long coveredMessages;

    /** 摘要字数: 用户据此判断"这一版是不是太长/太短" */
    @Schema(description = "摘要字数")
    private int chars;

    /**
     * 当前上下文策略是否真的在用这份摘要。
     * <p>切到 {@code sliding-window} 之后, 库里的摘要仍然存在但<b>不再参与组上下文</b> ——
     * 界面若照样显示"已压缩 12 条", 用户会以为模型还记得那些内容, 实际早已被窗口丢掉了。
     */
    @Schema(description = "当前策略是否使用摘要")
    private boolean applied;
}
