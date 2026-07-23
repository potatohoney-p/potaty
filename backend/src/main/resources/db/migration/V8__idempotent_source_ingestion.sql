-- A completed external attempt may be retried after a response loss or database interruption.
-- The stable request hash prevents that retry from creating a second source artifact.

alter table sources add column ingestion_key text;

create unique index uq_sources_workspace_ingestion_key
    on sources(workspace_id, ingestion_key)
    where ingestion_key is not null;
