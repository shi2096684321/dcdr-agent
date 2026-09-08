-- ============================================================================
-- dcdr-agent V3.0 数据库 schema 增量 (PostgreSQL 16 + pgvector)
-- 用途: V3.0-alpha 智能路由 / 触发层扩展 / 执行容错
--   - F-30 智能路由层: routing_logs (路由判断记录)
--   - F-31 触发层扩展: webhook_endpoints (Webhook 端点配置)
--   - F-33 执行容错:   workflow_executions (工作流执行记录, 含断点状态)
-- 执行顺序: 在 v2.0_schema.sql 之后执行; 均使用 CREATE TABLE IF NOT EXISTS / ALTER ADD COLUMN IF NOT EXISTS,
--           可在已有库上重复执行。
-- ============================================================================

-- 1. F-30 智能路由记录表 (用于分析路由准确率, 文档 §4.5)
--    token 字段: 路由 LLM 调用消耗(平台探测成本, 不计入用户用量/配额, 仅此处展示)
CREATE TABLE IF NOT EXISTS routing_logs (
    id               VARCHAR(64)  PRIMARY KEY,
    user_id          VARCHAR(64)  REFERENCES users(id) ON DELETE CASCADE,
    session_id       VARCHAR(64),
    user_message     TEXT         NOT NULL,
    route_type       VARCHAR(16)  NOT NULL,        -- workflow / agent / chat
    target_id        VARCHAR(64),
    confidence       DECIMAL(3,2),
    reason           TEXT,
    input_tokens     INTEGER      NOT NULL DEFAULT 0,  -- 路由 LLM 输入 token
    output_tokens    INTEGER      NOT NULL DEFAULT 0,  -- 路由 LLM 输出 token
    total_tokens     INTEGER      NOT NULL DEFAULT 0,  -- 路由 LLM 总 token
    created_at       TIMESTAMP    NOT NULL DEFAULT now()
);
ALTER TABLE routing_logs ADD COLUMN IF NOT EXISTS input_tokens  INTEGER NOT NULL DEFAULT 0;
ALTER TABLE routing_logs ADD COLUMN IF NOT EXISTS output_tokens INTEGER NOT NULL DEFAULT 0;
ALTER TABLE routing_logs ADD COLUMN IF NOT EXISTS total_tokens  INTEGER NOT NULL DEFAULT 0;
-- V3.1: 路由调用实际提交的 System Prompt(截断 4000 字符), 供管理员路由详情排查
ALTER TABLE routing_logs ADD COLUMN IF NOT EXISTS system_prompt TEXT;
CREATE INDEX IF NOT EXISTS idx_routing_user  ON routing_logs(user_id);
CREATE INDEX IF NOT EXISTS idx_routing_time  ON routing_logs(created_at);
CREATE INDEX IF NOT EXISTS idx_routing_type  ON routing_logs(route_type);

-- 2. F-31 Webhook 端点配置表 (文档 §5.3)
CREATE TABLE IF NOT EXISTS webhook_endpoints (
    id               VARCHAR(64)  PRIMARY KEY,
    user_id          VARCHAR(64)  REFERENCES users(id) ON DELETE CASCADE,
    workflow_id      VARCHAR(64)  REFERENCES workflow_definitions(id) ON DELETE CASCADE,
    name             VARCHAR(255),
    token            VARCHAR(128) NOT NULL UNIQUE,    -- 随机 token, 用于 URL (UNIQUE 自动建唯一索引)
    secret           VARCHAR(128),                    -- 可选, 用于签名验证
    enabled          BOOLEAN      NOT NULL DEFAULT true,
    last_triggered   TIMESTAMP,
    trigger_count    INT          NOT NULL DEFAULT 0,
    created_at       TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_webhook_user ON webhook_endpoints(user_id);

-- 3. F-33 工作流执行记录表 (断点续跑核心, 文档 §7.3)
CREATE TABLE IF NOT EXISTS workflow_executions (
    id               VARCHAR(64)  PRIMARY KEY,
    workflow_id      VARCHAR(64)  REFERENCES workflow_definitions(id) ON DELETE CASCADE,
    user_id          VARCHAR(64)  REFERENCES users(id) ON DELETE CASCADE,
    trigger_type     VARCHAR(16)  NOT NULL,        -- manual / api / webhook / event / schedule / chat
    input            TEXT,
    status           VARCHAR(16)  NOT NULL,        -- running / paused / success / failed / cancelled
    current_step     VARCHAR(64),
    steps_state_json TEXT,                          -- 每步状态 + 输出 (JSON)
    started_at       TIMESTAMP    NOT NULL DEFAULT now(),
    completed_at     TIMESTAMP,
    error            TEXT,
    cost_tokens      INT,
    cost_amount      DECIMAL(10,4)
);
CREATE INDEX IF NOT EXISTS idx_wfexec_workflow ON workflow_executions(workflow_id);
CREATE INDEX IF NOT EXISTS idx_wfexec_user     ON workflow_executions(user_id);
CREATE INDEX IF NOT EXISTS idx_wfexec_status   ON workflow_executions(status);
