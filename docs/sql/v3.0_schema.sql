-- ==========================================================================
-- V3.0 数据库增量脚本(routing/webhook/工作流执行 → 版本与用量/模板 → 多租户 → LLM 调用链)
-- (2026-10-15 由多个分脚本合并而来; 各段落保留原文件头说明,
--  段落顺序 = 历史演进/依赖顺序; 全部语句幂等, 可重复执行)
-- ==========================================================================

-- ---------------------------------------------------------------------------
-- [1/4] 来源: v3.0_schema.sql
-- ---------------------------------------------------------------------------
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

-- ---------------------------------------------------------------------------
-- [2/4] 来源: v3.0_beta_schema.sql
-- ---------------------------------------------------------------------------
-- ============================================================================
-- dcdr-agent V3.0-beta 数据库 schema 增量 (PostgreSQL 16 + pgvector)
-- 用途: V3.0-beta 治理与可观测性 / 模板市场 / 可视化 DAG 编排
--   - F-34 治理与可观测性: workflow_versions (工作流版本历史)
--                            llm_usage_logs (LLM 调用 token 记录)
--   - F-35 模板市场:       template_shares (模板分享记录)
--   - F-32 可视化 DAG:     workflow_definitions.dag_json (DAG 格式, 与 steps_json 并存)
-- 执行顺序: 在 v3.0_schema.sql 之后执行; 均使用 CREATE TABLE IF NOT EXISTS / ALTER ADD COLUMN IF NOT EXISTS,
--           可在已有库上重复执行。
-- ============================================================================

-- 1. F-34 工作流版本历史 (文档 §8.2)
CREATE TABLE IF NOT EXISTS workflow_versions (
    id               VARCHAR(64)  PRIMARY KEY,
    workflow_id      VARCHAR(64)  REFERENCES workflow_definitions(id) ON DELETE CASCADE,
    version          INT          NOT NULL,
    name             VARCHAR(255),
    description      TEXT,
    mode             VARCHAR(32),
    steps_json       TEXT         NOT NULL,
    dag_json         TEXT,
    change_note      VARCHAR(255),
    created_by       VARCHAR(64),
    created_at       TIMESTAMP    NOT NULL DEFAULT now(),
    UNIQUE(workflow_id, version)
);

-- 2. F-34 LLM 调用 token 记录 (文档 §8.3)
CREATE TABLE IF NOT EXISTS llm_usage_logs (
    id                     VARCHAR(64)  PRIMARY KEY,
    user_id                VARCHAR(64),
    session_id             VARCHAR(64),
    workflow_execution_id  VARCHAR(64),
    source                 VARCHAR(16)  NOT NULL,        -- chat / workflow / routing
    model                  VARCHAR(128),
    input_tokens           INT,
    output_tokens          INT,
    total_tokens           INT,
    cost_amount            DECIMAL(10,6),
    created_at             TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_llmusage_user    ON llm_usage_logs(user_id);
CREATE INDEX IF NOT EXISTS idx_llmusage_time    ON llm_usage_logs(created_at);
CREATE INDEX IF NOT EXISTS idx_llmusage_model   ON llm_usage_logs(model);
CREATE INDEX IF NOT EXISTS idx_llmusage_userday ON llm_usage_logs(user_id, created_at);

-- 3. F-35 模板分享记录 (文档 §9.4)
CREATE TABLE IF NOT EXISTS template_shares (
    id               VARCHAR(64)  PRIMARY KEY,
    user_id          VARCHAR(64)  REFERENCES users(id) ON DELETE CASCADE,
    type             VARCHAR(16)  NOT NULL,        -- agent / workflow
    target_id        VARCHAR(64)  NOT NULL,
    title            VARCHAR(255),
    description      TEXT,
    category         VARCHAR(64),
    tags             TEXT,                          -- 逗号分隔标签
    clone_count      INT          NOT NULL DEFAULT 0,
    rating_sum       INT          NOT NULL DEFAULT 0,
    rating_count     INT          NOT NULL DEFAULT 0,
    enabled          BOOLEAN      NOT NULL DEFAULT true,
    created_at       TIMESTAMP    NOT NULL DEFAULT now(),
    UNIQUE(type, target_id)
);
CREATE INDEX IF NOT EXISTS idx_tplshare_category ON template_shares(category);

-- 4. F-32 可视化 DAG: workflow_definitions 增加 dag_json 列 (与 steps_json 并存)
ALTER TABLE workflow_definitions ADD COLUMN IF NOT EXISTS dag_json TEXT;

-- ---------------------------------------------------------------------------
-- [3/4] 来源: v3.0_rc_schema.sql
-- ---------------------------------------------------------------------------
-- =====================================================================
-- V3.0-rc 数据库扩展 (F-36 配额型多租户; 完整 SaaS 化不实施, 见 docs/12-未来可扩展项记录.md)
-- 执行前提: 已执行 v2.0_schema.sql / v3.0_schema.sql / v3.0_beta_schema.sql
-- 注意: pg / H2 均由 JPA (ddl-auto=update) 在启动时自动补齐缺失的表与列, 本脚本无需手工执行
--       (脚本保留用于补齐 JPA 不生成的 COMMENT 与特殊索引; 全部语句可重复执行)
-- =====================================================================

-- F-36 租户表
CREATE TABLE IF NOT EXISTS tenants (
    id               VARCHAR(64)  PRIMARY KEY,
    name             VARCHAR(255) NOT NULL,
    plan             VARCHAR(32)  NOT NULL DEFAULT 'free',    -- free / pro / enterprise
    max_users        INT          NOT NULL DEFAULT 5,
    max_agents       INT          NOT NULL DEFAULT 10,
    max_workflows    INT          NOT NULL DEFAULT 20,
    max_tokens_month BIGINT       NOT NULL DEFAULT 1000000,
    status           VARCHAR(16)  NOT NULL DEFAULT 'active',  -- active / suspended
    created_at       TIMESTAMP    NOT NULL DEFAULT now()
);

-- 用户归属租户 (可空: null = 平台直属用户, 不参与租户配额)
ALTER TABLE users ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) REFERENCES tenants(id);
CREATE INDEX IF NOT EXISTS idx_users_tenant ON users(tenant_id);

-- 月 token 用量按租户聚合走 llm_usage_logs JOIN users, 已有 idx_llmusage_user_day 索引支撑

-- ---------------------------------------------------------------------------
-- [4/4] 来源: v3.0_llm_trace_schema.sql
-- ---------------------------------------------------------------------------
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
