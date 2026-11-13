-- =============================================================================
-- V3.5 前缀缓存可观测
-- 全部为增量 DDL，均带 IF NOT EXISTS，可重复执行；不回填存量数据。
-- =============================================================================

-- =============================================================================
-- llm_usage_logs 增加前缀缓存读写 token
--
-- 设计要点：
--   1. 与 input_tokens 是包含关系：缓存读的那部分已经算在输入里，只是计价更低。
--      因此 前缀命中率 = cache_read_tokens / input_tokens，不需要再加分母。
--   2. 0 的含义是「未观测到或未命中」：供应商未上报缓存明细时也落 0 —— 两件事在数据上
--      无法区分，这是既有事实，不要据此断言"缓存没生效"。要看是否支持，看该模型的原始响应。
--   3. 不回填存量：老记录当时确实没观测（NULL），补 0 会被读成"当时没命中"。
--      历史行为 NULL，新行为 0 或正整数。
-- =============================================================================

ALTER TABLE llm_usage_logs ADD COLUMN IF NOT EXISTS cache_read_tokens INTEGER;

COMMENT ON COLUMN llm_usage_logs.cache_read_tokens IS
    '命中前缀缓存的输入 token 数; 0=未观测到或未命中, NULL=v3.5 之前的存量记录';

ALTER TABLE llm_usage_logs ADD COLUMN IF NOT EXISTS cache_creation_tokens INTEGER;

COMMENT ON COLUMN llm_usage_logs.cache_creation_tokens IS
    '写入前缀缓存的输入 token 数; 仅 Anthropic 系上报, 其余为 0';
