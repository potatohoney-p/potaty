-- Copyright (c) 2026, Potaty
-- Serialize quota admission per workspace and hold estimated spend until a job terminates.

create table cost_reservations (
    id uuid primary key,
    workspace_id uuid not null references workspaces(id),
    reservation_key text not null,
    job_id uuid,
    amount_usd numeric(12,6) not null check (amount_usd >= 0),
    created_at timestamptz not null default now(),
    expires_at timestamptz not null,
    released_at timestamptz,
    constraint uq_cost_reservations_workspace_key unique (workspace_id, reservation_key),
    constraint fk_cost_reservations_workspace_job
        foreign key (workspace_id, job_id) references jobs(workspace_id, id)
);

create index idx_cost_reservations_active
    on cost_reservations(workspace_id, released_at, expires_at);
