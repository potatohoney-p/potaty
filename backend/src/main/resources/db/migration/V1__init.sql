-- Copyright (c) 2026, Potaty
-- V1: relational baseline (plan section 8). Vector columns/extension live in V2.
-- Every tenant-owned table carries workspace_id for tenant isolation (plan 20.5).

-- ---------- 8.1 workspaces & projects ----------

create table workspaces (
    id uuid primary key,
    name text not null,
    slug text not null unique,
    plan text not null default 'free',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz
);

create table users (
    id uuid primary key,
    email text not null unique,
    display_name text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz
);

create table workspace_members (
    workspace_id uuid not null references workspaces(id),
    user_id uuid not null references users(id),
    role text not null check (role in ('owner', 'admin', 'editor', 'viewer')),
    created_at timestamptz not null default now(),
    primary key (workspace_id, user_id)
);

create table projects (
    id uuid primary key,
    workspace_id uuid not null references workspaces(id),
    name text not null,
    slug text not null,
    description text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz,
    unique (workspace_id, slug)
);

create index idx_projects_workspace on projects(workspace_id);

-- ---------- 8.2 sources ----------

create table sources (
    id uuid primary key,
    workspace_id uuid not null references workspaces(id),
    project_id uuid not null references projects(id),
    source_type text not null,
    display_name text not null,
    external_ref jsonb not null default '{}'::jsonb,
    created_by uuid references users(id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz
);

create table source_versions (
    id uuid primary key,
    workspace_id uuid not null references workspaces(id),
    source_id uuid not null references sources(id),
    version_label text,
    content_hash text not null,
    normalized_text_object_key text,
    raw_object_key text,
    metadata jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    unique (source_id, content_hash)
);

-- NOTE: the embedding vector column is added in V2 (after the pgvector extension exists).
create table source_chunks (
    id uuid primary key,
    workspace_id uuid not null references workspaces(id),
    source_version_id uuid not null references source_versions(id),
    chunk_index integer not null,
    path text,
    start_line integer,
    end_line integer,
    start_page integer,
    end_page integer,
    start_ms integer,
    end_ms integer,
    speaker text,
    text text not null,
    text_hash text not null,
    token_count integer not null default 0,
    metadata jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    unique (source_version_id, chunk_index)
);

create index idx_sources_workspace_project on sources(workspace_id, project_id);
create index idx_source_versions_source on source_versions(source_id);
create index idx_source_chunks_version on source_chunks(source_version_id);
create index idx_source_chunks_workspace on source_chunks(workspace_id);

-- ---------- 8.3 extraction ----------

create table extracted_entities (
    id uuid primary key,
    workspace_id uuid not null references workspaces(id),
    project_id uuid not null references projects(id),
    source_version_id uuid not null references source_versions(id),
    canonical_key text not null,
    label text not null,
    entity_type text not null,
    summary text,
    confidence numeric(4,3) not null,
    extraction_source text not null,
    evidence jsonb not null,
    metadata jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create table extracted_relations (
    id uuid primary key,
    workspace_id uuid not null references workspaces(id),
    project_id uuid not null references projects(id),
    source_version_id uuid not null references source_versions(id),
    from_entity_key text not null,
    to_entity_key text not null,
    relation_type text not null,
    label text,
    confidence numeric(4,3) not null,
    extraction_source text not null,
    evidence jsonb not null,
    metadata jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create index idx_extracted_entities_project on extracted_entities(workspace_id, project_id);
create index idx_extracted_relations_project on extracted_relations(workspace_id, project_id);

-- ---------- 8.4 diagrams ----------

create table diagrams (
    id uuid primary key,
    workspace_id uuid not null references workspaces(id),
    project_id uuid not null references projects(id),
    title text not null,
    diagram_type text not null,
    status text not null default 'draft',
    created_by uuid references users(id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz
);

create table diagram_versions (
    id uuid primary key,
    workspace_id uuid not null references workspaces(id),
    diagram_id uuid not null references diagrams(id),
    version_number integer not null,
    cause text not null,
    ir jsonb not null,
    validation_report jsonb not null,
    evidence_coverage jsonb not null,
    source_snapshot jsonb not null,
    model_trace jsonb not null default '{}'::jsonb,
    renderer_version text not null,
    layout_engine_version text not null,
    created_by uuid references users(id),
    created_at timestamptz not null default now(),
    unique (diagram_id, version_number)
);

create table renderings (
    id uuid primary key,
    workspace_id uuid not null references workspaces(id),
    diagram_version_id uuid not null references diagram_versions(id),
    format text not null,
    object_key text,
    content_text text,
    content_hash text not null,
    render_status text not null,
    render_warnings jsonb not null default '[]'::jsonb,
    created_at timestamptz not null default now(),
    unique (diagram_version_id, format, content_hash)
);

create index idx_diagrams_project on diagrams(workspace_id, project_id);
create index idx_diagram_versions_diagram on diagram_versions(diagram_id);
create index idx_renderings_version on renderings(diagram_version_id);

-- ---------- 8.5 jobs ----------

create table jobs (
    id uuid primary key,
    workspace_id uuid not null references workspaces(id),
    project_id uuid references projects(id),
    job_type text not null,
    status text not null,
    idempotency_key text not null,
    priority integer not null default 100,
    attempts integer not null default 0,
    max_attempts integer not null default 3,
    input jsonb not null,
    output jsonb,
    error jsonb,
    locked_by text,
    locked_until timestamptz,
    run_after timestamptz not null default now(),
    created_by uuid references users(id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    completed_at timestamptz,
    unique (workspace_id, idempotency_key)
);

create table job_events (
    id uuid primary key,
    workspace_id uuid not null references workspaces(id),
    job_id uuid not null references jobs(id),
    event_type text not null,
    stage text,
    message text,
    payload jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create index idx_jobs_poll on jobs(status, priority, run_after, locked_until);
create index idx_jobs_workspace on jobs(workspace_id, created_at desc);
create index idx_job_events_job on job_events(job_id, created_at);

-- ---------- 8.6 credentials, usage, audit ----------

create table llm_credentials (
    id uuid primary key,
    workspace_id uuid not null references workspaces(id),
    provider text not null,
    credential_type text not null,
    encrypted_secret_ref text not null,
    label text not null,
    status text not null default 'active',
    metadata jsonb not null default '{}'::jsonb,
    created_by uuid references users(id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    last_used_at timestamptz,
    revoked_at timestamptz
);

create table usage_events (
    id uuid primary key,
    workspace_id uuid not null references workspaces(id),
    job_id uuid references jobs(id),
    provider text not null,
    model text not null,
    stage text not null,
    input_tokens integer not null default 0,
    output_tokens integer not null default 0,
    cached_input_tokens integer not null default 0,
    estimated_cost_usd numeric(12,6) not null default 0,
    created_at timestamptz not null default now()
);

create table audit_events (
    id uuid primary key,
    workspace_id uuid not null references workspaces(id),
    actor_user_id uuid references users(id),
    event_type text not null,
    resource_type text not null,
    resource_id uuid,
    ip inet,
    user_agent text,
    payload jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create index idx_llm_credentials_workspace on llm_credentials(workspace_id);
create index idx_usage_events_workspace on usage_events(workspace_id, created_at desc);
create index idx_audit_events_workspace on audit_events(workspace_id, created_at desc);
