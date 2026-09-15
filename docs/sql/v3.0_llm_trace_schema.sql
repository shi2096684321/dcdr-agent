-- =====================================================================
-- v3.0 增量(LLM 调用链独立日志): 命名沿用 v3.0_* 规范
-- 依赖: 无 (独立表, 与 messages 解耦)
-- 背景: 每次对话请求落一条记录, 保存该次请求触发的完整 LLM 调用链
--       (路由判断 / 各生成轮 / 工具步骤 / token / 总耗时 / 结束原因),
--       供前端"调用链日志"独立页面分页查看(不进对话内容)。
-- 注意: pg / H2 均由 JPA (ddl-auto=update) 在启动时自动补齐缺失的表与列, 本脚本无需手工执行
--       (脚本保留用于补齐 JPA 不生成的 COMMENT 与特殊索引; 全部语句可重复执行)。
-- =====================================================================

CREATE TABLE IF NOT EXISTS llm_call_logs (
    id                  VARCHAR(64) PRIMARY KEY,
    turn_id             VARCHAR(16),
    user_id             VARCHAR(64),
    session_id          VARCHAR(64),
    user_message        TEXT,
    end_signal          VARCHAR(32),
    llm_calls           INTEGER DEFAULT 0,
    tool_steps          INTEGER DEFAULT 0,
    total_in_tokens     BIGINT  DEFAULT 0,
    total_out_tokens    BIGINT  DEFAULT 0,
    total_duration_ms   BIGINT  DEFAULT 0,
    chain               TEXT,
    steps_json          TEXT,
    created_at          TIMESTAMP NOT NULL
);

-- 用户维度 + 时间维度索引(分页查询 userId+createdAt 倒序)
CREATE INDEX IF NOT EXISTS idx_llmcall_user_time ON llm_call_logs (user_id, created_at);