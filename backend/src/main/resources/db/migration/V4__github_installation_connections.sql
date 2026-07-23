-- Copyright (c) 2026, Potaty
-- Verified GitHub App installation bindings and one-time OAuth/connect state.

create table github_installations (
    id uuid primary key,
    workspace_id uuid not null,
    connected_by_user_id uuid not null,
    installation_id bigint not null check (installation_id > 0),
    app_id bigint not null check (app_id > 0),
    account_id bigint not null check (account_id > 0),
    account_login text not null,
    account_type text not null,
    installation_html_url text not null,
    github_user_id bigint not null check (github_user_id > 0),
    github_login text not null,
    active_key text unique,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    disconnected_at timestamptz,
    constraint fk_github_installations_member
        foreign key (workspace_id, connected_by_user_id)
        references workspace_members(workspace_id, user_id),
    constraint uq_github_installations_workspace_id unique (workspace_id, id),
    constraint ck_github_installations_active_state check (
        (active_key is not null and disconnected_at is null) or
        (active_key is null and disconnected_at is not null)
    )
);

create index idx_github_installations_workspace
    on github_installations(workspace_id, created_at desc);

create table github_connect_states (
    nonce_hash text primary key,
    workspace_id uuid not null,
    user_id uuid not null,
    phase text not null check (phase in ('INSTALL', 'OAUTH')),
    candidate_installation_id bigint check (candidate_installation_id > 0),
    pkce_verifier text,
    expires_at timestamptz not null,
    consumed_at timestamptz,
    created_at timestamptz not null,
    constraint fk_github_connect_states_member
        foreign key (workspace_id, user_id)
        references workspace_members(workspace_id, user_id),
    constraint ck_github_connect_states_phase check (
        (phase = 'INSTALL' and candidate_installation_id is null and pkce_verifier is null) or
        (phase = 'OAUTH' and candidate_installation_id is not null and pkce_verifier is not null)
    )
);

create index idx_github_connect_states_expiry on github_connect_states(expires_at);
