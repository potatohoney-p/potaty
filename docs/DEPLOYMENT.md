# Deployment

The checked-in Docker assets provide a hardened local backend container and PostgreSQL/pgvector stack. They are not a complete public production deployment.

## Local Compose stack

1. Copy the environment template and generate independent values:

   ```bash
   cp .env.example .env
   openssl rand -hex 32
   openssl rand -hex 32
   openssl rand -hex 32
   ```

2. Fill `POTATY_DB_PASSWORD`, `POTATY_DEV_TOKEN`, and `POTATY_CREDENTIAL_KMS_KEY` in `.env`.
3. Validate interpolation before building:

   ```bash
   docker compose config
   ```

4. Start the services:

   ```bash
   docker compose up --build
   ```

5. Check health at <http://localhost:8090/health>.

PostgreSQL is available only on the internal Compose network. The backend is bound to `127.0.0.1` by default. Development auth is disabled unless `.env` explicitly selects `POTATY_AUTH_MODE=dev` with a non-default token.

Explicit development auth seeds the configured workspace, user, membership, and project in either H2 or PostgreSQL. JWT mode deliberately never seeds identity data. Production operators must provision those records through an authenticated, auditable onboarding path before issuing a workspace token.

## Environment contract

| Variable | Required in production | Notes |
|---|---:|---|
| `POTATY_ENV=production` | Yes | Enables fail-closed production configuration validation |
| `POTATY_DB_MODE=postgres` | Yes | Do not use ephemeral H2 for durable workloads |
| `POTATY_DB_URL`, `POTATY_DB_USER`, `POTATY_DB_PASSWORD` | Yes | Use a dedicated least-privilege database role |
| `POTATY_DB_MIGRATE` | Deployment decision | Prefer a controlled migration job for multi-instance rollouts |
| `POTATY_CORS_ORIGINS` | Yes | Exact HTTPS browser origins only |
| `POTATY_AUTH_MODE=jwt` | Yes | Development OWNER tokens must never be public |
| `POTATY_JWT_ISSUER`, `POTATY_JWT_AUDIENCE`, `POTATY_JWT_SECRET` | Yes | HS256 validation; generate at least 32 random secret bytes and inject through a secret manager |
| `POTATY_JWT_TTL_SECONDS` | Yes | Accepted range is 60–86,400 seconds; keep access tokens short-lived |
| `POTATY_CREDENTIAL_KMS_KEY` | Not sufficient | Current code derives a local AES key; replace it with real KMS integration |
| `POTATY_ALLOW_PROVIDER_OAUTH` | No | Keep `false` unless an approved BYO/self-hosted Anthropic OAuth use case has been reviewed; consumer subscription tokens are unsupported |
| `POTATY_WORKSPACE_MONTHLY_COST_CAP_USD` | Yes | Atomic admission includes month-to-date usage plus active reservations; choose a tenant budget explicitly |
| `POTATY_TRANSCRIPTION_USD_PER_MINUTE` | If transcription is enabled | Estimate only; align it with the selected provider/model pricing |
| `POTATY_TRANSCRIPTION_RESERVATION_BITRATE_BPS` | If transcription is enabled | Conservative bitrate floor for pre-call reservation; validate against accepted audio formats |
| `OPENAI_API_KEY` | Optional | Enables optional enrichment; inject through a secret manager |
| `GITHUB_APP_ID`, `GITHUB_APP_PRIVATE_KEY`, `GITHUB_WEBHOOK_SECRET` | If private GitHub integration is enabled | App identity and webhook verification; inject secrets through a secret manager |
| `GITHUB_APP_SLUG`, `GITHUB_OAUTH_CLIENT_ID`, `GITHUB_OAUTH_CLIENT_SECRET` | If the GitHub connection flow is enabled | App installation URL and OAuth exchange; configuration is all-or-nothing |
| `GITHUB_OAUTH_CALLBACK_URL`, `POTATY_PUBLIC_BASE_URL` | If the GitHub connection flow is enabled | Exact HTTPS callback and post-connect public origin in production |
| `GITHUB_CONNECT_STATE_SECRET`, `GITHUB_CONNECT_STATE_TTL_SECONDS` | If the GitHub connection flow is enabled | 32+ character signing secret and short one-time state lifetime |
| `GITHUB_API_BASE_URL`, `GITHUB_WEB_BASE_URL` | Optional | Strict REST/web origins for GitHub.com or a reviewed GHES deployment |

The checked-in JWT store validates HS256 tokens but does not provide a public login or token-issuance endpoint. A trusted self-hosted identity gateway must mint tokens with the configured issuer and audience, UUID `sub` and `workspace_id` claims, an `owner`, `admin`, `editor`, or `viewer` role, unique `jti`, and bounded `iat`/`exp`. The backend rechecks that role against active workspace membership on every resolution. Revocation is process-local, so multi-instance deployments require short lifetimes and a durable external revocation/session strategy.

`.env.example` lists every Compose pass-through setting. Private GitHub connection startup should
be treated as disabled unless the complete App/OAuth/state/origin set is present; partial
configuration must not be used as a production fallback. Do not bake secrets into an image,
Compose file, frontend bundle, log, or repository.

## Request-size and concurrency boundary

The inline transcription route accepts at most 25 MiB of decoded audio. Its JSON request stream is
hard-capped at 35,018,072 bytes before deserialization, including when `Content-Length` is absent;
the extra space covers base64 and bounded metadata. This endpoint-local guard is not a global abuse
control. Configure the reverse proxy and ingress with matching or stricter body, header, request
time, connection, and concurrency limits for every route. Reject oversized bodies at the edge,
rate-limit by authenticated workspace as well as network identity, and cap simultaneous uploads
per instance so several individually valid bodies cannot exhaust heap.

Do not raise either application or proxy limits without measuring peak JVM memory under concurrent
base64 decode. The roadmap's object-storage upload flow is the preferred way to support larger
media; imported object keys must remain tenant-bound and short-lived.

## Source-ingestion retries

`POST /api/v1/projects/{projectId}/sources`, GitHub App indexing, and public GitHub URL indexing
require a 1–200 character `Idempotency-Key`. The backend stores a SHA-256 binding for the logical
request and commits source, first version, and every evidence chunk in one transaction. Retry the
same request with the same key after a timeout or lost response; Potaty returns the original ids.
Changing the body, project, or source flow under that key returns `409`.

GitHub replay is resolved before a remote API request or installation-token lookup. Do not create
a new key merely because the client lost the response: doing so intentionally starts a new source
snapshot and can duplicate work. Keys are scoped to the authenticated workspace, but clients
should still generate high-entropy, per-operation values and retain them for the complete
source-to-diagram attempt.

## Recovering uncertain external spend

Workspace owners can inspect expired, uncertain, checkpoint-free provider attempts through
`GET /api/v1/admin/external-spend/pending`. After correlating the reservation metadata with the
provider's billing receipt, settle exactly once with
`POST /api/v1/admin/external-spend/{reservationId}/reconcile` and a JSON body containing:

```json
{
  "decision": "charge",
  "chargeUsd": 1.25,
  "reason": "Matched provider receipt and incident record 2026-07-17-01.",
  "confirm": true
}
```

Use `release` only with evidence that the provider did not bill; release requests must omit
`chargeUsd`. Every `charge` request must include the amount from the correlated provider receipt;
the reservation estimate is never substituted. Charge values are non-negative and cannot exceed
`999999.999999`, the exact
PostgreSQL `numeric(12,6)` boundary. Reasons are 10–500 safe characters, secret-redacted before
storage, and written with actor, decision, amount, and timestamp to the tenant audit log.

A successful transcription checkpoint is deliberately absent from this queue and cannot be
manually charged or released. Retry the original request with the same body and
`Idempotency-Key` after its processing lease expires; Potaty will finish persistence without
calling the provider or reopening the credential. Never submit an uncertain attempt under a new
key until the original has been reconciled, because that can create a second billable call.

## Production readiness gate

Do not expose Potaty publicly until all of the following are true:

- a trusted login/token-issuance path, key rotation, and durable multi-instance revocation complement JWT validation;
- users, workspaces, projects, and GitHub installations are provisioned and tenant-bound through audited workflows;
- credential encryption uses KMS/HSM-backed envelope encryption;
- distributed rate limits, global request/body limits beyond the transcription-local ceiling,
  provider timeouts/budgets, concurrency caps, and queue backpressure are enforced;
- expired running jobs are reclaimed with lease fencing, and shutdown drains workers safely;
- PostgreSQL migrations, pgvector, backup, restore, and disaster recovery are tested;
- readiness verifies every critical dependency needed to accept work, not only the database;
- metrics are wired to real events, logs are structured, and alerts/dashboards exist;
- TLS terminates at a trusted reverse proxy and security headers are configured;
- source retention, deletion, privacy notice, provider disclosure, and operator access are documented;
- browser E2E, live integration canaries, image scanning, SBOM, and rollback checks pass.

The data inventory, retention gaps, and operator checklist are detailed in [Privacy and data handling](PRIVACY.md).
The risk-ordered acceptance plan is in [the roadmap](ROADMAP.md), and the latest evidence-backed
decision is in [the production readiness review](reviews/PRODUCTION_READINESS_REVIEW.md).

## Container controls

The backend image runs as UID/GID `10001`, uses the checked-in Gradle wrapper, includes a healthcheck, and fails closed until JWT configuration is provided. Compose drops Linux capabilities, uses a read-only backend filesystem with `/tmp` tmpfs, binds only to localhost, rotates container logs, and does not publish PostgreSQL.

Pin base images by digest in a real release pipeline and rebuild regularly for operating-system security updates.

## Backup and restore

Design and test backup/restore against the exact production platform. A basic Compose backup looks like:

```bash
docker compose exec -T postgres pg_dump -U potaty -d potaty --format=custom > potaty.dump
```

Restoration must be tested into a fresh database before calling the backup policy complete. Backups contain source code, transcripts, evidence, and generated artifacts; encrypt them, restrict access, define retention, and test deletion propagation.

## Rollout

Use immutable image tags, a controlled Flyway migration step, readiness checks, and a rollback plan. Never run multiple application versions against a schema change unless the migration is explicitly backward compatible.
