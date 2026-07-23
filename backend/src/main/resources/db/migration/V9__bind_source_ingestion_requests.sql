-- Bind every source-ingestion idempotency key to the logical request that first used it.
-- Existing durable transcription rows used their sha256 request hash as the ingestion key,
-- so they can be backfilled without weakening replay validation.

alter table sources add column ingestion_request_hash text;

update sources
set ingestion_request_hash = ingestion_key
where ingestion_key ~ '^sha256:[0-9a-f]{64}$';

alter table sources add constraint ck_sources_ingestion_request_hash
    check (
        (ingestion_key is null and ingestion_request_hash is null)
        or
        (
            ingestion_key is not null
            and ingestion_request_hash is not null
            and ingestion_request_hash ~ '^sha256:[0-9a-f]{64}$'
        )
    );
