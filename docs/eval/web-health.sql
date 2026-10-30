-- =====================================================================
-- 联网健康度巡检 (Web Search / Extract Health)
-- ---------------------------------------------------------------------
-- 用途: 回答"联网搜索与网页抓取的效果有没有变差" —— 这也是固定评测集
--       (WebExtractFixtureTest 存档页面 / WebExtractLiveSmokeTest 真实站点)
--       答不了的那一半: 评测集用的是冻结的金标与存档页面, 而这里看的是**真实流量**。
--
-- 数据来源: llm_call_logs.steps_json 中 kind='web' 的步骤,
--          由 LlmCallTrace.recordWeb 写入, 字段为:
--             tool / query / rawCount / results / costMs
--             extractOk / extractFailed / contentChars / thinPages (仅触发抓取时写)
--             emptyReason / emptyReasonLabel (仅没拿到东西时写)
--
-- 与检索健康度(docs/eval/retrieval-health.sql)的关系:
--   两者思路一致(看趋势、不看绝对值), 但**故障形态完全不同** ——
--   检索退化表现为"命中了但不太相关", 联网退化的第一表现是"一条都没拿到",
--   而后者在对话里看不出来(模型会拿空上下文继续编答案)。故这里的第一屏就是空结果率。
--
-- 使用方式: 定期(建议每周)在 pg 里执行, 关注**趋势**而非绝对值。
-- =====================================================================

-- ---------------------------------------------------------------------
-- ① 总览: 近 30 天的联网健康度
--
--    看什么:
--      empty_rate        —— results=0 的占比。**这是最该盯的一个数**:
--                           它一涨, 回答就开始悄悄变成"模型自由发挥", 而对话界面毫无异常。
--      avg_keep_ratio    —— 清洗存活率(保留条数 ÷ 上游原始条数)。**持续下降**说明
--                           SearchResultCleaner 的过滤规则相对当下的结果形态过严了
--                           (引擎返回的新页面类型没被规则覆盖, 或者误伤了真实文章)。
--      extract_success   —— 串联抓取成功率。掉下来通常是目标站点开始拦截、改版或需要登录。
--      thin_rate         —— "抓到了但正文极短"的占比。**抓取成功率会骗人**:
--                           抓回一个登录页也算成功, 但模型拿到的只有一句"请先登录"。
--      p95_cost_ms       —— 抓取变慢(站点变慢 / 超时重试)会直接拖长整轮对话时延。
-- ---------------------------------------------------------------------
WITH web_steps AS (
    SELECT COALESCE(s->>'tool', '?')           AS tool,
           NULLIF(s->>'rawCount', '')::int     AS raw_count,
           COALESCE((s->>'results')::int, 0)   AS kept_cnt,
           COALESCE((s->>'extractOk')::int, 0)     AS extract_ok,
           COALESCE((s->>'extractFailed')::int, 0) AS extract_failed,
           COALESCE((s->>'contentChars')::int, 0)  AS content_chars,
           COALESCE((s->>'thinPages')::int, 0)     AS thin_pages,
           (s->>'emptyReason')                 AS empty_reason,
           COALESCE((s->>'costMs')::bigint, 0) AS cost_ms
    FROM llm_call_logs l
    CROSS JOIN LATERAL jsonb_array_elements(l.steps_json::jsonb) AS s
    WHERE l.created_at >= now() - interval '30 days'
      AND s->>'kind' = 'web'
      AND l.steps_json LIKE '%"kind":"web"%'   -- 便宜的前置过滤, 避免全表 jsonb 解析
)
SELECT COUNT(*)                                                          AS web_calls,
       COUNT(*) FILTER (WHERE tool = 'web_search')                       AS search_calls,
       COUNT(*) FILTER (WHERE tool = 'web_extract')                      AS extract_calls,
       ROUND(COUNT(*) FILTER (WHERE kept_cnt = 0)::numeric
             / NULLIF(COUNT(*), 0), 3)                                   AS empty_rate,
       ROUND(AVG(CASE WHEN raw_count > 0
                      THEN kept_cnt::numeric / raw_count END), 3)        AS avg_keep_ratio,
       ROUND(SUM(extract_ok)::numeric
             / NULLIF(SUM(extract_ok + extract_failed), 0), 3)           AS extract_success,
       ROUND(SUM(thin_pages)::numeric
             / NULLIF(SUM(extract_ok), 0), 3)                            AS thin_rate,
       ROUND(AVG(NULLIF(content_chars, 0)))                              AS avg_content_chars,
       PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY cost_ms)             AS p95_cost_ms
FROM web_steps;

-- ---------------------------------------------------------------------
-- ② 按天趋势: 上面几个指标的时间序列(配合图表看"哪天开始变差的")
-- ---------------------------------------------------------------------
WITH web_steps AS (
    SELECT date_trunc('day', l.created_at)::date AS day,
           NULLIF(s->>'rawCount', '')::int       AS raw_count,
           COALESCE((s->>'results')::int, 0)     AS kept_cnt,
           COALESCE((s->>'extractOk')::int, 0)     AS extract_ok,
           COALESCE((s->>'extractFailed')::int, 0) AS extract_failed
    FROM llm_call_logs l
    CROSS JOIN LATERAL jsonb_array_elements(l.steps_json::jsonb) AS s
    WHERE l.created_at >= now() - interval '30 days'
      AND s->>'kind' = 'web'
      AND l.steps_json LIKE '%"kind":"web"%'
)
SELECT day,
       COUNT(*)                                                       AS web_calls,
       COUNT(*) FILTER (WHERE kept_cnt = 0)                           AS empty_calls,
       ROUND(COUNT(*) FILTER (WHERE kept_cnt = 0)::numeric
             / NULLIF(COUNT(*), 0), 3)                                AS empty_rate,
       ROUND(AVG(CASE WHEN raw_count > 0
                      THEN kept_cnt::numeric / raw_count END), 3)     AS avg_keep_ratio,
       ROUND(SUM(extract_ok)::numeric
             / NULLIF(SUM(extract_ok + extract_failed), 0), 3)        AS extract_success
FROM web_steps
GROUP BY day
ORDER BY day;

-- ---------------------------------------------------------------------
-- ③ 空结果成因分布: 到底是"上游不给"还是"自己过滤太狠"
--
--    这是整份脚本最有用的一节。两种成因的处置方式完全相反:
--      no_results   —— 上游引擎问题(限流 / 服务异常 / 查询词过窄) → 查 SearXNG 与引擎配额
--      all_filtered —— 我们自己的清洗规则过严                → 查 SearchResultCleaner 的过滤规则
--      disabled     —— 配置开关被关(该告警而不是当成正常)
--      error        —— 请求异常(超时 / 连不上 / 非 JSON 响应)
-- ---------------------------------------------------------------------
WITH web_steps AS (
    SELECT COALESCE(s->>'emptyReason', '(正常)')    AS empty_reason,
           COALESCE(s->>'emptyReasonLabel', '有结果') AS empty_label,
           COALESCE((s->>'rawCount')::int, -1)      AS raw_count
    FROM llm_call_logs l
    CROSS JOIN LATERAL jsonb_array_elements(l.steps_json::jsonb) AS s
    WHERE l.created_at >= now() - interval '30 days'
      AND s->>'kind' = 'web'
      AND l.steps_json LIKE '%"kind":"web"%'
)
SELECT empty_reason,
       empty_label,
       COUNT(*)                                                 AS calls,
       ROUND(COUNT(*)::numeric / NULLIF(SUM(COUNT(*)) OVER (), 0), 3) AS share,
       ROUND(AVG(NULLIF(raw_count, -1)))                        AS avg_raw_count
FROM web_steps
GROUP BY empty_reason, empty_label
ORDER BY calls DESC;

-- ---------------------------------------------------------------------
-- ④ 清洗存活率异常: 上游给了不少, 我们几乎全扔掉
--
--    raw_count >= 10 且保留 <= 2 —— 这类查询最值得人工看一眼:
--    可能是清洗规则误伤(丢掉的是真实文章), 也可能是查询词本身就模糊
--    (引擎只能给聚合页)。后者应改进 query 构造提示词, 而不是改清洗规则。
-- ---------------------------------------------------------------------
SELECT (s->>'query')                        AS query,
       (s->>'rawCount')::int                AS raw_count,
       (s->>'results')::int                 AS kept_cnt,
       ROUND((s->>'results')::numeric
             / NULLIF((s->>'rawCount')::int, 0), 3) AS keep_ratio,
       l.created_at
FROM llm_call_logs l
CROSS JOIN LATERAL jsonb_array_elements(l.steps_json::jsonb) AS s
WHERE l.created_at >= now() - interval '30 days'
  AND s->>'kind' = 'web'
  AND s->>'tool' = 'web_search'
  AND (s->>'rawCount')::int >= 10
  AND (s->>'results')::int <= 2
  AND l.steps_json LIKE '%"kind":"web"%'
ORDER BY l.created_at DESC
LIMIT 100;

-- ---------------------------------------------------------------------
-- ⑤ 抓取质量: 哪些查询"抓成功了但基本没内容"
--
--    extract_ok > 0 且 thin_pages = extract_ok(全是薄页) —— 典型是登录页 / 验证码页 /
--    404 页 / 纯 JS 渲染页。这类结果在抓取成功率上是"成功的", 但对回答零贡献。
-- ---------------------------------------------------------------------
SELECT (s->>'query')                            AS query,
       (s->>'extractOk')::int                   AS extract_ok,
       (s->>'extractFailed')::int               AS extract_failed,
       (s->>'contentChars')::int                AS content_chars,
       (s->>'thinPages')::int                   AS thin_pages,
       ROUND((s->>'thinPages')::numeric
             / NULLIF((s->>'extractOk')::int, 0), 3) AS thin_ratio,
       l.created_at
FROM llm_call_logs l
CROSS JOIN LATERAL jsonb_array_elements(l.steps_json::jsonb) AS s
WHERE l.created_at >= now() - interval '30 days'
  AND s->>'kind' = 'web'
  AND s ? 'extractOk'
  AND (s->>'extractOk')::int > 0
  AND (s->>'thinPages')::int = (s->>'extractOk')::int
  AND l.steps_json LIKE '%"kind":"web"%'
ORDER BY l.created_at DESC
LIMIT 100;

-- ---------------------------------------------------------------------
-- ⑥ 空结果 / 低存活率的原始查询样本: 直接拿来扩评测集
--
--    挑其中有代表性的, 按 src/test/resources/eval/web/extract-golden.json 的格式
--    追加成 fixture 用例(把该 URL 的响应字节存下来) —— 评测集随真实使用增长,
--    是它最有价值的用法(与检索评测集的扩充方式一致)。
-- ---------------------------------------------------------------------
SELECT l.created_at,
       (s->>'tool')          AS tool,
       (s->>'query')         AS query,
       (s->>'rawCount')::int AS raw_count,
       (s->>'results')::int  AS kept_cnt,
       (s->>'emptyReasonLabel') AS reason
FROM llm_call_logs l
CROSS JOIN LATERAL jsonb_array_elements(l.steps_json::jsonb) AS s
WHERE l.created_at >= now() - interval '7 days'
  AND s->>'kind' = 'web'
  AND (
        COALESCE((s->>'results')::int, 0) = 0
     OR ((s->>'rawCount')::int >= 10 AND (s->>'results')::int <= 2)
      )
  AND l.steps_json LIKE '%"kind":"web"%'
ORDER BY l.created_at DESC
LIMIT 100;
