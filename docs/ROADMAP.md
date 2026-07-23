# Roadmap

Potaty's roadmap is ordered by risk reduction, not by feature count. The current local Studio is
an open-source preview: prompt, transcript, and public GitHub inputs run through a deterministic,
evidence-linked pipeline, but the repository is not yet a hosted multi-tenant production service.

## Current verified baseline

The 2026-07-17 release candidate passes the full configured JVM/JS suite, Kotlin style checks,
npm's moderate advisory gate, the production browser build, and the backend distribution build.
Manual Chromium QA covers
all three source journeys, keyboard/modal behavior, a 390 px viewport, console/storage checks, and
live local API polling. Generated job artifacts are replay-idempotent and atomically persisted;
text/transcript and GitHub source/version/chunk artifacts are also request-bound, replay-idempotent,
and atomic; a database-clocked pre-outbound claim prevents duplicate GitHub work and malformed 2xx
payloads fail closed; browser retry keys survive same-tab reload without persisting source text or
credentials; quota admission is durable; transcription
request bytes are bounded before deserialization;
credentials are resolved server-side only for fresh provider calls; and provider success is
checkpointed before transcript evidence and usage commit atomically. Checkpoint-free uncertain
spend has an owner-only, audited reconciliation API. This is the baseline future milestones must
preserve.

Open-source publication and hosted production are separate gates. The repository can be published
as a clearly labelled preview once its first GitHub CI run and maintainer diff review pass. A public
hosted service and stable `1.0` remain blocked by the P0 operational evidence below.

## First public preview (`0.1.0`)

- Review the complete dirty worktree intentionally, split it into comprehensible commits, and run
  the documented release gate on the exact commit to be tagged.
- Require a green GitHub-hosted CI run including Compose configuration, container build/health,
  PostgreSQL migrations, licence/notice checks, secret scanning, and the production bundle budget.
- Enable private vulnerability reporting, branch protection, required reviews/checks, Dependabot
  or equivalent update automation, and documented maintainer/security ownership.
- Publish checksums, an SBOM/provenance record, changelog, known limitations, browser screenshots,
  and an explicit “no hosted-production claim” release note.
- Re-run the dependency advisory ledger immediately before tagging; scanner exceptions need an
  advisory link, resolved-version evidence, owner, and review date.

## Suggested release train

| Milestone | Focus | Exit condition |
|---|---|---|
| `0.1.x` preview | Publishable repository, deterministic core, honest self-hosting docs | Exact tagged commit passes CI, notices/licences/secrets are clean, preview limitations are prominent |
| `0.2.x` reliability | PostgreSQL integration, automated browser/accessibility tests, operator console, observability | Crash/replay/restore and cross-browser gates run continuously, not only in a manual review |
| `0.3.x` product depth | Incremental repository indexing, end-user audio flow, evidence-preserving edits and version diff | New flows retain tenant, evidence, determinism, cost, consent, and recovery contracts |
| `1.0` hosted | Identity/KMS/privacy/abuse/deployment P0 closure | Every P0 row below has production-topology evidence and a completed incident/restore drill |

Minor versions are outcomes, not dates. A product feature does not advance the hosted-readiness
claim unless its security, operations, accessibility, and failure-recovery evidence advances too.

## Release principles

- Imported repository code is always data and is never executed.
- Diagram IR remains renderer-independent, versioned, deterministic, and evidence-linked.
- Unsupported or inferred claims stay visible to the reviewer.
- No publishing or pull-request flow auto-merges changes.
- A milestone is complete only when its operational and negative-path acceptance criteria pass.

## P0 — hosted production gate

These items block a public hosted service and the first stable release.

| Workstream | Required outcome | Acceptance evidence |
|---|---|---|
| Identity and onboarding | OIDC/JWKS or an equivalent asymmetric identity boundary, audited workspace/user/project provisioning, key rotation, logout, and durable cross-instance revocation | Cross-tenant and stale-membership E2E tests; rotation and revocation drill |
| Secret management | Replace the local AES-derived credential store with KMS/HSM envelope encryption and a narrow audit trail | No long-lived master key in app memory; key rotation and restore test |
| Abuse and cost controls | Global request/body limits, per-workspace rate limits, concurrency quotas, provider budgets, and safe backpressure | Load/abuse suite proves bounded memory, queue depth, and spend |
| PostgreSQL operations | Expand the existing Flyway/source-claim PostgreSQL CI contract to every queue and crash boundary; automate backup, restore, rollback, and schema compatibility checks | Fresh install, upgrade, point-in-time restore, and rollback drills in CI/staging |
| Privacy lifecycle | Retention scheduler, user export/deletion, provider/cache/backup propagation, legal-hold rules, and operator audit events | End-to-end access/export/deletion verification with a published data inventory |
| Release security | JVM and JavaScript SCA, secret scanning, SBOM/provenance, container/image scanning, branch protection, and signed release artifacts | CI-enforced clean scans and reproducible release checklist |
| Browser quality | Automated real-browser tests for all three inputs, errors, keyboard flows, WCAG 2.2 AA, responsive layouts, and visual regressions | Chromium plus at least one non-Chromium browser in CI; zero serious axe findings |
| Deployment | TLS/reverse-proxy profile, durable configuration, structured logs, alerts, canaries, rollback, and disaster recovery | Staging soak test and documented incident/rollback exercise |

## P1 — reliability and operator experience

- Build a tenant-scoped operator console for job replay/cancellation, usage, quotas, provider
  health, GitHub connections, retention, and audit search. Destructive actions require explicit
  confirmation and immutable audit events.
- Persist webhook replay protection outside process memory and verify GitHub install, suspend,
  uninstall, token-expiry, and repository-permission changes end to end.
- Add graceful worker draining, queue-depth/autoscaling policies, dead-letter inspection, and
  multi-instance lease/fencing soak tests.
- Complete metrics instrumentation for jobs, latency, renderer failures, source redactions,
  provider tokens/costs, rate limits, and database health; publish starter dashboards and alerts.
- Turn dependency scan adjudications into expiring machine-readable exceptions so stale scanner
  metadata cannot become a permanent waiver; fail when the resolved version or advisory changes.
- Add deterministic job-replay and crash-point tests on PostgreSQL around diagram/version/rendering
  commit, usage booking, quota release, and worker lease loss.
- Build the operator-console view over the existing owner-only external-spend reconciliation API,
  adding provider receipt correlation, ageing/amount alerts, runbook links, dual-control policy for
  large charges, and metrics for unresolved attempts. Durable success checkpoints must remain
  excluded and resumable only through the original idempotent request.
- Add a tenant-scoped server operation journal and browser reconciliation screen for unknown source/
  job outcomes. It should list opaque operation metadata without source content, recover completed
  results, resolve abandoned attempts under explicit audit, and replace the preview UI's deliberate
  fail-closed stop when its bounded same-tab retry journal is full or unavailable.
- Introduce deterministic performance fixtures and enforce API latency, browser load, bundle, and
  large-diagram memory budgets.
- Modernize the Kotlin/Gradle/Ktor/Exposed/Flyway toolchain in staged, compatibility-tested steps;
  remove Gradle 9 deprecations and keep JVM/JS metadata versions aligned before the old line loses
  upstream security support.
- Reduce the production JavaScript bundle below 80% of its current 320 KiB gzip ceiling and subset
  or split the 1.49 MiB Korean diagram font without regressing deterministic cell width.

## P2 — product depth

- Incremental GitHub indexing by commit/tree hash, monorepo scope controls, `.potatyignore`
  preview, truncation recovery, and repository-diff diagrams.
- Richer transcript understanding: speaker normalization, decision/action/risk views, unresolved
  questions, confidence-aware deduplication, and an end-user audio transcription flow with direct
  object-storage upload, progress, cancellation, consent/retention controls, and credential
  selection that never exposes secret material. Replace large inline base64 bodies with scoped,
  expiring upload grants before increasing accepted media size.
- Evidence-preserving natural-language edits, explicit claim confirmation, version diff, undo, and
  conflict-aware regeneration.
- First-class D2, SVG, PNG, PDF, and Markdown exports with sanitizer allowlists, deterministic
  snapshots, and accessibility metadata.
- Layout improvements for dense graphs, long Unicode labels, groups, parallel edges, timelines,
  and sequence ordering; subset the D2Coding result font to reduce transfer size.
- Expand language-aware code extraction without executing repositories, beginning with manifests,
  imports, routes, schemas, and infrastructure definitions.

## P3 — ecosystem and collaboration

- Review links, comments, approvals, and evidence annotations with scoped sharing and expiry.
- A headless CLI and stable API/IR schema for CI-generated documentation.
- Opt-in GitHub pull-request publishing with branch protection awareness; never auto-merge.
- Renderer and extractor extension points with a permission model and deterministic contract tests.
- Internationalization of the Studio while keeping generated plain text portable.

## Quality signals tracked every release

- Evidence coverage and unsupported critical claims.
- Deterministic IR/layout/render hashes across repeated runs.
- Cross-tenant negative-path coverage.
- Queue success, retry, cancellation, lease-loss, and age percentiles.
- Browser accessibility, responsive, console, visual, and performance regressions.
- Dependency, secret, licence, SBOM, container, and migration gate results.
- Restore time, deletion completion time, and incident/rollback drill outcomes.

Dates are intentionally omitted until maintainers and hosting scope are committed. P0 security and
operations work should not be displaced by cosmetic features when planning the first stable tag.
