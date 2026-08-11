-- ============================================================================
-- dcdr-agent V2.0 数据库 schema (PostgreSQL 16 + pgvector 插件)
-- 用途: V2.0/V2.1 生产级 RAG 向量库 + V2.2 用户认证隔离
-- 启用方式: 在 pg profile 下使用 (application-pg.yml)
-- 执行顺序: pgvector 扩展由 docker-compose 的 00_install_pgvector.sh 安装,
--           本文件通过 CREATE EXTENSION 激活向量能力 (插件方式, 非定制镜像)
-- ============================================================================

-- 1. 启用 pgvector 扩展 (向量检索核心, 由插件提供)
CREATE EXTENSION IF NOT EXISTS vector;

-- 2. V2.0 新增: 用户表 (V2.2 认证/隔离使用)
CREATE TABLE IF NOT EXISTS users (
    id               VARCHAR(64)  PRIMARY KEY,
    username         VARCHAR(64)  NOT NULL UNIQUE,
    password_hash    VARCHAR(255) NOT NULL,
    email            VARCHAR(255),
    role             VARCHAR(32)  NOT NULL DEFAULT 'user',
    status           VARCHAR(16)  NOT NULL DEFAULT 'active',
    created_at       TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT now()
);

-- 3. V2.0 新增: 知识库表
CREATE TABLE IF NOT EXISTS knowledge_bases (
    id               VARCHAR(64)  PRIMARY KEY,
    user_id          VARCHAR(64)  REFERENCES users(id) ON DELETE CASCADE,
    name             VARCHAR(255) NOT NULL,
    description      TEXT,
    embedding_model  VARCHAR(128) NOT NULL DEFAULT 'text-embedding-v3',
    chunk_size       INT          NOT NULL DEFAULT 1000,
    chunk_overlap    INT          NOT NULL DEFAULT 200,
    doc_count        INT          NOT NULL DEFAULT 0,
    status           VARCHAR(16)  NOT NULL DEFAULT 'ready',
    visibility       VARCHAR(16)  NOT NULL DEFAULT 'USER',
    created_at       TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_kb_user ON knowledge_bases(user_id);
CREATE INDEX IF NOT EXISTS idx_kb_visibility ON knowledge_bases(visibility);
-- 已有部署升级: 补 visibility 列(PG 9.6+ 支持 IF NOT EXISTS)
ALTER TABLE knowledge_bases ADD COLUMN IF NOT EXISTS visibility VARCHAR(16) NOT NULL DEFAULT 'USER';

-- 4. V2.0 新增: 文档表
CREATE TABLE IF NOT EXISTS documents (
    id               VARCHAR(64)  PRIMARY KEY,
    kb_id            VARCHAR(64)  REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    user_id          VARCHAR(64)  REFERENCES users(id) ON DELETE CASCADE,
    filename         VARCHAR(512) NOT NULL,
    file_type        VARCHAR(32),
    file_size        BIGINT,
    chunk_count      INT          NOT NULL DEFAULT 0,
    status           VARCHAR(16)  NOT NULL DEFAULT 'pending',
    error_msg        TEXT,
    created_at       TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_doc_kb    ON documents(kb_id);
CREATE INDEX IF NOT EXISTS idx_doc_user  ON documents(user_id);

-- 5. V2.0 新增: 文档分块 + 向量 (RAG 检索核心)
CREATE TABLE IF NOT EXISTS document_chunks (
    id               VARCHAR(64)  PRIMARY KEY,
    doc_id           VARCHAR(64)  REFERENCES documents(id) ON DELETE CASCADE,
    kb_id            VARCHAR(64)  REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    user_id          VARCHAR(64)  REFERENCES users(id) ON DELETE CASCADE,
    chunk_index      INT          NOT NULL,
    content          TEXT         NOT NULL,
    embedding        VECTOR(1536),
    token_count      INT,
    created_at       TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_chunk_doc   ON document_chunks(doc_id);
CREATE INDEX IF NOT EXISTS idx_chunk_kb    ON document_chunks(kb_id);
-- 向量近似检索索引 (余弦距离)
CREATE INDEX IF NOT EXISTS idx_chunk_embedding
    ON document_chunks USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

-- 6. V2.0 新增: 智能体定义表 (V2.2 自定义 Agent)
CREATE TABLE IF NOT EXISTS agent_definitions (
    id               VARCHAR(64)  PRIMARY KEY,
    user_id          VARCHAR(64)  REFERENCES users(id) ON DELETE CASCADE,
    name             VARCHAR(255) NOT NULL,
    description      TEXT,
    system_prompt    TEXT,
    model            VARCHAR(128),
    tools            TEXT,
    kb_ids           TEXT,
    is_public        BOOLEAN      NOT NULL DEFAULT false,
    created_at       TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_agent_user ON agent_definitions(user_id);

-- 7. V2.0 新增: 审计日志表
CREATE TABLE IF NOT EXISTS audit_logs (
    id               VARCHAR(64)  PRIMARY KEY,
    user_id          VARCHAR(64),
    action           VARCHAR(128) NOT NULL,
    resource_type    VARCHAR(64),
    resource_id      VARCHAR(64),
    detail           TEXT,
    ip               VARCHAR(64),
    created_at       TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_audit_user  ON audit_logs(user_id);
CREATE INDEX IF NOT EXISTS idx_audit_time  ON audit_logs(created_at);

-- 8. 会话表 (对话模式; V1.x 存量表, V2.0 增加 user_id 用于认证隔离)
--    CREATE TABLE IF NOT EXISTS 兼容存量: 已存在则跳过, user_id 列由 ALTER 兜底补上
CREATE TABLE IF NOT EXISTS sessions (
    id               VARCHAR(64)  PRIMARY KEY,
    title            VARCHAR(255) NOT NULL,
    title_auto       BOOLEAN      NOT NULL DEFAULT true,
    model            VARCHAR(128),
    system_prompt    TEXT,
    user_id          VARCHAR(64),
    created_at       TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT now()
);
-- 兼容 V1.x 存量: 若 sessions 已存在但缺少 user_id 列, 补上
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS user_id VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id);

-- 9. 创作项目表 (V1.x 创作模式)
CREATE TABLE IF NOT EXISTS projects (
    id                VARCHAR(64)  PRIMARY KEY,
    name              VARCHAR(255) NOT NULL,
    mode              VARCHAR(16)  NOT NULL,
    default_model     VARCHAR(64),
    generation_count  INT          NOT NULL DEFAULT 0,
    created_at        TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP    NOT NULL DEFAULT now()
);

-- 10. 消息表 (V1.x 对话历史; 字段下划线命名, 对应 MessageEntity 驼峰字段)
CREATE TABLE IF NOT EXISTS messages (
    id                  BIGSERIAL    PRIMARY KEY,
    session_id          VARCHAR(64)  NOT NULL,
    seq                 INT          NOT NULL,
    role                VARCHAR(16)  NOT NULL,
    type                VARCHAR(16)  NOT NULL,
    content             TEXT,
    reasoning_content   TEXT,
    images              TEXT,
    vision_model        VARCHAR(64),
    image_status        VARCHAR(16),
    image_caption       TEXT,
    image_token_usage   VARCHAR(128),
    model               VARCHAR(64),
    tools_used          VARCHAR(2000),
    tool_calls_json     TEXT,
    tool_call_id        VARCHAR(64),
    tool_name           VARCHAR(64),
    sources             TEXT,
    token_usage         INT,
    error               BOOLEAN,
    interrupted         BOOLEAN,
    created_at          TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_messages_session ON messages(session_id);
CREATE INDEX IF NOT EXISTS idx_messages_seq     ON messages(session_id, seq);

-- 11. 生成记录表 (V1.x 创作模式)
CREATE TABLE IF NOT EXISTS generations (
    id                VARCHAR(64)  PRIMARY KEY,
    project_id        VARCHAR(64)  NOT NULL,
    type              VARCHAR(16)  NOT NULL,
    prompt            VARCHAR(4000),
    size              VARCHAR(32),
    refer_image_url   VARCHAR(1024),
    result_url        VARCHAR(1024),
    thumbnail_url     VARCHAR(1024),
    model             VARCHAR(64),
    params_json       VARCHAR(4000),
    cost_credits      INT          NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    error_message     VARCHAR(1000),
    duration_ms       BIGINT       NOT NULL DEFAULT 0,
    started_at        BIGINT,
    created_at        TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_generations_project ON generations(project_id);
