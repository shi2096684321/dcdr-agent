-- ==========================================================================
-- V3.1 数据库增量脚本(归属 → 治理 → 长期记忆 → 专家与技能 → 回答反馈)
-- (2026-10-15 由多个分脚本合并而来; 各段落保留原文件头说明,
--  段落顺序 = 历史演进/依赖顺序; 全部语句幂等, 可重复执行)
-- ==========================================================================

-- ---------------------------------------------------------------------------
-- [1/5] 来源: v3.1_ownership_schema.sql
-- ---------------------------------------------------------------------------
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

-- ---------------------------------------------------------------------------
-- [2/5] 来源: v3.1_f44_governance_schema.sql
-- ---------------------------------------------------------------------------
-- ============================================================================
-- dcdr-agent V3.1 F-44 治理完善 (版本管理 + 成本分析) 数据库增量 (PostgreSQL 16 + pgvector)
-- 用途:
--   F-44.1 版本管理: agent_definition_versions (Agent 定义版本快照)
--                    + template_shares.source_version (发布钉住的版本号)
--   F-44.2 成本分析: 复用 V3.0 已建的 llm_usage_logs, 仅补区间聚合索引
--
-- 与既有脚本的关系:
--   - workflow_versions(工作流版本) 已由 v3.0_beta_schema.sql 建立, **本脚本不重复创建** ——
--     F-44.1 只补 Agent 侧的同构表, 工作流版本复用现有表(不迁移、不改名)
--   - 规划文档里写的 cost_records 并不存在: F-39 落地时用的就是 llm_usage_logs, 故不新建成本表
--
-- 执行顺序: 在 v3.0_beta_schema.sql 之后执行; 全部使用 IF NOT EXISTS, 可在已有库上重复执行
--
-- 注意: 应用侧 ddl-auto=update 会在启动时自动建表/加列, 因此本脚本主要面向生产 PG 的显式 DDL。
--       由于 CREATE TABLE IF NOT EXISTS 在"JPA 已建表"时是空操作, 外键约束不会自动补上
--       (JPA 无关系映射, 不生成 FK)—— 需要外键请在建表前先执行本脚本, 或手工 ALTER 补。
-- ============================================================================

-- 1. F-44.1 Agent 定义版本快照 (字段与 agent_definitions 的定义列一一对应)
CREATE TABLE IF NOT EXISTS agent_definition_versions (
    id            VARCHAR(64)  PRIMARY KEY,
    agent_id      VARCHAR(64)  REFERENCES agent_definitions(id) ON DELETE CASCADE,
    version       INT          NOT NULL,
    name          VARCHAR(255) NOT NULL,
    description   TEXT,
    avatar        VARCHAR(512),
    system_prompt TEXT,
    model         VARCHAR(128),
    tools         TEXT,
    kb_ids        TEXT,
    temperature   DECIMAL(3,2),
    is_public     BOOLEAN      NOT NULL DEFAULT false,
    is_template   BOOLEAN      NOT NULL DEFAULT false,
    change_note   VARCHAR(255),
    created_by    VARCHAR(64),
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    UNIQUE(agent_id, version)
);
CREATE INDEX IF NOT EXISTS idx_agentver_agent ON agent_definition_versions(agent_id);

-- 2. F-44.1 模板市场: 发布时钉住的源版本号
--    为空表示"按当前定义克隆"(预置模板 / 本特性上线前的存量分享记录), 行为与 V3.0 一致
ALTER TABLE template_shares ADD COLUMN IF NOT EXISTS source_version INT;

-- 3. F-44.2 成本报表: 区间 + 模型维度的聚合查询
--    已有的 idx_llmusage_userday(user_id, created_at) 覆盖"按用户+区间",
--    本索引覆盖"全库区间内按模型聚合"(报表的模型占比维度)
CREATE INDEX IF NOT EXISTS idx_llmusage_time_model ON llm_usage_logs(created_at, model);

-- ---------------------------------------------------------------------------
-- [3/5] 来源: v3.1_memory_schema.sql
-- ---------------------------------------------------------------------------
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

-- ---------------------------------------------------------------------------
-- [4/5] 来源: v3.1_expert_skill_schema.sql
-- ---------------------------------------------------------------------------
-- ============================================================================
-- dcdr-agent V3.1 专家体系与技能 数据库增量 (PostgreSQL 16 + pgvector)
--
-- 用途:
--   1. 专家元数据: agent_definitions 补 4 列 —— profession / category_id / tags /
--      quick_prompts。这是把"模板市场"升级为"专家市场"的前提: 有了分类与标签
--      才能按领域检索与推荐(原先只能按时间/克隆数排序)。
--   2. 技能: skill_definitions —— 纯文本能力包(指令 + 参考资源 + 工具依赖声明),
--      让模型按需加载领域知识, 而不是把所有知识常驻 system prompt。
--
-- 与既有脚本的关系:
--   - 不新建"专家表": 专家就是 agent_definitions(现有语义已覆盖 90%), 只补展示元数据。
--     新增一张表会导致 Agent 出现两套真相(克隆/版本/归属/工具白名单都要双写)。
--   - 技能与插件(F-37 PluginLoader)是两层, 不合并: 插件是**可执行代码**(JAR,
--     经字节码审计后注册成 Tool); 技能是**纯文本**(指令与参考), 不具备执行能力。
--     技能需要执行能力时只能"引用"已注册工具(required_tools), 不携带任何二进制。
--
-- 执行顺序: 在 v3.0_beta_schema.sql 之后执行; 全部 IF NOT EXISTS, 可在已有库重复执行。
--
-- 注意: 应用侧 ddl-auto=update 会自动建表/加列, 故本脚本主要面向生产 PG 的显式 DDL。
-- ============================================================================

-- 1. 专家元数据 (模板市场 → 专家市场)
--    profession     职业头衔, 专家卡片标题(如"资深法务")
--    category_id    行业分类, 用于市场分类筛选
--    tags           擅长领域标签, JSON 数组字符串(展示用, 固定 3 个)
--    quick_prompts  推荐问法, JSON 数组字符串(首条兼作开场引导)
ALTER TABLE agent_definitions ADD COLUMN IF NOT EXISTS profession    VARCHAR(128);
ALTER TABLE agent_definitions ADD COLUMN IF NOT EXISTS category_id   VARCHAR(64);
ALTER TABLE agent_definitions ADD COLUMN IF NOT EXISTS tags          TEXT;
ALTER TABLE agent_definitions ADD COLUMN IF NOT EXISTS quick_prompts TEXT;

-- 按分类筛选专家(市场页签的常用查询)
CREATE INDEX IF NOT EXISTS idx_agent_category ON agent_definitions(category_id);

-- 1.1 版本快照同步补列: agent_definition_versions 的字段必须与定义表"一一对应",
--     否则回滚会漏掉元数据, 出现"定义回滚了、专家卡片还是新的"这种半吊子状态。
ALTER TABLE agent_definition_versions ADD COLUMN IF NOT EXISTS profession    VARCHAR(128);
ALTER TABLE agent_definition_versions ADD COLUMN IF NOT EXISTS category_id   VARCHAR(64);
ALTER TABLE agent_definition_versions ADD COLUMN IF NOT EXISTS tags          TEXT;
ALTER TABLE agent_definition_versions ADD COLUMN IF NOT EXISTS quick_prompts TEXT;

-- 2. 技能定义 (纯文本能力包)
--    instructions    SKILL.md 正文 —— 渐进式披露的第二层, 仅在模型调用 skill_load 时才注入
--    resources_json  参考资源 {"相对路径": "文本内容"} —— 同样按需加载, 不常驻上下文
--    required_tools  依赖的已注册工具名(JSON 数组); 导入时校验存在性, 缺失只告警不阻断
--    has_executable  是否声明需要可执行能力 —— 本项目**不接受**技能自带脚本,
--                    该列只用于在界面上提示"执行能力走沙箱/插件", 不代表技能包里有代码
CREATE TABLE IF NOT EXISTS skill_definitions (
    id              VARCHAR(64)  PRIMARY KEY,
    user_id         VARCHAR(64)  REFERENCES users(id) ON DELETE CASCADE,
    name            VARCHAR(128) NOT NULL,
    display_name    VARCHAR(255),
    description     TEXT,
    version         VARCHAR(32),
    author          VARCHAR(128),
    category_id     VARCHAR(64),
    tags            TEXT,
    instructions    TEXT,
    resources_json  TEXT,
    required_tools  TEXT,
    has_executable  BOOLEAN      NOT NULL DEFAULT false,
    source          VARCHAR(32)  NOT NULL DEFAULT 'local',
    is_public       BOOLEAN      NOT NULL DEFAULT false,
    enabled         BOOLEAN      NOT NULL DEFAULT true,
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT now(),
    -- 同一用户下技能标识唯一: skill_load(name) 按名字加载, 同名会让加载目标产生歧义。
    -- 注意 PG 里 NULL 互不相等, 故内置技能(user_id 为 NULL)不受此约束保护,
    -- 由启动期的预置初始化用"唯一约束容错"兜住(见 SkillInitializer)。
    UNIQUE(user_id, name)
);
CREATE INDEX IF NOT EXISTS idx_skill_user    ON skill_definitions(user_id);
CREATE INDEX IF NOT EXISTS idx_skill_enabled ON skill_definitions(enabled);
CREATE INDEX IF NOT EXISTS idx_skill_category ON skill_definitions(category_id);

-- 3. 图标地址 (V3.1 技能市场引入时带上)
--    只存 URL, 不存图片字节 —— 与"技能是纯文本能力包"的边界一致:
--    技能表一旦出现二进制(哪怕是内联 base64), "技能包可以自由分享"的前提就没了。
--    代价是外链依赖: 市场图标指向上游 CDN, 失效时界面回落到首字母占位。
ALTER TABLE skill_definitions ADD COLUMN IF NOT EXISTS icon_url VARCHAR(512);

-- 4. 来源页地址 (V3.1 技能市场引入时带上)
--    用途单一: "正文待补全"时用户去这里取正文。以前该地址只写在占位正文里,
--    用户得先打开技能才看得到 —— 列表上因此没有补全入口。
--    注: "是否待补全"不是列, 由正文内容派生(见 SkillService.PENDING_SENTINEL), 老数据无需迁移。
ALTER TABLE skill_definitions ADD COLUMN IF NOT EXISTS source_url VARCHAR(512);

-- 5. 来源标识 (V3.1 技能市场引入时带上)
--    如 'skillhub:@clawhub_shawnpana/browser-use' —— 记录"这个技能从哪个外部技能来的"。
--    为什么不从正文里读: 引入时占位正文里确实写过来源标识, 但那行会随"用户补全正文"
--    被整段替换掉, 血缘随即永久丢失; 而恰恰是这些技能最需要回溯来源(正文是人工搬来的,
--    出问题要拿原文核对)。
--    注: 此列**只由导入/市场引入写入**(见 SkillService#markOrigin), 不放进用户可编辑的
--    表单 DTO —— 否则用户补全正文时的那次普通编辑会把它清成 NULL。
--    存量引入的技能此处为 NULL: 不做回填, 因为占位正文可能已被覆盖, 回填会把"当时也许有"
--    当成"确定是"来展示。
ALTER TABLE skill_definitions ADD COLUMN IF NOT EXISTS source_ref VARCHAR(255);

-- ---------------------------------------------------------------------------
-- [5/5] 来源: v3.1_feedback_schema.sql
-- ---------------------------------------------------------------------------
-- =====================================================================
-- V3.1 回答反馈收集 (F-48 点赞/点踩)
-- ---------------------------------------------------------------------
-- 用途: 收集用户对**单条助手回答**的评价 (点赞/点踩 + 原因标签 + 可选文字),
--       并快照该回答的技术上下文 (模型/工具/引用来源数), 供后续归因分析
--       (按模型/是否联网/是否走 RAG 聚合差评率、构造回归用例、沉淀示例库)。
--
-- 反馈主体是"一条回答", 通过 (session_id, message_seq) 定位 —— seq 是 messages 表
-- 的会话内序号, 前后端链路稳定 (MessageDTO.id 是运行时合成 ID, 不可作为持久键)。
--
-- 执行前提: 已执行 v2.0_schema.sql / v3.0*.sql
-- 注意: pg / H2 均由 JPA (ddl-auto=update) 在启动时自动补齐缺失的表与列, 本脚本无需手工执行。
--       但 JPA 不生成 COMMENT 与特殊索引, 需要这些特性时手工执行一次本脚本补齐即可
--       (全部语句为 IF NOT EXISTS, 可重复执行)。
-- =====================================================================

CREATE TABLE IF NOT EXISTS message_feedback (
    id            BIGSERIAL    PRIMARY KEY,
    session_id    VARCHAR(64)  NOT NULL,                      -- 会话 ID (messages.session_id)
    message_seq   INT          NOT NULL,                      -- 会话内消息序号 (messages.seq), 定位被评价的回答
    user_id       VARCHAR(64)  NOT NULL DEFAULT 'anonymous',  -- 反馈人; 认证关闭(单用户模式)时写 anonymous 哨兵值, 保证唯一约束可用
    rating        VARCHAR(8)   NOT NULL,                      -- up = 点赞, down = 点踩
    reason_tags   VARCHAR(255),                               -- 点踩原因标签 code, 逗号分隔 (见 FeedbackService.REASON_TAGS)
    comment       TEXT,                                       -- 用户补充说明 (可选)
    -- ===== 回答侧上下文快照 (提交时从 messages 冗余, 避免回答被截断/删除后无法归因) =====
    model         VARCHAR(64),                                -- 生成该回答的模型
    tools_used    VARCHAR(2000),                              -- 本轮调用的工具 (JSON 字符串)
    source_count  INT          NOT NULL DEFAULT 0,            -- 引用来源条数 (RAG 片段/网页)
    web_search    BOOLEAN      NOT NULL DEFAULT false,        -- 是否走了联网检索
    message_chars INT          NOT NULL DEFAULT 0,            -- 回答正文长度 (字符, 用于"太啰嗦/太短"归因)
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT now()
);

-- 同一用户对同一条回答只保留一条反馈 (重复提交即更新, 便于用户改主意)
CREATE UNIQUE INDEX IF NOT EXISTS uk_feedback_msg_user
    ON message_feedback(session_id, message_seq, user_id);

-- 会话删除 / 历史截断时的级联清理
CREATE INDEX IF NOT EXISTS idx_feedback_session ON message_feedback(session_id, message_seq);
-- 看板聚合 (按时间窗口 + 好评/差评)
CREATE INDEX IF NOT EXISTS idx_feedback_created ON message_feedback(created_at);
CREATE INDEX IF NOT EXISTS idx_feedback_rating  ON message_feedback(rating);
CREATE INDEX IF NOT EXISTS idx_feedback_user    ON message_feedback(user_id);

-- ---------------------------------------------------------------------------
-- [追加 2026-10-15] 来源: v3.2_rag_hybrid_schema.sql
-- 混合检索(向量 + BM25/RRF)已随 V3.1 交付且随仓默认开启(agent.rag.retrieval.hybrid.enabled=true),
-- 其索引必须随建库创建 —— 故这段 DDL 并入 v3.1, 不再以 v3.2 名义单独存在
-- (此前它不进 initdb, 全新部署会漏掉这个索引, 混合检索退化为顺序扫描)。
-- ---------------------------------------------------------------------------
-- ============================================================
-- V3.2 框架化: 混合检索(BM25 全文)可选索引(增量, 非破坏)
-- ------------------------------------------------------------
-- 仅当 agent.rag.retrieval.hybrid.enabled=true 时才需要执行。
-- 纯向量检索默认关闭混合, 无需此索引即可正常运行。
-- ============================================================

-- 全文检索所需扩展 + content 的 trigram GIN 索引,
-- 用于 document_chunks 的 to_tsvector 加速(ts_vector 表达式等价)
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- content 列 trigram 索引(支持 LIKE/trigram 匹配; BM25 走 to_tsvector 等价 GIN)
CREATE INDEX IF NOT EXISTS idx_chunk_content_trgm
    ON document_chunks USING gin (content gin_trgm_ops);