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