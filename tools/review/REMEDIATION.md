# Potaty — P0–P3 Remediation Disposition

> Historical remediation snapshot, superseded by the current
> [production-readiness review](../../docs/reviews/PRODUCTION_READINESS_REVIEW.md),
> [code review](../../docs/reviews/CODE_REVIEW.md), and
> [roadmap](../../docs/ROADMAP.md). The status statements below are retained for review provenance,
> not as a description of the current worktree.

Adversarial review found **90 confirmed findings** (P0×2, P1×11, P2×65, P3×12). This records
their disposition. Everything marked **FIXED** is verified by a clean, sequential build
(`:diagram-ir`/`:renderer-codegen` jsNodeTest, `:layout-engine`/`:render-core` nodeTest,
`:backend:test`) plus a byte-for-byte golden ASCII match (`:diagram-demo`).

## P0 (2) — all FIXED
- SessionAuth / TenantContext unwired (every route 401) → **FIXED**: `SessionAuth` Ktor plugin +
  `SessionStore` (in-memory dev token) wired in `Application.module`; routes resolve `TenantContext`.
  Tests: `ApiRoutesTest.unauthenticatedRequestIs401` (401 without token; 201/202 with).

## P1 (11)
- OpenAI + Anthropic HTTP stubbed → **FIXED**: real Ktor-client calls (Anthropic forced tool use;
  OpenAI json_schema strict + embeddings); `LlmProviderTest` (MockEngine) covers parse + error map.
- Worker pool / queue not started at startup → **FIXED**: `AppGraph` builds + starts `JobWorkerPool`;
  `DiagramPipelineTest.jobPipelineAdapterProducesVersion` exercises the worker→pipeline path.
- POST diagram-jobs lacks sourceVersionIds validation → **FIXED** (+ outputFormats validation).
- DiagramVersionRecord missing source_snapshot / model_trace → **FIXED** (added + persisted + read).
- SVG sanitizer misses `<style>` / weak coverage → **FIXED**: hardened (script/style/iframe/object,
  on*, javascript:/data: hrefs); `SvgSanitizerTest`.
- Credential KMS / TokenUsage→usage_events / lease extension → **DEFERRED** (prod infra: KMS, a
  `usage_events` table + recorder, multi-process lease heartbeat). Dev `EnvelopeCredentialStore`
  (AES/GCM) is documented as dev-only; single-process worker doesn't need lease extension.

## P2 (65)
**Shared modules (diagram-ir / layout-engine / render-core / renderer-codegen) — ~29 FIXED**
(applied via a verified workflow, clean build + golden identical):
- IR: additive `IrNode.userConfirmed` + R009 broadened (evidence||userConfirmed||userModified);
  `EvidenceRef.isValidRange()` (R008 inverted-range warning); confidence-weighted coverage +
  low/mid/high confidence buckets (modeled as 3 Int fields — a `Map` field trips the Kotlin/JS
  serializer-IR linker); `CycleDetector` order-independence documented + tested. (`IrFixesTest`.)
- layout: `LabelFormatter` width simplified to `coerceIn` (no-op for existing fixtures);
  `LayoutQualityScore` gained `symmetryScore`/`visualDensity`; determinism test added.
- render-core: added `slate`/`sage`/`terracotta`/`monochrome-docs` `StyleProfile`s; group-label
  clipping (no-op when the title fits — golden unchanged). (`StyleProfileTest`, group-label test.)
- codegen: enum comparison instead of `edge.type.name` strings; markdown escaping reviewed;
  `CodegenRobustnessTest`.

**Backend — FIXED**
- Idempotency: unique index `(workspace_id, idempotency_key)` + read-then-return (dedup test).
- Source chunks repository (`saveChunks`/`listChunks`); `findLatestVersion`; malformed-JSON → 400;
  output-format validation; **SafetyPreScan**: secrets stripped from content before storage, PII
  reported (`safetyPreScanRedactsSecretsAtIngestion`).

**Backend — BY DESIGN (documented, no change)**
- `JobQueue.claim()` reads across workspaces — intentional for a shared worker pool; every claimed
  job and mutation carries `workspaceId`.
- Repositories take an explicit `workspaceId` (not auto-scoped) — the explicit parameter is the
  tenant-isolation safety pattern.
- `TransactionContext` has no retry — retries live at the job layer (`RetryPolicy`).
- IR `R012` vs `Redactor` pattern overlap — `IrValidator` is in the shared module and must not
  depend on the backend `Redactor`; kept separate intentionally.

**Backend — DEFERRED (need features/infra beyond this slice)**
- Cost estimation / quota guard / rate limiting (WS22); `usage_events` persistence (WS6);
  remaining plan tables (workspaces/users/extracted_*/renderings — needed by unbuilt WS5/admin);
  value-class `WorkspaceId`/`UserId` wrappers; Redactor entropy pass.

## P3 (12)
Doc/clarity items folded into the shared-module workflow pass (stale docstrings, schema-version
note, determinism comments) where trivial; the remainder are deferred polish.

## Workstreams built this pass (verified by :backend:test, clean)
- **WS5 extraction persistence** — ExtractedEntities/Relations tables + ExtractionRepository +
  TranscriptChunker (speaker/timestamp aware) + tests.
- **WS6 cost/usage/quota** — UsageEventsTable + UsageRecorder + CostEstimator + QuotaGuard, wired
  into the diagram-job route (real cost estimate + month-to-date cap; 402 on breach) + tests.
- **WS9 GitHub read-only indexing** — GitHubAppService (App JWT → installation token), WebhookVerifier
  (HMAC-SHA256 + replay protection), IgnoreRules, GitHubIndexer (git tree → blobs → chunks),
  githubRoutes (signed webhook + index endpoint); MockEngine tests.
- **WS13 security** — SecretEntropy (Shannon-entropy secret detection); PromptInjectionGuard now
  invoked from PromptAssembler.split (source-isolation enforced for every provider, raises 400);
  TenantIsolationTest (cross-workspace reads denied).
- **WS14 eval harness** — EvalCorpus + EvalMetrics (entity/relation precision-recall + coverage gate
  per plan 21.3) + EvalRunner + test.
- **WS11 GitHub PR publishing** — GitHubPublisher (base ref → new branch → commit files → open PR,
  body = AI disclosure + evidence/validation summary + review checklist, NEVER auto-merges) +
  gitHubPublishRoutes; MockEngine test asserting the REST sequence + no merge.
- **WS12 audio transcription** — AudioTranscriptionService (OpenAI multipart `/v1/audio/transcriptions`,
  verbose_json → segments with ms/speaker) + TranscriptIngestor (transcribe → TranscriptChunker →
  persist transcript source w/ speaker/timestamp evidence) + transcriptionRoutes; MockEngine test.
- **WS15 ops** — Metrics registry (Prometheus text exposition at GET /metrics) + AdminRoutes
  (job diagnostics, workspace usage) + RetentionService (tenant-scoped data deletion) +
  docker-compose.yml + backend/Dockerfile; metrics/retention/admin tests.
- **WS8 workbench client** — `shared/workbench-client` Kotlin/JS module: typed PotatyApiClient over
  the /api/v1 contract (source→job→poll→version), pluggable HttpTransport + FetchTransport,
  JobPoller, WorkbenchController; pure-logic nodeTest (compile-verified end to end).

## Remaining honest caveats (code complete + compiled/tested here; NOT exercised against live infra)
- **WS8** the API-client module compiles + unit-tests, but the interactive *editor UI* itself isn't
  built/dogfooded — there is no headless-browser (Karma/Chrome) test harness in this environment.
- **WS11/WS12** are MockEngine-tested, not run against real GitHub write credentials / a real Whisper
  endpoint. **WS15** docker-compose/Dockerfile are provided but not deployed; metrics dashboards (UI)
  are out of scope. Object storage + a prompt-file registry remain backlog items.
