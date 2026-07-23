# Architecture

Potaty has two product surfaces: a browser Studio that orchestrates generation and displays evidence, and a manual ASCII editor built from the original drawing modules. The canonical artifact between ingestion and rendering is Diagram IR.

## System shape

```text
Browser
  ├─ Studio controller
  │    ├─ prompt / transcript file / GitHub URL
  │    ├─ typed workbench client
  │    └─ ASCII + Mermaid result and evidence inspector
  └─ manual editor
          |
          v
Ktor API
  ├─ bearer-session resolution + workspace RBAC
  ├─ source ingestion and safety scan
  ├─ GitHub indexing / audio transcription integrations
  ├─ job repository + worker pool
  ├─ deterministic extraction, optional LLM enrichment
  ├─ Diagram IR validation and version persistence
  └─ admin and Prometheus endpoints
          |
          v
H2 (development/tests) or PostgreSQL + pgvector
```

## Module responsibilities

| Module | Responsibility |
|---|---|
| `app` | Browser composition and Studio interaction controller |
| `backend` | Ktor API, tenant context, jobs, persistence, provider and GitHub integrations |
| `shared/diagram-ir` | Serializable canonical graph, evidence model, validation, patches and coverage |
| `shared/layout-engine` | Deterministic layout strategies and quality scoring |
| `shared/render-core` | Maps IR and layout into the editor's drawing primitives |
| `shared/renderer-ascii` | Produces whitespace-preserving ASCII output in the browser |
| `shared/renderer-codegen` | Produces Mermaid, D2, PlantUML, Graphviz and Markdown |
| `shared/workbench-client` | Typed API client, polling and source-to-result orchestration |
| `libs/*` | Manual editor, bitmap, shape, state, storage and UI primitives |

## Generation flow

1. The browser validates a prompt, reads a transcript locally, or accepts a GitHub URL.
2. The backend binds the mutation's `Idempotency-Key` to a request hash, normalizes the source,
   and applies the shared safety gate.
3. Source, first version, and file/line/speaker/timestamp evidence chunks commit in one
   transaction. An exact replay returns that complete artifact; changed input under the key is a
   conflict.
4. A diagram job is enqueued under a workspace and project.
5. Deterministic extractors build entities and relations. Sparse prose may be enriched by a configured provider.
6. `IrAssembler` builds Diagram IR; `IrValidator` and `EvidenceCoverageScorer` produce publishability evidence.
7. The backend atomically persists the diagram, first immutable version, and requested code
   renderings. A unique workspace/job binding makes a reclaimed worker return that same complete
   artifact instead of creating an orphan or duplicate.
8. The browser fetches the IR, lays it out, and renders ASCII with the same primitives used by the manual editor.

## Trust boundaries

- Source text, provider output, repository metadata, SVG, Mermaid, and request JSON are untrusted.
- Imported repositories are fetched as text and never built or executed.
- Text/transcript and GitHub ingestion keys are workspace-scoped and request-bound. GitHub replay
  is checked before a remote tree request or installation-token lookup. A PostgreSQL/H2 claim is
  acquired before credential/network work; takeover, renewal, finalization, and completion are
  token-fenced against the database clock. GitHub 2xx tree/blob payloads require their wire fields
  and exact SHA/size agreement, and repository paths are sorted before hashing/chunking for
  deterministic snapshots.
- Browser retry ownership survives a same-tab reload as SHA-256 request digests and opaque random
  idempotency keys. Raw source fingerprints and bearer tokens are memory-only; a failed cancel
  acknowledgement retains the original job key so retry cannot create a second billable job.
- Workspace ID must be present in every tenant-owned repository operation.
- JWT workspace and role claims are accepted only while the user has matching active database membership.
- Provider credentials are resolved server-side; transcription accepts only a bounded credential
  id and requires an active, non-revoked, same-workspace provider/type match. Plaintext and
  encrypted secret references must never enter HTTP DTOs, logs, browser storage, or Diagram IR.
- Transcription request bytes are streamed under a fixed ceiling before JSON deserialization. The
  tenant-owned project is checked before the body is read, and a credential is opened only for a
  fresh provider call—not to replay a stored result or finish a durable checkpoint.
- External provider accounting uses a durable pre-call fence. A successful transcription is
  checkpointed, then source/version/chunks, usage, the replayable HTTP result, and reservation
  release commit in one transaction. An expired checkpoint lease is reclaimed only by the
  original idempotent request and never appears in the generic reconciliation queue.
- A provider outcome that is genuinely uncertain and has no success checkpoint remains
  quota-visible until an owner records an audited charge/release decision. Charge decisions must
  include the provider-receipt amount; definitive provider rejection is stored and replayed at
  zero cost.
- A generated result is not automatically factual. Evidence coverage, inferred claims, and warnings stay visible for human review.

## Persistence modes

H2 is an ephemeral development/test mode. It creates the Exposed identity and application tables
and is useful for fast isolated tests. PostgreSQL uses Flyway migrations and has stronger foreign-
key constraints plus pgvector. CI now runs the source-ingestion claim contract against a disposable
pgvector PostgreSQL service, including Flyway, concurrent takeover, tenant FKs, completion/replay,
and tenant-scoped retention. That focused test does not prove every queue, upgrade, restore, or
onboarding path. Explicit development auth seeds a deterministic workspace, user, membership, and
project in either mode; JWT mode never seeds identities.

## Current production gaps

- Hosted login/token issuance, key rotation, and durable multi-instance revocation beyond the HS256 validator.
- Audited workspace/user/project onboarding for JWT and production PostgreSQL deployments.
- KMS-backed credential storage.
- Distributed request/body/rate/concurrency controls and provider budget backpressure.
- Graceful worker draining and real PostgreSQL multi-instance queue/lease soak tests.
- Complete usage/metrics instrumentation, retention scheduling, export/deletion propagation, and
  an operator UI. External-spend decisions already create immutable tenant audit events, but the
  current recovery surface is API-only.
- Live-provider, automated cross-browser E2E, broad PostgreSQL queue/upgrade coverage,
  backup/restore, and deployment tests.

GitHub App connections are workspace-bound and worker claims now use expiring leases, heartbeats,
and fencing tokens. Those controls are unit/integration tested, but the installation lifecycle and
multi-instance queue semantics still need live staging evidence before a hosted release.

These gaps are constraints, not hidden roadmap promises. See [DEPLOYMENT.md](DEPLOYMENT.md),
[TESTING.md](TESTING.md), and the [roadmap](ROADMAP.md) before operating Potaty with real users or
confidential data.
