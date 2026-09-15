-- =====================================================================
-- V3.1 F-41 安全加固收口: 统一归属框架 (Ownership) 的数据库变更
--
-- 背景: V3.1 引入统一归属框架 (com.dcdr.agent.auth.ownership),
--       所有"按 id 操作"的接口统一经 OwnershipGuard 校验资源归属。
--       实现 OwnedEntity 的 11 张表里, 10 张在 V2.0/V3.0 就已有 user_id 列,
--       仅 projects (V1.x 存量表) 缺失, 故本次只有这一张表需要调整。
--
-- 执行前提: 已执行 v2.0_schema.sql / v3.0*.sql / v3.1_feedback_schema.sql
-- 注意: pg / H2 均由 JPA (ddl-auto=update) 在启动时自动补齐缺失的列, 本脚本无需手工执行。
--       但 JPA 不生成 COMMENT 与特殊索引, 需要这些特性时手工执行一次本脚本补齐即可
--       (全部语句为 IF NOT EXISTS, 可重复执行)。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 一、必执行: 创作项目补齐归属列
-- ---------------------------------------------------------------------
-- 存量数据 user_id 为 NULL: 迁移后仅 admin 或"认证关闭"模式可访问,
-- 避免历史无主项目被任意登录用户读写。
ALTER TABLE projects ADD COLUMN IF NOT EXISTS user_id VARCHAR(64);

-- 归属过滤索引 (列表查询按 user_id + updated_at 排序)
CREATE INDEX IF NOT EXISTS idx_projects_user ON projects (user_id);

-- ---------------------------------------------------------------------
-- 二、执行前自检: 列出"应有归属列却缺失"的表 (预期返回 0 行)
-- ---------------------------------------------------------------------
SELECT t.tbl AS missing_table
  FROM (VALUES ('sessions'), ('knowledge_bases'), ('documents'), ('agent_definitions'),
               ('workflow_definitions'), ('workflow_executions'), ('scheduled_tasks'),
               ('webhook_endpoints'), ('user_model_configs'), ('template_shares'),
               ('projects')) AS t(tbl)
  LEFT JOIN information_schema.columns c
         ON c.table_schema = current_schema()
        AND c.table_name = t.tbl
        AND c.column_name = 'user_id'
 WHERE c.column_name IS NULL;

-- ---------------------------------------------------------------------
-- 三、存量数据处置 (二选一; 默认保持 NULL, 即选项 A)
-- ---------------------------------------------------------------------
-- 选项 A (默认, 最安全): 保持 NULL —— 存量项目仅 admin 可见。
-- 选项 B (单用户升级场景): 把历史无主项目一次性归给指定用户。
--   注意: 这是"认领"操作, 仅当确认该库此前确实只有一个人在用才可执行。
-- UPDATE projects SET user_id = '<目标用户ID>' WHERE user_id IS NULL;

-- ---------------------------------------------------------------------
-- 四、校验: 确认列已创建
-- ---------------------------------------------------------------------
SELECT column_name, data_type, character_maximum_length
  FROM information_schema.columns
 WHERE table_name = 'projects' AND column_name = 'user_id';

-- ---------------------------------------------------------------------
-- 五、回滚 (仅在需要撤销本次变更时执行)
-- ---------------------------------------------------------------------
-- DROP INDEX IF EXISTS idx_projects_user;
-- ALTER TABLE projects DROP COLUMN IF EXISTS user_id;
