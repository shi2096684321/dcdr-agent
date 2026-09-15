-- =====================================================================
-- V3.1 长期记忆 (Long-Term Memory)
-- ---------------------------------------------------------------------
-- 用途: 跨会话沉淀"关于用户"的稳定信息 (画像/偏好/纠正/项目/外部资源指引),
--       在后续对话中作为背景注入, 避免用户反复说明同一件事。
--
-- 与既有"短期记忆"的分工:
--   - 短期记忆 = messages 表 + InMemoryChatMemory 滑窗 (会话内上下文, 已实现);
--   - 长期记忆 = 本表 (跨会话提炼出的结论性条目)。
--   两者语义不同, 故分表存储, 互不影响。
--
-- 作用域 (scope) 采用"用户为主干 + Agent 为叠加维度":
--   - USER  : agent_id IS NULL, 跨 Agent 通用画像, 换 Agent 不丢;
--   - AGENT : agent_id 非空, 该 Agent 专属习惯。
--   召回时两级合并, 冲突时局部(AGENT)优先。
--
-- 本版本 (P0) 不引入向量: 召回走「常驻(pinned/高重要度) + 条件触发(topic/tags)」。
-- 待单用户条目规模突破阈值后再增量增加 embedding 列与向量召回 (P2)。
--
-- 执行前提: 已执行 v2.0_schema.sql / v3.0*.sql / v3.1*.sql
-- 注意: pg / H2 均由 JPA (ddl-auto=update) 在启动时自动补齐缺失的表与列, 本脚本无需手工执行。
--       但 JPA 不生成 COMMENT 与部分索引 (WHERE agent_id IS NOT NULL), 需要这些特性时
--       手工执行一次本脚本补齐即可 (全部语句为 IF NOT EXISTS, 可重复执行)。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 会话补 agent_id: 记录"该会话由哪个自定义 Agent 承载"
-- ---------------------------------------------------------------------
-- 原有缺口: agentId 仅在请求中临时传入 (ChatRequest.agentId) 做配置覆盖, 从不落库,
-- 导致同一会话内切换 Agent 后无法归因历史消息, Agent 级记忆也无归属依据。
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS agent_id VARCHAR(64);

COMMENT ON COLUMN sessions.agent_id IS 'V3.1: 本会话使用的自定义 Agent ID (NULL = 未指定/通用助手)';

CREATE INDEX IF NOT EXISTS idx_session_agent ON sessions(agent_id);

-- ---------------------------------------------------------------------
-- 2. 长期记忆主表
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS long_term_memories (
    id                VARCHAR(64)  PRIMARY KEY,
    user_id           VARCHAR(64)  NOT NULL,                     -- 归属用户 (主干, 必填)
    agent_id          VARCHAR(64),                               -- NULL = 用户级通用记忆; 非空 = 该 Agent 专属
    scope             VARCHAR(16)  NOT NULL,                     -- USER | AGENT
    type              VARCHAR(24)  NOT NULL,                     -- 见 MemoryType 枚举
    topic             VARCHAR(64),                               -- 条件触发键 (对齐规则的 paths 语义)
    content           TEXT         NOT NULL,                     -- 结论性信息, 非原始日志流
    tags              VARCHAR(512),                              -- 逗号分隔, 支持组合查询
    importance        NUMERIC(3,2) NOT NULL DEFAULT 0.50,        -- 0.00~1.00, 决定注入优先级
    pinned            BOOLEAN      NOT NULL DEFAULT false,       -- 常驻注入 (每轮必带)
    source_session_id VARCHAR(64),                               -- 来源会话 (溯源)
    source_msg_seq    INT,                                       -- 来源消息序号 (溯源)
    hit_count         INT          NOT NULL DEFAULT 0,           -- 被召回次数 (淘汰依据)
    last_hit_at       TIMESTAMP,                                 -- 最近召回时间
    expires_at        TIMESTAMP,                                 -- 时效性记忆到期自动失效 (NULL = 永久)
    status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',    -- ACTIVE | ARCHIVED | DELETED
    created_at        TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP    NOT NULL DEFAULT now()
);

COMMENT ON TABLE  long_term_memories IS 'V3.1 长期记忆条目 (跨会话, 按用户/Agent 作用域分层)';
COMMENT ON COLUMN long_term_memories.scope      IS 'USER=用户级通用 (agent_id 为空) / AGENT=该 Agent 专属';
COMMENT ON COLUMN long_term_memories.status     IS 'ACTIVE 参与召回 / ARCHIVED 归档不召回 / DELETED 软删除 (用户可在记忆页彻底清除)';
COMMENT ON COLUMN long_term_memories.expires_at IS '时效性记忆到期后不再召回; NULL 表示永久有效';

-- 召回主路径: 按用户 + 作用域 + 活跃状态过滤
CREATE INDEX IF NOT EXISTS idx_memory_user  ON long_term_memories(user_id, scope, status);
-- Agent 级记忆按 Agent 聚合 (仅索引非空值, 用户级记忆不占空间)
CREATE INDEX IF NOT EXISTS idx_memory_agent ON long_term_memories(agent_id) WHERE agent_id IS NOT NULL;
-- 记忆管理页按时间倒序展示
CREATE INDEX IF NOT EXISTS idx_memory_created ON long_term_memories(user_id, created_at DESC);
