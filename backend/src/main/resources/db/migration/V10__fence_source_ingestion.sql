-- Fence rate-limited or billable source ingestion before any outbound work. The complete source
-- remains the durable replay authority; this table only coordinates ownership and crash recovery.

create table source_ingestion_claims (
    id uuid primary key,
    workspace_id uuid not null,
    project_id uuid not null,
    source_type text not null,
    ingestion_key text not null,
    request_hash text not null,
    status text not null,
    processing_token uuid,
    lease_expires_at timestamptz,
    source_id uuid,
    source_version_id uuid,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_source_ingestion_claims_workspace_key unique (workspace_id, ingestion_key),
    constraint ck_source_ingestion_claims_key_hash check (
        ingestion_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,255}$'
        and request_hash ~ '^sha256:[0-9a-f]{64}$'
    ),
    constraint ck_source_ingestion_claims_state check (
        (
            status = 'processing'
            and processing_token is not null
            and lease_expires_at is not null
            and source_id is null
            and source_version_id is null
        )
        or
        (
            status = 'complete'
            and processing_token is null
            and lease_expires_at is null
            and source_id is not null
            and source_version_id is not null
        )
    ),
    constraint fk_source_ingestion_claims_workspace_project
        foreign key (workspace_id, project_id) references projects(workspace_id, id),
    constraint fk_source_ingestion_claims_workspace_source
        foreign key (workspace_id, source_id) references sources(workspace_id, id),
    constraint fk_source_ingestion_claims_workspace_version
        foreign key (workspace_id, source_version_id) references source_versions(workspace_id, id)
);

create index idx_source_ingestion_claims_recovery
    on source_ingestion_claims(status, lease_expires_at);
