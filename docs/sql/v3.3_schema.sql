-- =============================================================================
-- V3.3 F-49 工作区（Workspace）
-- 一个被用户显式授权给模型的目录 + 一组访问策略。
--
-- 设计要点：
--   1. root_path 存的是 toRealPath() 解析后的真实绝对路径，不是用户输入的原始串。
--      创建时算一次，之后只做比对；也避免 D:/work/../work 这类等价路径重复建记录。
--   2. access_level 分三档：READ / WRITE / FULL。
--      读授权不等于删授权 —— 想删必须显式升到 FULL，档位由用户在界面上选，模型无权影响。
--   3. 删除工作区只删本表记录，绝不触碰磁盘文件。
--
-- 说明：不建文件索引表、不建版本表。撤回所需的快照与变更日志都落在磁盘上
--      （<data>/workspace-trash/<workspaceId>/blobs + journal），不依赖数据库 ——
--      数据库挂了用户仍能手工把文件捞回来，这个冗余是故意的。
-- =============================================================================

CREATE TABLE IF NOT EXISTS workspaces (
    id           VARCHAR(64)   PRIMARY KEY,
    user_id      VARCHAR(64)   NOT NULL,
    name         VARCHAR(128)  NOT NULL,
    root_path    VARCHAR(1024) NOT NULL,
    access_level VARCHAR(16)   NOT NULL DEFAULT 'READ',
    created_at   TIMESTAMP     NOT NULL,
    updated_at   TIMESTAMP     NOT NULL
);

COMMENT ON TABLE  workspaces              IS '工作区：被用户授权给模型的目录';
COMMENT ON COLUMN workspaces.root_path    IS '解析后的真实绝对路径';
COMMENT ON COLUMN workspaces.access_level IS 'READ 只读 / WRITE 可写 / FULL 可写可删';

CREATE INDEX IF NOT EXISTS idx_workspace_user ON workspaces (user_id);

-- 会话与工作区绑定。
-- 绑定到会话而不是用户：同一个用户开两个会话可以分别操作不同目录，
-- 避免"选过一次之后所有会话都能读"的权限隐式扩散。
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS workspace_id VARCHAR(64);

-- =============================================================================
-- F-50 定时任务执行结果推送（agent.notify.*）
-- 任务跑完后把执行结果推送到企业微信群机器人 / 钉钉群机器人。
-- 企业微信这条配合「我的企业 → 微信插件」，用个人微信扫码关注后可直接在个人微信收到。
--
-- 设计要点：
--   1. 开关分两层：全局 agent.notify.enabled（部署方决定是否开这个能力）
--      + 任务级 notify_enabled（用户决定这个任务要不要推）。两层都开才发。
--   2. notify_channel 存渠道 code（wecom_bot / dingtalk_bot），留空表示"跟随全局默认"。
--      存 code 而不是枚举名，是为了将来枚举改名不影响存量数据。
--   3. notify_last_* 只记录推送结果，与任务本身的 last_status 完全独立
--      —— 推送失败不等于任务失败，混在一起会让用户误以为 Agent 跑挂了。
--   4. 加 NOT NULL 列时带 DEFAULT：ddl-auto=update 给存量表加无默认值的非空列会失败。
-- =============================================================================

ALTER TABLE scheduled_tasks ADD COLUMN IF NOT EXISTS notify_enabled     BOOLEAN     NOT NULL DEFAULT FALSE;
ALTER TABLE scheduled_tasks ADD COLUMN IF NOT EXISTS notify_channel     VARCHAR(32);
ALTER TABLE scheduled_tasks ADD COLUMN IF NOT EXISTS notify_last_status VARCHAR(16);
ALTER TABLE scheduled_tasks ADD COLUMN IF NOT EXISTS notify_last_error  TEXT;
ALTER TABLE scheduled_tasks ADD COLUMN IF NOT EXISTS notify_last_at     TIMESTAMP;

COMMENT ON COLUMN scheduled_tasks.notify_enabled     IS '执行完成后是否推送结果';
COMMENT ON COLUMN scheduled_tasks.notify_channel     IS '通知渠道 code，如 wecom_bot / dingtalk_bot；NULL=跟随全局默认';
COMMENT ON COLUMN scheduled_tasks.notify_last_status IS '最近一次推送状态：success / failed / skipped';
COMMENT ON COLUMN scheduled_tasks.notify_last_error  IS '最近一次推送失败或跳过的原因';
COMMENT ON COLUMN scheduled_tasks.notify_last_at     IS '最近一次推送时间';

-- -----------------------------------------------------------------------------
-- F-50 单次执行记录
-- 为什么需要：scheduled_tasks 只留 last_status / last_error，完整输出执行完就丢了，
-- 于是推送里"查看详情"的链接无处可指。抓取类任务（读几篇文章汇总成长文）尤其需要：
-- IM 里只放 300 字概览，全文落在这里，通过一个免登录 share_token 打开。
--
-- 为什么不复用消息表：runOnce 执行完会立刻删除临时会话，消息随之消失，
-- 且消息表没有"分享 token / 过期时间"这类语义。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS scheduled_task_runs (
    id               VARCHAR(64)  PRIMARY KEY,
    task_id          VARCHAR(64)  NOT NULL,
    user_id          VARCHAR(64),
    status           VARCHAR(16),
    output           TEXT,
    error            TEXT,
    duration_ms      BIGINT       DEFAULT 0,
    started_at       TIMESTAMP,
    finished_at      TIMESTAMP,
    share_token      VARCHAR(64)  UNIQUE,
    share_expires_at TIMESTAMP,
    created_at       TIMESTAMP    NOT NULL
);

COMMENT ON TABLE  scheduled_task_runs               IS '定时任务单次执行记录（详情页的数据来源）';
COMMENT ON COLUMN scheduled_task_runs.output        IS 'Agent 完整输出，可能数万字';
COMMENT ON COLUMN scheduled_task_runs.share_token   IS '免登录访问凭证（32 位十六进制，不可猜测）';
COMMENT ON COLUMN scheduled_task_runs.share_expires_at IS '分享链接过期时间；过期后详情页返回 404';

CREATE INDEX IF NOT EXISTS idx_run_task    ON scheduled_task_runs (task_id);
CREATE INDEX IF NOT EXISTS idx_run_created ON scheduled_task_runs (created_at);

-- =============================================================================
-- 定时任务增强 + 用户级推送配置（V3.3 补丁；原注释标为 V3.4，实际已并入 3.3.0）
--
-- 设计要点：
--   1. scheduled_tasks.model：任务可显式指定执行模型，优先级高于 Agent 配置的模型
--      （见 ChatRequest#modelLocked）。NULL = 跟随 Agent 配置 / 平台默认。
--   2. user_notify_configs：每个用户自己维护机器人 Webhook，一套部署下的任务结果
--      可以进各自的群，而不是全公司共用一个群。
--   3. Webhook 地址里带机器人 key/token，属凭据：落库为 AES-GCM 密文
--      （复用 ApiKeyCipher，主密钥 MODEL_API_KEY_SECRET），对外只回显掩码。
--   4. (user_id, channel) 唯一：一个用户在一个渠道上只保留一份配置，
--      既是业务约束也天然按用户隔离，不存在跨用户读取路径。
--   5. 服务端会按用户填的地址发请求，因此地址要在应用层过 SSRF 白名单
--      （仅 https + 官方域名 + 443），数据库层面不做校验。
-- =============================================================================

ALTER TABLE scheduled_tasks ADD COLUMN IF NOT EXISTS model VARCHAR(128);

COMMENT ON COLUMN scheduled_tasks.model IS '执行模型名；NULL=跟随 Agent 配置或平台默认，非空时优先级最高';

CREATE TABLE IF NOT EXISTS user_notify_configs (
    id                     VARCHAR(64)  PRIMARY KEY,
    user_id                VARCHAR(64)  NOT NULL,
    channel                VARCHAR(32)  NOT NULL,
    webhook_url_encrypted  VARCHAR(4096),
    secret_encrypted       VARCHAR(4096),
    keyword                VARCHAR(128),
    enabled                BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at             TIMESTAMP    NOT NULL,
    updated_at             TIMESTAMP    NOT NULL,
    CONSTRAINT uk_unc_user_channel UNIQUE (user_id, channel)
);

COMMENT ON TABLE  user_notify_configs                       IS '用户级推送配置：各用户自己的机器人 Webhook';
COMMENT ON COLUMN user_notify_configs.webhook_url_encrypted IS 'Webhook 地址（AES-GCM 密文，不存明文）';
COMMENT ON COLUMN user_notify_configs.secret_encrypted      IS '钉钉加签密钥（AES-GCM 密文）';
COMMENT ON COLUMN user_notify_configs.keyword               IS '钉钉自定义关键词（非凭据，明文）';
COMMENT ON COLUMN user_notify_configs.enabled               IS '关闭后回退部署级 agent.notify.* 配置';

CREATE INDEX IF NOT EXISTS idx_unc_user ON user_notify_configs (user_id);


-- =============================================================================
-- 会话来源分类 + 调用链来源(并入本脚本, 保持"一版一脚本"; 不再单开文件)
-- =============================================================================
-- ==========================================================================
--  V3.3 会话来源分类 (sessions.source / sessions.source_ref)
-- --------------------------------------------------------------------------
--  背景: 定时任务与工作流每执行一次都会建一个会话跑 Agent, 这些会话以前和用户
--        自己聊的对话混在同一个列表里。加两个字段后:
--          · 对话侧边栏只查 source='user'
--          · 「自动化 → 任务对话」查 source IN ('schedule','workflow')
--          · 自动会话按 (source, source_ref) 收敛保留数量, 不会被分钟级 cron 撑爆
--
--  说明: 应用侧 ddl-auto=update 会自动加列, 本脚本用于显式部署 / 手工建库场景。
--        全部 IF NOT EXISTS, 可重复执行。
-- ==========================================================================

ALTER TABLE sessions ADD COLUMN IF NOT EXISTS source VARCHAR(32) NOT NULL DEFAULT 'user';
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS source_ref VARCHAR(64);

-- 存量会话(加列前创建的)全部视为用户自己的对话 —— 与列的 DEFAULT 一致,
-- 显式写一遍是为了让"升级后对话列表不会突然空掉"这件事在脚本里可见。
UPDATE sessions SET source = 'user' WHERE source IS NULL;

-- 存量里混在对话列表中的自动执行会话: 靠旧版标题前缀(schedule: / workflow:)认出来并改归来源。
-- 只能认回前缀还在的那部分 —— 首个用户问题到达后标题会被改写成问题内容, 前缀就丢了,
-- 这类会话升级后仍留在对话列表里, 需要用户手动删除。
-- source_ref 无法反推任务/工作流 ID(旧版没存), 留空: 「任务对话」里会少一行"来自任务 xxx", 其余照常。
UPDATE sessions SET source = 'schedule' WHERE source = 'user' AND title LIKE 'schedule:%';
UPDATE sessions SET source = 'workflow' WHERE source = 'user' AND title LIKE 'workflow:%';

COMMENT ON COLUMN sessions.source IS
    '会话来源: user=用户自己开的对话; schedule=定时任务自动执行; workflow=工作流步骤自动执行';
COMMENT ON COLUMN sessions.source_ref IS
    '来源聚合 ID: 定时任务 ID 或工作流 ID; 自动会话按它收敛保留条数, 用户会话为空';

-- 列表查询按 (user_id, source) 过滤并按 updated_at 倒序; 收敛清理按 (source, source_ref)
CREATE INDEX IF NOT EXISTS idx_session_user_source ON sessions (user_id, source);
CREATE INDEX IF NOT EXISTS idx_session_source_ref ON sessions (source, source_ref);

-- ===== 调用链日志补来源列 =====
-- 为什么要补: 定时任务与工作流 V3.3 起也建会话, 于是"有没有 session_id"再也分不出
-- 这条日志是用户自己聊的还是系统跑的; 而失败排查时"这条是不是定时任务"是第一个要回答的问题。
-- 取值与 sessions.source 共用同一套 code, 不另造枚举。
ALTER TABLE llm_call_logs ADD COLUMN IF NOT EXISTS source VARCHAR(16) DEFAULT 'user';

-- 存量行: V3.3 之前只有用户对话会写这张表, 回填 user 是事实还原, 不是猜测。
-- 不依赖 ALTER 的 DEFAULT 自动填充 —— 那只在部分数据库/版本上对存量行生效。
UPDATE llm_call_logs SET source = 'user' WHERE source IS NULL;

-- 列表页按用户 + 来源筛选
CREATE INDEX IF NOT EXISTS idx_llmcall_user_source ON llm_call_logs (user_id, source, created_at);

-- =============================================================================
-- 定时任务"执行中"可见（V3.3 补丁）
--
-- 设计要点：
--   1. 执行记录改为"开始即落 running 行，结束 update 同一行"。
--      原先只在执行结束后 insert，执行过程中「执行历史」里一行都没有 ——
--      用户点了定时任务只能干等，分不清"在跑"还是"根本没触发"。
--   2. scheduled_task_runs.session_id：执行中的记录没有正文可看，唯一能证明它在跑的是
--      那次执行的会话（用户消息在调模型前就已落库）。要把用户从"执行中"这一行
--      直接送到「任务对话」，必须知道是哪条会话；会话表只有 task_id，
--      一个任务多条会话，反查不出正在跑的那条。
--   3. 存量行的 status 只有 success / failed，语义不变，无需回填。
-- =============================================================================

ALTER TABLE scheduled_task_runs ADD COLUMN IF NOT EXISTS session_id VARCHAR(64);

COMMENT ON COLUMN scheduled_task_runs.session_id IS
    '本次执行对应的会话 ID（「自动化 → 任务对话」可直达）';
COMMENT ON COLUMN scheduled_task_runs.status IS
    '执行状态: running=执行中(记录已落库, 正文尚未回写); success / failed=已结束';

-- 一个任务同时最多只有一条 running 行，按 (task_id, status) 找"当前是否在跑"足够便宜
CREATE INDEX IF NOT EXISTS idx_run_task_status ON scheduled_task_runs (task_id, status);
