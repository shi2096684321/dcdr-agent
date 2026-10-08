-- ============================================================================
-- dcdr-agent V3.1 F-44 治理完善 (版本管理 + 成本分析) 数据库增量 (PostgreSQL 16 + pgvector)
-- 用途:
--   F-44.1 版本管理: agent_definition_versions (Agent 定义版本快照)
--                    + template_shares.source_version (发布钉住的版本号)
--   F-44.2 成本分析: 复用 V3.0 已建的 llm_usage_logs, 仅补区间聚合索引
--
-- 与既有脚本的关系:
--   - workflow_versions(工作流版本) 已由 v3.0_beta_schema.sql 建立, **本脚本不重复创建** ——
--     F-44.1 只补 Agent 侧的同构表, 工作流版本复用现有表(不迁移、不改名)
--   - 规划文档里写的 cost_records 并不存在: F-39 落地时用的就是 llm_usage_logs, 故不新建成本表
--
-- 执行顺序: 在 v3.0_beta_schema.sql 之后执行; 全部使用 IF NOT EXISTS, 可在已有库上重复执行
--
-- 注意: 应用侧 ddl-auto=update 会在启动时自动建表/加列, 因此本脚本主要面向生产 PG 的显式 DDL。
--       由于 CREATE TABLE IF NOT EXISTS 在"JPA 已建表"时是空操作, 外键约束不会自动补上
--       (JPA 无关系映射, 不生成 FK)—— 需要外键请在建表前先执行本脚本, 或手工 ALTER 补。
-- ============================================================================

-- 1. F-44.1 Agent 定义版本快照 (字段与 agent_definitions 的定义列一一对应)
CREATE TABLE IF NOT EXISTS agent_definition_versions (
    id            VARCHAR(64)  PRIMARY KEY,
    agent_id      VARCHAR(64)  REFERENCES agent_definitions(id) ON DELETE CASCADE,
    version       INT          NOT NULL,
    name          VARCHAR(255) NOT NULL,
    description   TEXT,
    avatar        VARCHAR(512),
    system_prompt TEXT,
    model         VARCHAR(128),
    tools         TEXT,
    kb_ids        TEXT,
    temperature   DECIMAL(3,2),
    is_public     BOOLEAN      NOT NULL DEFAULT false,
    is_template   BOOLEAN      NOT NULL DEFAULT false,
    change_note   VARCHAR(255),
    created_by    VARCHAR(64),
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    UNIQUE(agent_id, version)
);
CREATE INDEX IF NOT EXISTS idx_agentver_agent ON agent_definition_versions(agent_id);

-- 2. F-44.1 模板市场: 发布时钉住的源版本号
--    为空表示"按当前定义克隆"(预置模板 / 本特性上线前的存量分享记录), 行为与 V3.0 一致
ALTER TABLE template_shares ADD COLUMN IF NOT EXISTS source_version INT;

-- 3. F-44.2 成本报表: 区间 + 模型维度的聚合查询
--    已有的 idx_llmusage_userday(user_id, created_at) 覆盖"按用户+区间",
--    本索引覆盖"全库区间内按模型聚合"(报表的模型占比维度)
CREATE INDEX IF NOT EXISTS idx_llmusage_time_model ON llm_usage_logs(created_at, model);
