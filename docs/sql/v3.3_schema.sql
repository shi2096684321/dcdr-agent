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
