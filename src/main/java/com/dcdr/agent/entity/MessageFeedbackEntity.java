package com.dcdr.agent.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * V3.1 回答反馈实体 (F-48 点赞/点踩收集)。
 * <p>
 * 记录用户对**单条助手回答**的评价: 点赞/点踩 + 点踩原因标签 + 可选文字说明。
 * <p>
 * <b>定位方式</b>: 反馈主体是"一条回答", 用 {@code (sessionId, messageSeq)} 定位。
 * {@code seq} 是 messages 表的会话内序号, 在记忆层/DB 回退两条读取路径上都稳定;
 * 而 {@code MessageDTO.id} 是运行时合成 ID (sessionId + 序号), 不能作为持久键。
 * <p>
 * <b>上下文快照</b>: 提交时把回答侧的模型/工具/来源数等冗余存下, 使后续归因分析
 * (按模型、按是否联网、按是否走 RAG 聚合差评率) 不受回答被截断或删除影响。
 * <p>
 * 本版只做"收集", 数据的消费 (改提示词 / 调路由 / 构建示例库) 属后续版本。
 */
@Data
@Entity
@Schema(description = "V3.1 回答反馈 (F-48)")
@Table(name = "message_feedback", uniqueConstraints = {
        @UniqueConstraint(name = "uk_feedback_msg_user",
                columnNames = {"session_id", "message_seq", "user_id"})
}, indexes = {
        @Index(name = "idx_feedback_session", columnList = "session_id,message_seq"),
        @Index(name = "idx_feedback_created", columnList = "created_at"),
        @Index(name = "idx_feedback_rating", columnList = "rating"),
        @Index(name = "idx_feedback_user", columnList = "user_id")
})
public class MessageFeedbackEntity {

    /** 匿名用户哨兵值: 认证关闭(单用户模式)时用它占位, 保证唯一约束在无用户体系下依然可用 */
    public static final String ANONYMOUS_USER = "anonymous";

    /** 点赞 */
    public static final String RATING_UP = "up";
    /** 点踩 */
    public static final String RATING_DOWN = "down";

    @Schema(description = "主键 ID (自增)")
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Schema(description = "会话 ID")
    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    /** 被评价回答在会话内的序号 (messages.seq) */
    @Schema(description = "被评价回答的会话内序号 (messages.seq)")
    @Column(name = "message_seq", nullable = false)
    private int messageSeq;

    @Schema(description = "反馈人用户 ID (认证关闭时为 anonymous)")
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Schema(description = "评价: up=点赞 / down=点踩", allowableValues = {"up", "down"})
    @Column(nullable = false, length = 8)
    private String rating;

    @Schema(description = "点踩原因标签 code (逗号分隔)")
    @Column(name = "reason_tags", length = 255)
    private String reasonTags;

    @Schema(description = "用户补充说明 (可选)")
    @Column(columnDefinition = "TEXT")
    private String comment;

    @Schema(description = "快照: 生成该回答的模型")
    @Column(length = 64)
    private String model;

    @Schema(description = "快照: 本轮调用的工具 (JSON 字符串)")
    @Column(name = "tools_used", length = 2000)
    private String toolsUsed;

    @Schema(description = "快照: 引用来源条数")
    @Column(name = "source_count", nullable = false)
    private int sourceCount = 0;

    @Schema(description = "快照: 是否走了联网检索")
    @Column(name = "web_search", nullable = false)
    private boolean webSearch = false;

    @Schema(description = "快照: 回答正文长度 (字符)")
    @Column(name = "message_chars", nullable = false)
    private int messageChars = 0;

    @Schema(description = "创建时间")
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Schema(description = "更新时间 (改评价时刷新)")
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
