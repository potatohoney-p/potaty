-- Bind generated diagrams to their owning job so retries reuse one atomically persisted artifact.
-- The column is nullable for synchronous/manual diagram creation and is backward-compatible.

alter table diagrams add column generation_job_id uuid;

create unique index uq_diagrams_workspace_generation_job
    on diagrams(workspace_id, generation_job_id)
    where generation_job_id is not null;
