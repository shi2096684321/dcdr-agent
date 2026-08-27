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
