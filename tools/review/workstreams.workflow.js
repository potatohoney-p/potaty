export const meta = {
  name: 'potaty-workstream-buildout',
  description: 'Implement backend workstreams WS5/6/9/13/14 as self-contained new files + wiring specs',
  phases: [{ title: 'Build', detail: 'one agent per workstream, returns new files + wiring + tests' }],
}

const OUT_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['workstream', 'newFiles', 'newTestFiles', 'wiring', 'notes'],
  properties: {
    workstream: { type: 'string' },
    newFiles: {
      type: 'array',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['path', 'content'],
        properties: { path: { type: 'string' }, content: { type: 'string' } },
      },
    },
    newTestFiles: {
      type: 'array',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['path', 'content'],
        properties: { path: { type: 'string' }, content: { type: 'string' } },
      },
    },
    wiring: {
      type: 'array',
      description: 'Precise deltas to EXISTING shared files for the integrator to apply.',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['file', 'instruction'],
        properties: { file: { type: 'string' }, instruction: { type: 'string' } },
      },
    },
    notes: { type: 'string' },
  },
}

const COMMON =
  'You are implementing a workstream in the Potaty Kotlin/JVM Ktor backend (package root ' +
  'com.potaty.backend, under backend/src/main/kotlin/com/potaty/backend). This is JVM (NOT Kotlin/JS) ' +
  'so normal JVM stdlib is fine; use kotlinx.serialization, Exposed (org.jetbrains.exposed.sql.*), and ' +
  'the Ktor *client* (io.ktor.client.*) for outbound HTTP. \n\n' +
  'STUDY THESE EXISTING FILES FIRST for conventions and exact signatures you must match:\n' +
  '- backend/src/main/kotlin/com/potaty/backend/AppGraph.kt (composition root; how services are built)\n' +
  '- backend/src/main/kotlin/com/potaty/backend/persistence/Tables.kt (Exposed table style) and ' +
  'Database.kt (ALL_TABLES list, H2 SchemaUtils.create)\n' +
  '- backend/src/main/kotlin/com/potaty/backend/persistence/repositories/SourceRepository.kt (tenant-scoped repo style, TransactionContext.tx)\n' +
  '- backend/src/main/kotlin/com/potaty/backend/auth/TenantContext.kt, Rbac.kt (Permission)\n' +
  '- backend/src/main/kotlin/com/potaty/backend/api/SourceRoutes.kt, DiagramRoutes.kt (Route extension fn style, call.tenant(), ApiError)\n' +
  '- backend/src/main/kotlin/com/potaty/backend/llm/provider/LlmProvider.kt, anthropic/AnthropicProvider.kt (Ktor client + MockEngine-testable HTTP pattern)\n' +
  '- backend/src/main/kotlin/com/potaty/backend/config/AppConfig.kt (config style)\n' +
  '- backend/src/test/kotlin/com/potaty/backend/TestSupport.kt (testConfig + H2), ApiRoutesTest.kt, LlmProviderTest.kt (MockEngine), DiagramPipelineTest.kt\n\n' +
  'HARD RULES:\n' +
  '- Create ONLY NEW files. Do NOT edit any existing file. Express every change to an existing file ' +
  '(AppGraph, Database.ALL_TABLES, Application routing, AppConfig, build.gradle.kts, libs.versions.toml, ' +
  'a route handler call) as a `wiring` entry with the exact code to add and where. The integrator applies them.\n' +
  '- Put any new Exposed tables in their OWN new file (e.g. persistence/<Ws>Tables.kt); list them in `wiring` ' +
  'for Database.ALL_TABLES so H2 creates them. Columns: uuid/text/integer/timestamp only (H2-compatible; ' +
  'no jsonb/pgvector types — store JSON as text). Every tenant-owned table has workspace_id and every query filters by it.\n' +
  '- Prefer java stdlib crypto (javax.crypto.Mac HmacSHA256, java.security) over new dependencies. Only ' +
  'request a new dependency in `wiring` if truly unavoidable.\n' +
  '- Tests use kotlin.test + (where HTTP) io.ktor.client.engine.mock.MockEngine, runBlocking. Make them ' +
  'deterministic and runnable against H2 (testConfig()). Do NOT rely on network.\n' +
  '- DO NOT run gradle, builds, or tests (concurrent builds corrupt the Kotlin incremental cache). ' +
  'Reason about compilation carefully and return complete, compilable file contents.\n'

const WORKSTREAMS = [
  {
    key: 'WS9-github',
    prompt:
      'Implement WS9 — GitHub read-only repository indexing (plan sections 4.2, 18). Create package ' +
      'com.potaty.backend.github with: GitHubModels.kt (@Serializable DTOs for the tree/blob/webhook payloads you need); ' +
      'WebhookVerifier.kt (verify the X-Hub-Signature-256 HMAC-SHA256 over the raw body using javax.crypto.Mac, ' +
      'constant-time compare; plus delivery-id replay rejection via an in-memory seen-set); IgnoreRules.kt ' +
      '(default ignores for node_modules/build/dist/vendor/.git + *.min.js etc., plus parsing a .potatyignore string); ' +
      'GitHubAppService.kt (build a GitHub App JWT from a PEM private key with java.security RSA SHA256, exchange for an ' +
      'installation token via the Ktor client POST /app/installations/{id}/access_tokens); GitHubIndexer.kt (given an ' +
      'installation token + owner/repo/ref, fetch the git tree, apply IgnoreRules, fetch text blobs for high-signal files, ' +
      'and persist them as a Source + SourceVersion + chunks via SourceRepository — reuse SourceNormalizer/Chunker). ' +
      'GitHubRoutes.kt: fun Route.githubRoutes(...) with POST /github/webhook (verify signature, 204) and ' +
      'POST /projects/{projectId}/github/index (RBAC CREATE_SOURCE; index a repo, return the sourceVersionId). ' +
      'Tests: WebhookVerifierTest (known HMAC vector), IgnoreRulesTest, GitHubIndexerTest (MockEngine returning a small ' +
      'fake tree+blobs -> asserts chunks persisted). Wiring: GitHubConfig in AppConfig, AppGraph builds the services, ' +
      'Application mounts githubRoutes.',
  },
  {
    key: 'WS6-usage-quota',
    prompt:
      'Implement WS6 cost/usage/quota (plan sections 3.4, 22.1). New files: persistence/UsageTables.kt ' +
      '(UsageEventsTable: id, workspace_id, job_id nullable, provider, model, stage, input_tokens, output_tokens, ' +
      'estimated_cost_usd as text or double, created_at); usage/UsageRecorder.kt (tenant-scoped insert + a sum-cost-this-month ' +
      'query); cost/CostEstimator.kt (estimate (lowUsd, highUsd) from approximate source token counts x a per-ModelTier ' +
      'price table, pure + unit-testable); cost/QuotaGuard.kt (throw a QuotaExceededException when a workspace month-to-date ' +
      'cost + the new estimate would exceed a configured cap). Tests: CostEstimatorTest, QuotaGuardTest (H2). Wiring: add ' +
      'UsageEventsTable to Database.ALL_TABLES; AppGraph builds UsageRecorder/CostEstimator/QuotaGuard; DiagramRoutes ' +
      'diagram-job POST computes estimatedCostRange via CostEstimator and calls QuotaGuard before enqueue; a ' +
      'QuotaExceededException -> HTTP 402/429 handler in Application StatusPages.',
  },
  {
    key: 'WS5-extraction',
    prompt:
      'Implement WS5 extraction persistence + transcript handling (plan sections 7.1, 8.3, 12.2, 14.2). New files: ' +
      'persistence/ExtractionTables.kt (ExtractedEntitiesTable + ExtractedRelationsTable per plan 8.3, workspace+project scoped, ' +
      'evidence/metadata as text); persistence/ExtractionRepository.kt (tenant-scoped save/list for entities & relations); ' +
      'source/TranscriptChunker.kt (chunk a transcript: detect "Speaker: text" turns and [hh:mm:ss] / (mm:ss) timestamps, ' +
      'producing chunks carrying speaker + startMs/endMs; falls back to paragraph chunking). Tests: ExtractionRepositoryTest (H2), ' +
      'TranscriptChunkerTest. Wiring: add the two tables to Database.ALL_TABLES; note where DiagramPipeline could persist ' +
      'extraction (ExtractionRepository.save...) without breaking the existing flow.',
  },
  {
    key: 'WS13-security',
    prompt:
      'Implement WS13 security hardening + tests (plan section 20). New files: security/SecretEntropy.kt (Shannon-entropy ' +
      'helper that flags long high-entropy tokens as probable secrets; pure + testable); test TenantIsolationTest.kt ' +
      '(two workspaces via two dev tokens — seed a second token through a second AppGraph/config; create a diagram in ws A ' +
      'via the pipeline, assert ws B gets 404 on GET version, and that source/job reads are workspace-isolated); test ' +
      'PromptInjectionGuardTest.kt (assert PromptInjectionGuard.assertSourceIsolation raises PromptInjectionException when ' +
      'untrusted source text is placed in a SYSTEM/DEVELOPER part, and passes when fenced as SOURCE_DATA); test ' +
      'SecretEntropyTest.kt. Read security/PromptInjectionGuard.kt + Redactor.kt + provider/PromptAssembler.kt first. ' +
      'Wiring: have Redactor optionally consult SecretEntropy (describe the small edit); ensure assertSourceIsolation is ' +
      'invoked from PromptAssembler.split (describe the edit).',
  },
  {
    key: 'WS14-eval',
    prompt:
      'Implement WS14 evaluation harness (plan section 21). New package com.potaty.backend.eval: EvalCorpus.kt ' +
      '(2-3 in-code fixtures: a source text + required node labels + required edges + forbidden claims); EvalMetrics.kt ' +
      '(precision/recall for entities & relations, node/edge evidence coverage, plus pass/fail gate vs plan 21.3 thresholds: ' +
      'node coverage >= 0.9, edge coverage >= 0.8, forbidden-claim count == 0); EvalRunner.kt (runs DiagramPipeline.generate ' +
      'on each fixture against an H2 AppGraph and computes EvalMetrics). Test: EvalRunnerTest runs the corpus and asserts the ' +
      'deterministic pipeline meets the gate on the fixtures. Read DiagramPipeline.kt, EntityRelationExtractor.kt, ' +
      'IrAssembler.kt, AppGraph.kt, TestSupport.kt first. Wiring: none required beyond test wiring (eval is a tool/library).',
  },
]

const results = await parallel(
  WORKSTREAMS.map((w) => () =>
    agent(`${COMMON}\n\n=== YOUR WORKSTREAM: ${w.key} ===\n${w.prompt}`, {
      label: w.key,
      phase: 'Build',
      schema: OUT_SCHEMA,
    }),
  ),
)

return { workstreams: results.filter(Boolean) }
