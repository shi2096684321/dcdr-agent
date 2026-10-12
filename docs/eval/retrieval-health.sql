-- =====================================================================
-- 检索健康度巡检 (Retrieval Health)
-- ---------------------------------------------------------------------
-- 用途: 回答"线上文档持续增长时, 检索效果有没有漂移" —— 这是固定评测集
--       (RagRetrievalEvalIT / RagRetrievalEvalLiveIT) 答不了的问题:
--       评测集用的是冻结/手动标注的语料, 而这里看的是**真实流量**。
--
-- 数据来源: llm_call_logs.steps_json 中 kind='rag' 的步骤,
--          由 LlmCallTrace.recordRag 写入, 字段为:
--             query / kbCount / hits / costMs / topScore / docIds
--
-- 使用方式: 定期(建议每周)在 pg 里执行, 关注**趋势**而非绝对值。
--          对照 TODO.md「第十二批」中记录的已知背景一并理解。
--
-- 说明: steps_json 是 TEXT 列, 故一律先 ::jsonb 再展开;
--       docIds 仅在命中时写入, 故取它之前要用 s ? 'docIds' 判存在。
-- =====================================================================

-- ---------------------------------------------------------------------
-- ① 总览: 近 30 天的检索健康度(零命中率 / 平均命中数 / 平均最高相似度 / 耗时)
--
--    看什么:
--      zero_hit_rate  —— 零命中占比。**突然上升**的信号最值得警惕:
--                        要么用户问的超出语料(知识盲区), 要么检索真的退化了。
--      avg_top_score  —— 最高相似度均值。**整体下滑**说明语料与问题的匹配度变差
--                        (文档变多、话题被稀释、新文档质量差)。
--      p95_cost_ms    —— 语料变大后的性能退化。
-- ---------------------------------------------------------------------
WITH rag_steps AS (
    SELECT l.created_at,
           (s->>'hits')::int              AS hits,
           (s->>'topScore')::double precision AS top_score,
           (s->>'costMs')::bigint         AS cost_ms
    FROM llm_call_logs l
    CROSS JOIN LATERAL jsonb_array_elements(l.steps_json::jsonb) AS s
    WHERE l.created_at >= now() - interval '30 days'
      AND s->>'kind' = 'rag'
      AND l.steps_json IS NOT NULL
      AND l.steps_json LIKE '%"kind":"rag"%'   -- 便宜的前置过滤, 避免全表 jsonb 解析
)
SELECT COUNT(*)                                                      AS rag_calls,
       ROUND(AVG(hits), 2)                                           AS avg_hits,
       ROUND(SUM(CASE WHEN hits = 0 THEN 1 ELSE 0 END)::numeric
             / NULLIF(COUNT(*), 0), 3)                               AS zero_hit_rate,
       ROUND(AVG(top_score)::numeric, 3)                             AS avg_top_score,
       PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY cost_ms)         AS p95_cost_ms
FROM rag_steps;

-- ---------------------------------------------------------------------
-- ② 按天趋势: 上面那几个指标的时间序列(配合图表看"哪天开始变差的")
-- ---------------------------------------------------------------------
WITH rag_steps AS (
    SELECT date_trunc('day', l.created_at)::date AS day,
           (s->>'hits')::int                  AS hits,
           (s->>'topScore')::double precision AS top_score
    FROM llm_call_logs l
    CROSS JOIN LATERAL jsonb_array_elements(l.steps_json::jsonb) AS s
    WHERE l.created_at >= now() - interval '30 days'
      AND s->>'kind' = 'rag'
      AND l.steps_json LIKE '%"kind":"rag"%'
)
SELECT day,
       COUNT(*)                                             AS rag_calls,
       ROUND(AVG(hits), 2)                                  AS avg_hits,
       SUM(CASE WHEN hits = 0 THEN 1 ELSE 0 END)            AS zero_hit_calls,
       ROUND(AVG(top_score)::numeric, 3)                    AS avg_top_score
FROM rag_steps
GROUP BY day
ORDER BY day;

-- ---------------------------------------------------------------------
-- ③ 按知识库拆分: 哪个库的效果最差
--    (零命中率明显高于其它库的, 通常是该库语料质量或切分有问题)
-- ---------------------------------------------------------------------
WITH rag_steps AS (
    SELECT (s->>'kbCount')::int                AS kb_count,
           (s->>'hits')::int                   AS hits,
           (s->>'topScore')::double precision  AS top_score
    FROM llm_call_logs l
    CROSS JOIN LATERAL jsonb_array_elements(l.steps_json::jsonb) AS s
    WHERE l.created_at >= now() - interval '30 days'
      AND s->>'kind' = 'rag'
      AND l.steps_json LIKE '%"kind":"rag"%'
)
SELECT kb_count,
       COUNT(*)                                             AS rag_calls,
       ROUND(AVG(hits), 2)                                  AS avg_hits,
       ROUND(SUM(CASE WHEN hits = 0 THEN 1 ELSE 0 END)::numeric
             / NULLIF(COUNT(*), 0), 3)                      AS zero_hit_rate,
       ROUND(AVG(top_score)::numeric, 3)                    AS avg_top_score
FROM rag_steps
GROUP BY kb_count
ORDER BY kb_count;

-- ---------------------------------------------------------------------
-- ④ 死文档排查: 入库超过 7 天、但近 30 天从未被召回过的文档
--
--    这是文档增长场景里**最隐蔽**的退化形态 —— 整体命中率可能毫无异常,
--    但部分文档实际上"像丢进了黑洞"。常见成因:
--      · 切分太碎(每块只有标题, 语义不完整)
--      · 文档内容与所在知识库主题不符
--      · 同一内容重复入库, 被同源的其它副本挤掉
--      · PDF 解析失败, 入库的是乱码或空白
-- ---------------------------------------------------------------------
WITH recalled AS (
    SELECT DISTINCT jsonb_array_elements_text(s->'docIds') AS doc_id
    FROM llm_call_logs l
    CROSS JOIN LATERAL jsonb_array_elements(l.steps_json::jsonb) AS s
    WHERE l.created_at >= now() - interval '30 days'
      AND s->>'kind' = 'rag'
      AND s ? 'docIds'
      AND l.steps_json LIKE '%"kind":"rag"%'
)
SELECT d.kb_id,
       d.id                                        AS document_id,
       d.filename,
       COUNT(c.id)                                 AS chunk_count,
       MIN(c.created_at)                           AS ingested_at
FROM documents d
JOIN document_chunks c ON c.doc_id = d.id
LEFT JOIN recalled r ON r.doc_id = d.id
WHERE r.doc_id IS NULL
  AND d.created_at < now() - interval '7 days'
GROUP BY d.kb_id, d.id, d.filename
ORDER BY d.kb_id, chunk_count DESC;

-- ---------------------------------------------------------------------
-- ⑤ 零命中的原始问题样本: 用来判断是"知识盲区"还是"检索退化"
--
--    前者应补充文档; 后者应排查切分 / embedding / topK / minScore。
--    挑其中有代表性的, 按 eval/live/cases.json 的格式追加进评测集。
-- ---------------------------------------------------------------------
SELECT l.created_at,
       (s->>'query')                       AS question,
       (s->>'kbCount')::int                AS kb_count,
       (s->>'topScore')::double precision  AS top_score
FROM llm_call_logs l
CROSS JOIN LATERAL jsonb_array_elements(l.steps_json::jsonb) AS s
WHERE l.created_at >= now() - interval '7 days'
  AND s->>'kind' = 'rag'
  AND (s->>'hits')::int = 0
  AND l.steps_json LIKE '%"kind":"rag"%'
ORDER BY l.created_at DESC
LIMIT 100;
