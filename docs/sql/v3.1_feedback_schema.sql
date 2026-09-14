-- =====================================================================
-- V3.1 回答反馈收集 (F-48 点赞/点踩)
-- ---------------------------------------------------------------------
-- 用途: 收集用户对**单条助手回答**的评价 (点赞/点踩 + 原因标签 + 可选文字),
--       并快照该回答的技术上下文 (模型/工具/引用来源数), 供后续归因分析
--       (按模型/是否联网/是否走 RAG 聚合差评率、构造回归用例、沉淀示例库)。
--
-- 反馈主体是"一条回答", 通过 (session_id, message_seq) 定位 —— seq 是 messages 表
-- 的会话内序号, 前后端链路稳定 (MessageDTO.id 是运行时合成 ID, 不可作为持久键)。
--
-- 执行前提: 已执行 v2.0_schema.sql / v3.0*.sql
-- 注意: pg profile 下 ddl-auto=validate 不会自动建表, 需手动在 PostgreSQL 执行本脚本;
--       H2 开发库 (ddl-auto=update) 由 JPA 自动建表, 无需执行。
-- =====================================================================

CREATE TABLE IF NOT EXISTS message_feedback (
    id            BIGSERIAL    PRIMARY KEY,
    session_id    VARCHAR(64)  NOT NULL,                      -- 会话 ID (messages.session_id)
    message_seq   INT          NOT NULL,                      -- 会话内消息序号 (messages.seq), 定位被评价的回答
    user_id       VARCHAR(64)  NOT NULL DEFAULT 'anonymous',  -- 反馈人; 认证关闭(单用户模式)时写 anonymous 哨兵值, 保证唯一约束可用
    rating        VARCHAR(8)   NOT NULL,                      -- up = 点赞, down = 点踩
    reason_tags   VARCHAR(255),                               -- 点踩原因标签 code, 逗号分隔 (见 FeedbackService.REASON_TAGS)
    comment       TEXT,                                       -- 用户补充说明 (可选)
    -- ===== 回答侧上下文快照 (提交时从 messages 冗余, 避免回答被截断/删除后无法归因) =====
    model         VARCHAR(64),                                -- 生成该回答的模型
    tools_used    VARCHAR(2000),                              -- 本轮调用的工具 (JSON 字符串)
    source_count  INT          NOT NULL DEFAULT 0,            -- 引用来源条数 (RAG 片段/网页)
    web_search    BOOLEAN      NOT NULL DEFAULT false,        -- 是否走了联网检索
    message_chars INT          NOT NULL DEFAULT 0,            -- 回答正文长度 (字符, 用于"太啰嗦/太短"归因)
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT now()
);

-- 同一用户对同一条回答只保留一条反馈 (重复提交即更新, 便于用户改主意)
CREATE UNIQUE INDEX IF NOT EXISTS uk_feedback_msg_user
    ON message_feedback(session_id, message_seq, user_id);

-- 会话删除 / 历史截断时的级联清理
CREATE INDEX IF NOT EXISTS idx_feedback_session ON message_feedback(session_id, message_seq);
-- 看板聚合 (按时间窗口 + 好评/差评)
CREATE INDEX IF NOT EXISTS idx_feedback_created ON message_feedback(created_at);
CREATE INDEX IF NOT EXISTS idx_feedback_rating  ON message_feedback(rating);
CREATE INDEX IF NOT EXISTS idx_feedback_user    ON message_feedback(user_id);
