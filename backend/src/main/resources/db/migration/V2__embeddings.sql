-- Copyright (c) 2026, Potaty
-- V2: pgvector extension + embedding column + ANN index (plan section 8.2).
--
-- Embedding dimension is provider-dependent. We default to 1536 (OpenAI text-embedding-3-small)
-- as the plan specifies, but the dimension is intentionally isolated in this single migration
-- so it can be changed (or made a metadata-driven multi-table strategy) without touching app
-- code. Do NOT hard-code vector(1536) anywhere in Kotlin.

create extension if not exists vector;

alter table source_chunks
    add column embedding vector(1536);

-- Approximate-nearest-neighbour index for semantic chunk retrieval (cosine distance).
-- ivfflat requires ANALYZE / a populated table to choose list count well; 100 lists is a
-- reasonable starting point and can be tuned per data volume.
create index idx_source_chunks_embedding
    on source_chunks
    using ivfflat (embedding vector_cosine_ops)
    with (lists = 100);
