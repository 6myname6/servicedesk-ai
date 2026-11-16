-- =============================================================
-- PG V1 知识库向量域：kb_chunk（PostgreSQL 16 + pgvector）
-- 依据：docs/ER图.md 3.1 / 4.10 / 5（kb_chunk 标注 PG）
-- 执行：
--   psql "postgresql://postgres:$DB_PASSWORD@localhost:5432/servicedesk_kb" \
--        -f backend/sql/pg/V1__kb_chunk.sql
-- 说明：与 MySQL 的 kb_article 分属两库，article_id 是跨库逻辑外键，
--      不建物理 FK；删文章时按 article_id 清理本表。
-- =============================================================

-- Windows 客户端默认可能是 GBK，先固定客户端编码，避免中文注释乱码
SET client_encoding = 'UTF8';

-- 向量类型 + HNSW 索引；关键词召回的 trigram 索引
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE IF NOT EXISTS kb_chunk (
    id          BIGSERIAL    PRIMARY KEY,
    article_id  BIGINT       NOT NULL,
    chunk_no    INT          NOT NULL,
    heading     VARCHAR(255) NULL,
    content     TEXT         NOT NULL,
    token_count INT          NOT NULL DEFAULT 0,
    embedding   vector(1024) NULL,
    is_active   SMALLINT     NOT NULL DEFAULT 1,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_article_chunk UNIQUE (article_id, chunk_no)
);

COMMENT ON TABLE kb_chunk IS '知识分块 + 向量；article_id 逻辑引用 MySQL 库 kb_article.id';

-- 唯一块与「按文章取有效块」，与 MySQL 侧索引口径一致
CREATE INDEX IF NOT EXISTS idx_article_active ON kb_chunk (article_id, is_active);

-- 关键词召回：pg_trgm 的 GIN，中文按字符三元组匹配，不装分词扩展
CREATE INDEX IF NOT EXISTS idx_chunk_content
    ON kb_chunk USING gin (heading gin_trgm_ops, content gin_trgm_ops);

-- 向量召回：余弦距离 + HNSW；重建索引期间可先 DROP 再建，不影响已存数据
CREATE INDEX IF NOT EXISTS idx_chunk_embedding
    ON kb_chunk USING hnsw (embedding vector_cosine_ops);
