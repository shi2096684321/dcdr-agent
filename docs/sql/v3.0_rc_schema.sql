-- =====================================================================
-- V3.0-rc 数据库扩展 (F-36 多租户 SaaS 化)
-- 执行前提: 已执行 v2.0_schema.sql / v3.0_schema.sql / v3.0_beta_schema.sql
-- 注意: ddl-auto=validate 不会自动建表, 需手动在 PostgreSQL 执行本脚本
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
