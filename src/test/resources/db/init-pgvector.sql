-- ==========================================================================
--  Testcontainers 初始化脚本(pgvector 容器)
-- --------------------------------------------------------------------------
--  由 `PostgreSQLContainer#withInitScript` 在容器就绪后、Spring 上下文启动前执行。
--
--  为什么必须在"上下文启动前"装扩展:
--    `document_chunks.embedding` 的列定义是 `vector(1536)`(实体侧 columnDefinition),
--    Hibernate 在上下文启动时(ddl-auto=create-drop)就会执行建表语句 ——
--    若此时 vector 类型尚不存在, 建表直接失败, 整个集成测试无法启动。
-- ==========================================================================

CREATE EXTENSION IF NOT EXISTS vector;
