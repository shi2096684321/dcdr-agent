-- ==========================================================================
-- V3.2 数据库增量脚本 —— 调用链失败定位
--
-- 背景: llm_call_logs 原先只有 end_signal(ON_COMPLETE/ON_ERROR/ON_CANCEL),
--       调用链详情页只能告诉用户"这次失败了", 却答不出"哪一步失败、错误是什么" ——
--       与"每一步都可见"的设计初衷不符。本次补两列:
--         failed_step   失败所在步骤(如「第 2 轮生成」「工具 web_search」)
--         error_message 首个错误信息(级联错误一律忽略, 只留最初那一条)
--
-- 执行前提: 已执行 v2.0_schema.sql / v3.0_schema.sql / v3.1_schema.sql
-- 注意: pg / H2 均由 JPA (ddl-auto=update) 在启动时自动补齐缺失的列, 本脚本无需手工执行;
--       保留它是为了 ① 明确 DDL 的权威来源 ② 补齐 JPA 不生成的 COMMENT。
--       全部语句幂等 (IF NOT EXISTS / COMMENT 可重复覆盖), 可重复执行。
-- ==========================================================================

ALTER TABLE llm_call_logs ADD COLUMN IF NOT EXISTS failed_step   VARCHAR(64);
ALTER TABLE llm_call_logs ADD COLUMN IF NOT EXISTS error_message TEXT;

COMMENT ON COLUMN llm_call_logs.failed_step   IS '失败所在步骤(如 第2轮生成 / 工具 web_search); 正常结束为 NULL';
COMMENT ON COLUMN llm_call_logs.error_message IS '首个错误信息(级联错误不入库); 正常结束为 NULL';

-- ---------------------------------------------------------------------
-- 自检: 两列都应存在 (预期返回 2 行)
-- ---------------------------------------------------------------------
SELECT column_name, data_type
  FROM information_schema.columns
 WHERE table_schema = current_schema()
   AND table_name = 'llm_call_logs'
   AND column_name IN ('failed_step', 'error_message');
