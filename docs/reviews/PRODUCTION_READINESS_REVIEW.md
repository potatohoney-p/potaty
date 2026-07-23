# Production readiness review

Review date: 2026-07-17
Review target: commit `c70cee0` plus the uncommitted production-readiness worktree
Release decision: suitable for local development and controlled self-hosted evaluation; **not yet
approved for a public hosted multi-tenant service**.

## Executive assessment

Potaty's core product contract is implemented: prompt, transcript, and public GitHub inputs become
deterministic, evidence-linked Diagram IR and attractive ASCII output without executing imported
repository code. The production browser bundle, backend distribution, unit/integration suites,
security boundary tests, and live local API/browser flows are release-gated in this worktree.

The remaining blockers are operational trust boundaries rather than hidden UI defects. A public
hosted service still needs an external identity lifecycle, KMS/HSM-backed credential encryption,
durable distributed abuse controls and revocation, broad PostgreSQL/backup drills, automated
cross-browser accessibility coverage, and a deploy/rollback environment. Those items are tracked
as P0 in [the roadmap](../ROADMAP.md).

## Evidence reviewed

| Area | Evidence | Assessment |
|---|---|---|
| Dependency reproducibility | `npm ci`, lockfiles, Gradle wrapper, exact/pinned GitHub Actions | Pass for the checked-in build; release SBOM/provenance remain a hosted-release gate |
| Maven advisory review | OSV query of all 108 resolved runtime coordinates plus Gradle `dependencyInsight` | 37 findings removed; Jackson resolves to upstream-fixed 2.18.9, with one stale OSV range hit explicitly adjudicated |
| JavaScript security | `npm audit --audit-level=moderate` | No known moderate-or-higher npm advisories at review time |
| Kotlin quality | `ktlint`, 95 JVM/JS suites, deterministic golden render tests | Pass: 518 tests, 0 failures/errors; 1 real-PostgreSQL test is conditionally skipped locally and enabled in CI |
| Release artifacts | `browserProductionWebpack`, `:backend:installDist` | Pass; 302,571-byte gzip browser bundle is within the 327,680-byte gate; the 79-file, 32,732,516-byte backend distribution installs successfully |
| API flow | Live H2 backend: prompt, transcript, public GitHub, authentication and error paths | Pass for the supported local flow |
| Browser flow | Production bundle at desktop and 390 px mobile; console, keyboard, storage, overflow and target checks | Pass for the manually exercised Chromium flow |
| Tenant boundary | Membership-bound sessions, request-bound source ingestion, and tenant-negative repository/route tests | Strong application-level coverage; distributed identity lifecycle remains open |
| Source trust | Normalization, terminal-control removal, redaction, prompt isolation, schema-constrained LLM output | Pass for implemented paths; imported code is never executed |
| Transcription recovery | Request-stream ceiling, exact request hash, provider checkpoint, lease fencing, transactional completion, owner reconciliation tests | Pass for local/H2 fault injection; live provider receipts and PostgreSQL crash testing remain staging gates |
| Source recovery | Atomic source persistence, pre-outbound GitHub claim, malformed-2xx rejection, no-network replay, and Flyway/PostgreSQL claim contract | Pass for H2 plus focused real-PostgreSQL constraints/concurrency/retention CI; broad crash/upgrade soak remains a hosted gate |
| Data lifecycle | Tenant-scoped retention deletion order and dependent-table coverage | Corrected and tested; scheduling/export/backup propagation remain open |
| Containers | Hardened Dockerfile/Compose and CI smoke workflow | Configuration reviewed; local Docker execution was unavailable in this environment |

## Defects closed during the final review

- Unexpected request, worker, and heartbeat exceptions no longer copy provider payloads, SQL
  details, imported text, or credential-like exception messages into logs or persisted job errors.
- GitHub repository inputs now accept only bounded, valid `github.com` owner/repository/ref forms;
  malformed or deceptive hosts fail without reflecting the raw input.
- CORS and GitHub base URLs are parsed as strict origins instead of being accepted as loosely
  formatted strings.
- Provider-generated graph identifiers and labels are bounded, normalized, allowlisted, and
  remapped before entering canonical IR.
- Idempotency keys now reject malformed values and return `409` when a key is replayed with a
  different request. Concurrent insert races resolve against the database uniqueness constraint.
- Text ingestion rejects unsupported source kinds, bounds display names and diagram controls, and
  removes terminal and bidirectional override controls while preserving ordinary Unicode text.
- Workspace retention now removes usage, renderings, extraction rows, GitHub connection state,
  credentials, and audit rows in foreign-key-safe order before source/diagram/job parents.
- Job execution renews leases, fences terminal transitions, recovers expired work, and treats lease
  loss and cancellation as control flow rather than retryable failures.
- Browser bearer tokens are memory-only; non-secret settings and SHA-256/opaque retry state are
  tab-scoped. All modal actions and keyboard dismissal work, CSP is clean, document titles track
  the current result, and responsive output controls meet the target-size contract.
- Original MonoSketch copyright notices were restored in every derived source/build file and the
  upstream Apache-2.0 relationship is now explicit in `NOTICE` and third-party documentation.
- A current OSV check found two reviewed PostgreSQL JDBC advisories; the driver was upgraded to
  42.7.11, the version that contains both fixes.
- Ktor, Netty, Logback and Jackson were security-aligned after a complete resolved-runtime scan;
  Jackson now resolves to 2.18.9, the upstream fixed line. OSV's stale range hit is documented in
  `SECURITY.md` with both the resolved-graph and authoritative-advisory evidence.
- Actual LLM token usage is now booked against the workspace and job, including structured-output
  repair attempts; provider/model/token/application provenance is stored with the diagram version.
- Requested Mermaid, D2, PlantUML, DOT, and Markdown results are persisted and returned from the
  immutable version instead of being discarded and silently replaced with Mermaid.
- Job creator identity, terminal failure/needs-input reasons, and terminal progress now survive the
  queue boundary; browser polling covers the backend's worst-case provider timeout window.
- Source text can no longer close its untrusted prompt fence with a copied delimiter, malformed
  JSON parser details are not reflected, and one-cell labels cannot let wide Unicode overwrite a
  border.
- Diagram, first immutable version, and requested renderings now commit in one transaction under a
  unique `(workspace, generation job)` binding. Lease-reclaimed retries reuse the committed
  artifact and deterministic ids instead of creating orphan or duplicate diagrams.
- The transcription route verifies the tenant-owned project before parsing a potentially large
  request or touching credentials/quota/provider state. Clients send only a credential id; active,
  non-revoked, same-workspace OpenAI API-key rows are resolved server-side.
- Provider speaker strings pass through source normalization/redaction and cannot overwrite the
  chunker's sanitized speaker metadata; only exact numeric segment times are restored.
- GitHub App/OAuth connection configuration is complete across `.env.example`, Compose, and the
  README, including callback/public origins and signed, expiring connection state.
- Transcription now crosses a durable cost fence immediately before the provider call. Usage
  insertion and reservation release share one transaction; a synthetic database-insert failure
  proves the pending charge remains quota-visible beyond the ordinary reservation TTL.
- Inline transcription bodies are counted before UTF-8/JSON deserialization, including requests
  without `Content-Length`. This closes unbounded pre-validation allocation for the 25 MiB inline
  audio contract without pretending to replace ingress-wide concurrency and rate controls.
- A successful provider response is stored as a bounded redacted checkpoint. Source, source
  version, chunks, usage, replayable HTTP result, and quota release commit atomically; a forced
  usage collision rolls every artifact back and the same key later completes exactly once.
- Stored results and checkpoints remain replayable after credential rotation/revocation. Durable
  checkpoints are excluded from owner reconciliation at the queue, locked service guard, and
  update predicate, preventing a valid success from being manually cleared and billed twice.
- PostgreSQL JSONB numeric normalization is compared semantically, and owner-entered charges cannot
  exceed the authoritative `numeric(12,6)` maximum. A final independent focused review returned
  `PASS` on these accounting/recovery boundaries with no actionable finding.
- Prompt/text-transcript and GitHub sources now bind a workspace idempotency key to the logical
  request and commit source/version/chunks atomically. An injected mid-chunk failure leaves no
  source or version; GitHub replay returns the stored ids/ref before any remote or credential work.
- Owner reconciliation requires an explicit provider-receipt amount for every charge instead of
  silently falling back to the reservation estimate.
- GitHub ingestion claims are acquired before credential/network work and use PostgreSQL server
  time plus token-fenced CAS for takeover, renewal, finalization, and completion. Mandatory tree/
  blob response fields and exact SHA/size semantics fail closed.
- Browser unknown outcomes survive reload through SHA-256 request digests and opaque idempotency
  keys only. Unconfirmed cancellation and cancel/success races retain the original job key, while
  access tokens are never restored from storage.
- A third independent narrow adversarial review rechecked these lease, GitHub-shape, cancellation,
  browser-storage, and PostgreSQL-evidence fixes and returned `PASS` with no P1/P2 finding.
- Final production-bundle QA exposed a Kotlin/JS interop defect where an external `Uint8Array`
  index compiled to the nonexistent browser method `bytes.get(index)`. Native bracket indexing and
  a browser regression test now protect SHA-256 retry fingerprints; the live source/job/result flow
  was repeated with expected `201`/`202`/`200` responses and zero console errors.
- Browser transport now applies one abortable deadline across fetch headers and response-body reads,
  settles late callbacks once, and retains the job key for unusable success/unknown outcomes.
  Back-to-input aborts the active request but keeps run ownership until the stale coroutine exits.
- Prompt, GitHub, and transcript source controls are scrubbed on reload/BFCache restoration. Active
  transcript readers are aborted and generation-fenced so stale callbacks cannot repopulate raw
  text after the scrub.
- The bounded retry journal never evicts unresolved attempts. Read, corruption, quota, and write
  failures latch the tab fail-closed before another server mutation; candidate memory state commits
  only after session storage succeeds.
- The final independent read-only adversarial re-review returned `PASS` after the controller-level
  ChromeHeadless lifecycle assertions were strengthened to fail if stale success/error callbacks,
  removal, replacement, or persisted-page cleanup regress.

## Hosted-production blockers

These are release blockers, not optional polish:

1. Replace development/HS256 identity plumbing with an audited login and onboarding path using
   OIDC/JWKS or an equivalent asymmetric boundary, key rotation, and durable cross-instance
   session revocation.
2. Replace local derived-key credential encryption with KMS/HSM envelope encryption and audited,
   least-privilege decrypt operations.
3. Add global body/concurrency limits beyond the transcription-local ceiling, distributed
   workspace rate limits, provider budgets, queue backpressure, and load/abuse evidence.
4. Exercise Flyway, queue leasing, pgvector, upgrade/rollback, point-in-time restore, and deletion
   against the exact production PostgreSQL topology.
5. Complete privacy operations: scheduled retention, access/export/deletion, legal holds, cache and
   provider propagation, backup expiry, and workspace closure.
6. Enforce JVM and container SCA, secret scanning, SBOM/provenance, signed releases, and branch
   protection in the release environment.
7. Automate Chromium plus non-Chromium E2E, WCAG 2.2 AA scanning, keyboard/error flows, responsive
   screenshots, and visual regression checks.
8. Operate a staging deployment with TLS, dependency-aware readiness, structured logs, alerts,
   canaries, graceful draining, rollback, and disaster-recovery drills.

## Release recommendation

Publish the repository as an honest **pre-release/open-source preview** after maintainers review
the worktree and CI passes on GitHub. Do not market it as a hosted production service and do not
enable development authentication on a public network. The first stable hosted tag should remain
blocked until every P0 roadmap item has acceptance evidence, not merely an implementation PR.
