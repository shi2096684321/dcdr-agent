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

-- ---------------------------------------------------------------------
-- 2.1 长期记忆的写入来源 (V3.1: 对话中"记住 X" 与自动抽取先后引入)
-- ---------------------------------------------------------------------
-- 记忆有三条写入路径, 其中两条用户没亲手打字:
--   MANUAL    = 用户在记忆页新增;
--   REQUESTED = 用户在对话里明确要求记录(经 memory 工具);
--   AUTO      = 平台自动抽取(每 N 轮跑一次)。
-- 不区分的话, "AI 自己提炼的"和"我要求记的"会混在同一个列表里, 后者看起来像是失败了 ——
-- 而这恰恰是用户最容易提出、最难自查的疑问("我说了记住, 怎么没记")。
--
-- 可空且**不做回填**: 该列是后加的。存量行的归因由读取侧按 source_session_id 兜底
--   (有来源会话 = AI 在对话中写入; 没有 = 手动新增) —— 回填等于把"当时大概如此"
--   写成"确定如此", 而这条兜底规则本来就是确定的, 老数据不猜也能显示正确。
ALTER TABLE long_term_memories ADD COLUMN IF NOT EXISTS source_kind VARCHAR(16);

COMMENT ON COLUMN long_term_memories.source_kind IS 'MANUAL=记忆页新增 / REQUESTED=对话中用户要求 / AUTO=平台自动抽取; NULL=存量数据(按 source_session_id 兜底)';

-- ---------------------------------------------------------------------
-- 3. 用户级「自动记忆」开关 (V3.1 P1)
-- ---------------------------------------------------------------------
-- AI 自动抽取记忆引入的同意机制: 配置 agent.memory.auto-extract.enabled 是部署方的能力闸门
-- (决定要不要付这笔模型费用), 而"要不要让 AI 替我记东西"是用户自己的事 —— 两者都满足才生效。
-- 参考 CodeBuddy 的记忆设计: 它同样有"AI 自动识别并保存" + 一个可全局关闭的开关。
--
-- 用**可空**布尔表达三态:
--   NULL  = 用户未表态 → 跟随配置 agent.memory.auto-extract.user-default
--   TRUE  = 用户明确开启
--   FALSE = 用户明确关闭
-- 若用 NOT NULL DEFAULT false, 那么部署方日后把 user-default 改为开启时, 从未表态的用户
-- 会被当成"明确拒绝"而永远拿不到新默认 —— 用户的选择只该在用户真的选择过时才被记下来。
ALTER TABLE users ADD COLUMN IF NOT EXISTS auto_memory_enabled BOOLEAN;

COMMENT ON COLUMN users.auto_memory_enabled IS 'V3.1: 是否允许 AI 自动抽取长期记忆 (NULL=未表态, 跟随 agent.memory.auto-extract.user-default)';
