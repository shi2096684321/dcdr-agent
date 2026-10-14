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
