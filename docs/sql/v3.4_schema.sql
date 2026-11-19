-- =============================================================================
-- V3.4 上下文占用展示 + 历史对话摘要压缩
-- 全部为增量 DDL，均带 IF NOT EXISTS，可重复执行；不回填存量数据。
-- =============================================================================

-- =============================================================================
-- 上下文占用展示（V3.4）
--
-- 设计要点：
--   1. users.context_token_limit：用户自己设的「总上下文大小」，是占用百分比的分母，
--      也是"接近上限"告警的阈值依据。可空 = 未设置，跟随部署默认 agent.memory.context-token-limit(30 万)。
--      为什么不由部署统一配：分母应当是"我这个模型的窗口有多大"，那是用户手里的事实；
--      8k 小模型按 30 万算，占用永远 3%、告警永不触发；1M 大模型又会天天告警。
--   2. sessions.context_usage：最近一轮的占用快照(JSON)。真实占用只有"组装完这一轮上下文"
--      那一刻才知道(平台提示词 + 记忆召回 + RAG + 技能目录 + 本轮工具定义)，事后现算等于把
--      整条准备链路再跑一遍，而其中记忆召回、路由都有副作用和成本。
--      存快照的代价只有每轮一次 UPDATE，换来切会话/刷新后仍能看到上次那一轮用了多少。
--   3. 不回填存量：老会话没有快照是事实(当时确实没算)，补 0 反而会被读成"上下文是空的"。
--      界面按 available=false 显示空态，下一次对话自动覆盖。
-- =============================================================================

ALTER TABLE users ADD COLUMN IF NOT EXISTS context_token_limit INTEGER;

COMMENT ON COLUMN users.context_token_limit IS
    '用户设置的总上下文(token); NULL=跟随部署默认 agent.memory.context-token-limit';

ALTER TABLE sessions ADD COLUMN IF NOT EXISTS context_usage TEXT;

COMMENT ON COLUMN sessions.context_usage IS
    '最近一轮上下文占用快照(JSON, 结构见 dto.ContextUsage); 百分比由读取侧按当前上限重算';

-- =============================================================================
-- 历史对话摘要压缩（V3.4）
--
-- 设计要点：
--   1. 一个会话只有"当前这一份"摘要，每次压缩整体替换，所以是会话的两个属性而不是一张新表 ——
--      建历史表就要处理"哪一版生效"和清理，换不来任何东西。
--   2. context_summary_seq 是边界证明：组装上下文时只丢弃 seq <= 它的消息。
--      用户撤回/删除历史后，这条消息可能已不存在 → 说明摘要描述的是被删掉的内容，
--      整份作废（策略里有自愈判断），否则模型会"记得"用户明确撤回过的事。
--   3. 原文不删：摘要只影响"发给模型什么"，界面里的完整历史照旧从 messages 表读。
--      因此摘要本质是可丢弃的缓存，两列一起为空表示从未压缩过。
--   4. 存量不回填：老会话没有摘要就是没压过，保持 NULL 即可（下次超预算时会自然生成）。
-- =============================================================================

ALTER TABLE sessions ADD COLUMN IF NOT EXISTS context_summary TEXT;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS context_summary_seq INTEGER;

COMMENT ON COLUMN sessions.context_summary IS
    '历史对话压缩摘要; NULL=从未压缩。由 SummaryCompactionStrategy 维护，原文仍保留在 messages';
COMMENT ON COLUMN sessions.context_summary_seq IS
    '摘要覆盖到的最后一条消息 seq; 组装上下文时只丢弃 seq <= 它的消息。边界消息被删除即视为摘要失效';

-- =============================================================================
-- 上下文压缩事件（V3.5）
--
-- 设计要点：
--   1. 与 sessions.context_summary 分工不同：那两列是「当前生效的摘要」（覆盖即替换），
--      本表是「发生过的压缩事件」（只追加）。前者回答"模型现在记得什么"，
--      后者回答"它是什么时候、因为什么变成这样的" —— 用户报"会话突然忘事"时，
--      需要的正是后者的时间线，而覆盖式的摘要字段天然留不下。
--   2. 不并进 llm_call_logs：降级、摘要作废、用户清除这些事件根本没有模型调用，
--      混进去会出现没有 token、没有耗时的行，也会污染按来源统计的指标。
--   3. 会话删除时只把 session_id 置空、不删行：压缩频率与降级率是部署级观测数据，
--      不能由"会话只保留最近 N 条"的清理策略决定还能不能复盘。
--   4. event_type 取值见 CompactionEvent：compacted / degraded / invalidated / cleared / edited。
-- =============================================================================

CREATE TABLE IF NOT EXISTS context_compactions (
    id                VARCHAR(64) PRIMARY KEY,
    session_id        VARCHAR(64),
    user_id           VARCHAR(64),
    event_type        VARCHAR(24),
    reason            VARCHAR(512),
    trigger_tokens    INTEGER,
    before_tokens     INTEGER,
    after_tokens      INTEGER,
    summarized_count  INTEGER,
    kept_count        INTEGER,
    summary_chars     INTEGER,
    boundary_seq      INTEGER,
    model             VARCHAR(64),
    duration_ms       BIGINT,
    created_at        TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ctxcomp_session_time ON context_compactions (session_id, created_at);
CREATE INDEX IF NOT EXISTS idx_ctxcomp_user_time ON context_compactions (user_id, created_at);

COMMENT ON TABLE context_compactions IS
    '上下文压缩事件(一次压缩/降级/作废/清除/改写一条); 由 SummaryCompactionStrategy 与 SessionSummaryStore 写入';
COMMENT ON COLUMN context_compactions.event_type IS
    'compacted=压缩成功 / degraded=降级窗口 / invalidated=摘要作废 / cleared=用户清除 / edited=用户改写';
COMMENT ON COLUMN context_compactions.reason IS
    '降级或作废原因; 成功压缩为空';
COMMENT ON COLUMN context_compactions.session_id IS
    '会话 ID; 会话删除后置空(保留统计)';

-- =============================================================================
-- 前缀缓存可观测（llm_usage_logs 增加缓存读写 token）
--
-- 设计要点：
--   1. 与 input_tokens 是包含关系：缓存读的那部分已经算在输入里，只是计价更低。
--      因此 前缀命中率 = cache_read_tokens / input_tokens，不需要再加分母。
--   2. 0 的含义是「未观测到或未命中」：供应商未上报缓存明细时也落 0 —— 两件事在数据上
--      无法区分，这是既有事实，不要据此断言"缓存没生效"。要看是否支持，看该模型的原始响应。
--   3. 不回填存量：老记录当时确实没观测（NULL），补 0 会被读成"当时没命中"。
-- =============================================================================

ALTER TABLE llm_usage_logs ADD COLUMN IF NOT EXISTS cache_read_tokens INTEGER;

COMMENT ON COLUMN llm_usage_logs.cache_read_tokens IS
    '命中前缀缓存的输入 token 数; 0=未观测到或未命中, NULL=本次改动之前的存量记录';

ALTER TABLE llm_usage_logs ADD COLUMN IF NOT EXISTS cache_creation_tokens INTEGER;

COMMENT ON COLUMN llm_usage_logs.cache_creation_tokens IS
    '写入前缀缓存的输入 token 数; 仅 Anthropic 系上报, 其余为 0';

-- =============================================================================
-- 知识库分块策略
--
-- 设计要点：
--   1. 此前 chunk_size / chunk_overlap 虽在知识库表和界面上存在，但摄取时始终用全局
--      TextSplitter Bean，改这两个字段完全无效。本次补齐"按库配置"链路，并新增策略字段。
--   2. 存量行默认 recursive：与历史行为逐字一致（全局默认策略也是 recursive）。
--      不回填、不重切已有向量 —— 老片段仍按老规则切，需用户主动"重新入库"才变更。
--   3. 四种 code 与 ChunkStrategy 枚举、GET /api/knowledge-bases/splitter-options 一致。
-- =============================================================================

ALTER TABLE knowledge_bases ADD COLUMN IF NOT EXISTS chunk_strategy VARCHAR(24) NOT NULL DEFAULT 'recursive';

COMMENT ON COLUMN knowledge_bases.chunk_strategy IS
    '分块策略: fixed=固定长度 / recursive=递归字符 / sentence=句子优先 / markdown=标题结构; 空值回退全局 agent.rag.splitter.strategy';

-- =============================================================================
-- 会话级知识库挂载
--
-- 设计要点：
--   1. 知识库是"这个会话的工作资料"，与 sessions.model 同级，属于会话属性。
--      此前只存在前端组件状态里：刷新即丢（用户以为还在引用，实际已退回模型自主检索）、
--      且会从会话 A 串到会话 B。
--   2. 空值 = 未挂载任何知识库（不强制检索，检索交还给模型的 knowledge_base_search 工具）。
--   3. 用逗号分隔而非数组列：与代码里 Agent 定义的 kb_ids 存储口径一致，迁移成本最低。
-- =============================================================================

ALTER TABLE sessions ADD COLUMN IF NOT EXISTS kb_ids TEXT;

COMMENT ON COLUMN sessions.kb_ids IS
    '本会话挂载的知识库 ID（逗号分隔）；空=未挂载。挂上后该会话每轮强制检索，跨轮继承';
