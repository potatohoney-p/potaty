-- Keep an external provider reservation counted after its ordinary TTL until durable usage and
-- release settle atomically. A null marker retains the existing crash-expiry behavior for work
-- that never crossed a billable boundary.

alter table cost_reservations
    add column request_hash text,
    add column external_operation text,
    add column external_provider text,
    add column external_model text,
    add column external_stage text,
    add column external_metadata jsonb,
    add column external_spend_started_at timestamptz,
    add column external_checkpoint jsonb,
    add column external_checkpointed_at timestamptz,
    add column processing_token uuid,
    add column processing_lease_expires_at timestamptz,
    add column external_result jsonb,
    add column reconciliation_decision text,
    add column reconciled_at timestamptz,
    add column reconciled_by uuid references users(id);

alter table cost_reservations
    add constraint ck_cost_reservations_external_terminal check (
        external_spend_started_at is null
        or released_at is null
        or external_result is not null
        or reconciliation_decision is not null
    ),
    add constraint ck_cost_reservations_checkpoint_pending check (
        external_checkpoint is null
        or (external_spend_started_at is not null and released_at is null)
    ),
    add constraint ck_cost_reservations_processing_fence check (
        external_spend_started_at is null
        or released_at is not null
        or (
            request_hash is not null
            and processing_token is not null
            and processing_lease_expires_at is not null
        )
    ),
    add constraint ck_cost_reservations_reconciliation_decision check (
        reconciliation_decision is null or reconciliation_decision in ('charge', 'release')
    );

create index idx_cost_reservations_external_spend
    on cost_reservations(workspace_id, processing_lease_expires_at)
    where released_at is null and external_spend_started_at is not null;
