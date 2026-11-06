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
