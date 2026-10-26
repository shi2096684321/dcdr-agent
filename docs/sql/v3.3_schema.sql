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
