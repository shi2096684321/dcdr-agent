-- =====================================================================
-- 长期记忆召回健康度巡检 (Memory Health)
-- ---------------------------------------------------------------------
-- 用途: 回答"记忆是不是越攒越多、越攒越没用" —— 记忆页只能告诉你"存了什么",
--       回答不了"哪几条从来没起过作用"。
--
-- 背景: 记忆的召回是"每轮都要过一遍"的常驻成本(常驻条目直接注入 + 其余逐条打分),
--       所以一条从不命中的记忆不是"闲置", 而是**持续消耗**: 占着常驻额度、
--       吃掉按背景信息预算分配的名额, 却对回答毫无贡献。
--
-- 数据来源(两条互补, 不要只看一条):
--   ① llm_call_logs.steps_json 中 kind='memory' 的步骤, 由 LlmCallTrace.recordMemory 写入:
--        query / hits / chars / costMs / memoryIds
--   ② long_term_memories 自身的 hit_count / last_hit_at(由 recordHit 累加)
--
-- 说明: steps_json 是 TEXT 列, 故一律先 ::jsonb 再展开;
--       memoryIds 仅在召回命中时写入, 故取它之前要用 s ? 'memoryIds' 判存在。
-- =====================================================================

-- ---------------------------------------------------------------------
-- ① 总览: 近 30 天记忆召回的规模与成本
--
--    看什么:
--      zero_hit_rate —— 零召回占比。**持续偏高**说明用户的记忆与他的实际提问对不上:
--                       要么触发键(topic/tags)没写, 要么那些记忆本来就不该常驻。
--      avg_chars     —— 平均注入字数。它能验证背景信息预算(agent.context.budget)是否在起作用:
--                       如果长期贴着上限, 说明该清理记忆了, 而不是继续调大预算。
-- ---------------------------------------------------------------------
WITH mem_steps AS (
    SELECT (s->>'hits')::int    AS hits,
           (s->>'chars')::int   AS chars,
           (s->>'costMs')::bigint AS cost_ms
    FROM llm_call_logs l
    CROSS JOIN LATERAL jsonb_array_elements(l.steps_json::jsonb) AS s
    WHERE l.created_at >= now() - interval '30 days'
      AND s->>'kind' = 'memory'
      AND l.steps_json LIKE '%"kind":"memory"%'   -- 便宜的前置过滤, 避免全表 jsonb 解析
)
SELECT COUNT(*)                                                        AS recall_calls,
       ROUND(AVG(hits), 2)                                             AS avg_hits,
       ROUND(SUM(CASE WHEN hits = 0 THEN 1 ELSE 0 END)::numeric
             / NULLIF(COUNT(*), 0), 3)                                 AS zero_hit_rate,
       ROUND(AVG(chars))                                               AS avg_chars,
       MAX(chars)                                                      AS max_chars,
       PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY cost_ms)           AS p95_cost_ms
FROM mem_steps;

-- ---------------------------------------------------------------------
-- ② 按天趋势: 注入字数与零召回率的时间序列(配合图表看"哪天开始变胖的")
-- ---------------------------------------------------------------------
WITH mem_steps AS (
    SELECT date_trunc('day', l.created_at)::date AS day,
           (s->>'hits')::int  AS hits,
           (s->>'chars')::int AS chars
    FROM llm_call_logs l
    CROSS JOIN LATERAL jsonb_array_elements(l.steps_json::jsonb) AS s
    WHERE l.created_at >= now() - interval '30 days'
      AND s->>'kind' = 'memory'
      AND l.steps_json LIKE '%"kind":"memory"%'
)
SELECT day,
       COUNT(*)                                              AS recall_calls,
       ROUND(AVG(hits), 2)                                   AS avg_hits,
       ROUND(AVG(chars))                                     AS avg_chars,
       SUM(CASE WHEN hits = 0 THEN 1 ELSE 0 END)             AS zero_hit_calls
FROM mem_steps
GROUP BY day
ORDER BY day;

-- ---------------------------------------------------------------------
-- ③ 从未命中过的记忆 —— 本文件的核心
--
--    口径: 活跃、且从未出现在任何一轮的召回 ID 里。用 steps_json 而不是只信 hit_count,
--          是为了同时拿到"最近一次被召回是什么时候", 便于区分"刚建的"与"沉底的"。
--    怎么用: 逐条判断它为什么没被召回 ——
--            · 触发键(topic/tags)写得太窄或压根没写 → 改触发键, 而不是删;
--            · 内容与用户实际提问无关 → 该删;
--            · 本来就不该常驻(importance>=0.9 / pinned 会让它每轮占额度) → 降级或取消常驻。
-- ---------------------------------------------------------------------
WITH recalled_ids AS (
    SELECT DISTINCT jsonb_array_elements_text(s->'memoryIds') AS memory_id
    FROM llm_call_logs l
    CROSS JOIN LATERAL jsonb_array_elements(l.steps_json::jsonb) AS s
    WHERE l.created_at >= now() - interval '90 days'
      AND s->>'kind' = 'memory'
      AND s ? 'memoryIds'
      AND l.steps_json LIKE '%"kind":"memory"%'
)
SELECT m.id,
       m.user_id,
       m.scope,
       m.type,
       m.pinned,
       m.importance,
       m.topic,
       LEFT(m.content, 60)  AS content_preview,
       m.created_at
FROM long_term_memories m
LEFT JOIN recalled_ids r ON r.memory_id = m.id
WHERE m.status = 'ACTIVE'
  AND r.memory_id IS NULL
ORDER BY m.pinned DESC, m.importance DESC, m.created_at;
