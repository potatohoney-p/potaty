export const meta = {
  name: 'potaty-final-workstreams',
  description: 'Build WS11 (GitHub PR publish), WS12 (audio transcription), WS15 (ops/admin), WS8 (JS API client)',
  phases: [{ title: 'Build', detail: 'one agent per workstream: self-contained new files + wiring + tests' }],
}

const OUT_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['workstream', 'newFiles', 'newTestFiles', 'wiring', 'notes'],
  properties: {
    workstream: { type: 'string' },
    newFiles: { type: 'array', items: { type: 'object', additionalProperties: false, required: ['path', 'content'], properties: { path: { type: 'string' }, content: { type: 'string' } } } },
    newTestFiles: { type: 'array', items: { type: 'object', additionalProperties: false, required: ['path', 'content'], properties: { path: { type: 'string' }, content: { type: 'string' } } } },
    wiring: { type: 'array', items: { type: 'object', additionalProperties: false, required: ['file', 'instruction'], properties: { file: { type: 'string' }, instruction: { type: 'string' } } } },
    notes: { type: 'string' },
  },
}

const BACKEND_COMMON =
  'Potaty Kotlin/JVM Ktor backend (com.potaty.backend, backend/src/main/kotlin/...). JVM — normal ' +
  'stdlib OK; use kotlinx.serialization, Exposed, and the Ktor CLIENT (io.ktor.client.*) for outbound HTTP. ' +
  'STUDY for conventions/signatures: AppGraph.kt, Application.kt (routing + StatusPages), ' +
  'github/GitHubAppService.kt + GitHubIndexer.kt + GitHubModels.kt + GitHubRoutes.kt (Ktor client + MockEngine ' +
  'pattern, Route extension fns, ApiError, call.tenant(), Rbac/Permission), persistence/Tables.kt + Database.kt ' +
  '(ALL_TABLES) + repositories/*, llm/openai/OpenAiProvider.kt + provider/LlmProvider.kt (TranscriptionInput/' +
  'TranscriptArtifact/TranscriptSegment, CredentialStore.open), config/AppConfig.kt, and test files ' +
  '(TestSupport.kt, ApiRoutesTest.kt, LlmProviderTest.kt with MockEngine).\n' +
  'RULES: create ONLY NEW files; express edits to existing files as `wiring` entries (exact code + location) ' +
  'for the integrator. New Exposed tables go in their own file + a wiring entry for Database.ALL_TABLES ' +
  '(uuid/text/integer/timestamp only; workspace_id on every tenant table). Prefer java stdlib over new deps. ' +
  'Tests: kotlin.test + MockEngine + H2 testConfig(); deterministic; NO network. ' +
  'DO NOT run gradle/builds/tests (concurrent builds corrupt the Kotlin cache) — reason about compilation and ' +
  'return complete, compilable files.'

const WORKSTREAMS = [
  {
    key: 'WS11-github-publish',
    prompt:
      BACKEND_COMMON + '\n\n=== WS11 — GitHub PR publishing (plan 18.6, 4.2 PR publish) ===\n' +
      'Create com.potaty.backend.github.GitHubPublisher: given an installation token + owner/repo, it (1) gets the ' +
      'base branch head SHA, (2) creates a new branch ref, (3) commits one or more files (create/update blob via ' +
      'PUT /repos/{o}/{r}/contents/{path} with base64 content on the new branch), and (4) opens a pull request ' +
      '(POST /repos/{o}/{r}/pulls) whose body includes the AI-generated disclosure + validation/evidence summary + ' +
      'a review checklist (plan 18.6). NEVER auto-merge. Add the request/response @Serializable DTOs you need to ' +
      'GitHubModels (a NEW file github/GitHubPublishModels.kt to avoid editing the existing one). Add ' +
      'GitHubPublishRoutes.kt: fun Route.gitHubPublishRoutes(appService, publisher) with ' +
      'POST /diagrams/{diagramId}/versions/{versionId}/github/pr (RBAC PUBLISH_PR; loads the stored IR via the ' +
      'graph, compiles markdown+mermaid, publishes, returns the PR url). Tests with MockEngine simulating the ' +
      'GitHub REST sequence -> asserts a PR is opened with the disclosure/checklist body and no merge call. ' +
      'Wiring: AppGraph builds GitHubPublisher (nullable, only when config.github.enabled); Application mounts ' +
      'gitHubPublishRoutes; the route needs access to graph.diagrams + graph.diagramPipeline to render — pass the ' +
      'graph or the needed collaborators.',
  },
  {
    key: 'WS12-audio',
    prompt:
      BACKEND_COMMON + '\n\n=== WS12 — audio transcription (plan 14.4, 4.3) ===\n' +
      'Create com.potaty.backend.transcription.AudioTranscriptionService: POST multipart/form-data to ' +
      '{openAiBaseUrl}/v1/audio/transcriptions (Ktor client MultiPartFormDataContent: file part + model + ' +
      'response_format=verbose_json), Authorization: Bearer <api key from CredentialStore>, parse verbose_json into ' +
      'a TranscriptArtifact (text + segments with startMs/endMs/speaker) reusing the existing provider types ' +
      'where possible (TranscriptArtifact/TranscriptSegment in llm/provider/LlmProvider.kt). Add a ' +
      'TranscriptionRoutes.kt: fun Route.transcriptionRoutes(...) with POST /projects/{projectId}/transcription ' +
      '(RBAC CREATE_SOURCE) accepting an audio object key/bytes + credential id, transcribing, then ingesting the ' +
      'transcript text as a TRANSCRIPT source (normalize + TranscriptChunker + persist via SourceRepository, ' +
      'preserving speaker/timestamp evidence). Tests: MockEngine returns a canned verbose_json -> asserts segments ' +
      'parsed (ms/speaker) and a transcript source version + chunks are created (H2). Wiring: AppGraph builds the ' +
      'service; Application mounts transcriptionRoutes. Do NOT edit OpenAiProvider (build this as a standalone service).',
  },
  {
    key: 'WS15-ops',
    prompt:
      BACKEND_COMMON + '\n\n=== WS15 — operations: metrics, admin diagnostics, retention, deploy (plan 22-23, 20.6) ===\n' +
      'Create: observability/Metrics.kt (a tiny in-process counter/gauge registry — jobs_created/completed/failed, ' +
      'llm_tokens, render_failures, secret_scan_hits — thread-safe via java.util.concurrent.atomic; exposes a ' +
      'Prometheus text-format render() ); api/MetricsRoutes.kt (GET /metrics, unauthenticated text/plain Prometheus ' +
      'exposition); api/AdminRoutes.kt (RBAC MANAGE_WORKSPACE: GET /admin/jobs/{jobId} diagnostics from JobRepository, ' +
      'GET /admin/usage workspace month-to-date cost via UsageRecorder); ops/RetentionService.kt (tenant-scoped ' +
      'deletion: delete a workspace’s sources/source_versions/chunks/diagrams/versions/jobs/usage via Exposed ' +
      'deleteWhere, for plan 20.6 data deletion). Also create deploy artifacts at repo root as NEW files: ' +
      'docker-compose.yml (postgres + the backend) and backend/Dockerfile (JVM image running the application ' +
      'plugin distribution). Tests: MetricsTest (counter increments + Prometheus format), RetentionServiceTest ' +
      '(create rows in H2, delete, assert gone + tenant-scoped), AdminRoutes via testApplication. Wiring: AppGraph ' +
      'builds Metrics + RetentionService; Application mounts metricsRoutes (outside /api/v1) + adminRoutes (under ' +
      '/api/v1); optionally increment Metrics from JobWorkerPool (describe as wiring, keep optional).',
  },
  {
    key: 'WS8-client',
    prompt:
      'Build WS8 — a browser-consumable API client for the Potaty backend, as a NEW Kotlin/JS module ' +
      '`shared/workbench-client` (package com.potaty.workbench). It must COMPILE under this repo (Kotlin 1.8.20, ' +
      'Gradle). Look at shared/diagram-ir/build.gradle.kts (MPP) and shared/renderer-codegen for module conventions, ' +
      'and shared/diagram-ir/src/main/kotlin/com/potaty/ir/Ir.kt for the DiagramIR type it will deserialize.\n' +
      'Create shared/workbench-client/build.gradle.kts: a `kotlin("js")` + `kotlin("plugin.serialization")` module ' +
      'with `js(org.jetbrains.kotlin.gradle.plugin.KotlinJsCompilerType.IR) { nodejs() }`, depending on ' +
      'projects.diagramIr + libs.kotlinx.serialization.json + libs.kotlin.stdlib.js + (test) libs.kotlin.test.js. ' +
      'Create src/main/kotlin/com/potaty/workbench/PotatyApiClient.kt: a suspend client over the browser fetch API ' +
      '(use kotlin.js.Promise/org.w3c.fetch via kotlinx.coroutines.await, or kotlinx.browser) with methods ' +
      'createSource(projectId, sourceType, displayName, content), createDiagramJob(projectId, request, ' +
      'idempotencyKey), getJob(jobId), getDiagramVersion(diagramId, versionId) — sending Authorization: Bearer and ' +
      'parsing JSON responses into @Serializable DTOs (define them in the module, mirroring the backend /api/v1 ' +
      'shapes; reuse com.potaty.ir.DiagramIR for the ir field). Create WorkbenchController.kt: a small ' +
      'orchestrator (generateFromText: createSource -> createDiagramJob -> poll getJob -> getDiagramVersion) that ' +
      'returns the rendered mermaid + validation summary, suitable for wiring into the existing editor UI. Add a ' +
      'pure-logic test (no DOM/network): e.g. JSON (de)serialization of the DTOs round-trips, and a tiny pollable ' +
      'state-machine test — runnable on nodeTest. DO NOT modify the existing app; this is an additive library ' +
      'module. Wiring: register the module in settings.gradle.kts moduleMap (\"workbench-client\" to ' +
      '\"shared/workbench-client\"). DO NOT run gradle.',
  },
]

const results = await parallel(
  WORKSTREAMS.map((w) => () =>
    agent(w.prompt, { label: w.key, phase: 'Build', schema: OUT_SCHEMA }),
  ),
)
return { workstreams: results.filter(Boolean) }
