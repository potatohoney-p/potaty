export const meta = {
  name: 'potaty-p0p3-review',
  description: 'Adversarial P0–P3 review of the new Potaty source-grounded code (shared + backend), verified',
  phases: [
    { title: 'Review', detail: 'one reviewer per code area, structured findings' },
    { title: 'Verify', detail: 'adversarially confirm each P0/P1 finding is real' },
  ],
}

const FINDINGS_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['area', 'findings'],
  properties: {
    area: { type: 'string' },
    findings: {
      type: 'array',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['title', 'file', 'severity', 'category', 'description', 'suggestedFix'],
        properties: {
          title: { type: 'string' },
          file: { type: 'string', description: 'repo-relative path, with :line if known' },
          severity: { type: 'string', enum: ['P0', 'P1', 'P2', 'P3'] },
          category: {
            type: 'string',
            enum: ['correctness', 'security', 'completeness', 'aesthetics', 'reliability', 'api-contract', 'performance'],
          },
          description: { type: 'string' },
          evidence: { type: 'string' },
          suggestedFix: { type: 'string' },
        },
      },
    },
  },
}

const VERDICT_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['isReal', 'confidence', 'refinedSeverity', 'reasoning'],
  properties: {
    isReal: { type: 'boolean' },
    confidence: { type: 'number' },
    refinedSeverity: { type: 'string', enum: ['P0', 'P1', 'P2', 'P3', 'invalid'] },
    reasoning: { type: 'string' },
  },
}

const AREAS = [
  { key: 'diagram-ir', path: 'shared/diagram-ir', focus: 'IR model invariants, validator rules R001-R017, patch/diff correctness, evidence coverage scoring, JSON round-trip, determinism on Kotlin/JS' },
  { key: 'layout-engine', path: 'shared/layout-engine', focus: 'layout correctness (no overlaps, edge routing, self-loops), quality scoring thresholds, determinism, Kotlin/JS pitfalls (no .entries, no inline regex flags)' },
  { key: 'render-core+ascii', path: 'shared/render-core and shared/renderer-ascii', focus: 'IR->shape mapping, group label draw order, ASCII output aesthetics vs output_sample, engine reuse correctness' },
  { key: 'renderer-codegen', path: 'shared/renderer-codegen', focus: 'Mermaid/D2/PlantUML/Graphviz/Markdown correctness, label escaping (injection), IdentAllocator injectivity, securityLevel-safe output' },
  { key: 'backend-llm', path: 'backend/src/main/kotlin/com/potaty/backend/llm', focus: 'provider abstraction, credential handling (never to browser), forced-tool/json_schema shaping, structured caller, model router, what is stubbed vs real' },
  { key: 'backend-jobs', path: 'backend/src/main/kotlin/com/potaty/backend/jobs', focus: 'job queue (FOR UPDATE SKIP LOCKED), idempotency, retry policy, pipeline stage contract, worker pool, what is stub passthrough' },
  { key: 'backend-persistence', path: 'backend/src/main/kotlin/com/potaty/backend/persistence', focus: 'tenant scoping on EVERY query (cross-tenant leak risk), Exposed correctness, migration/runtime DB coupling, no in-memory/test path' },
  { key: 'backend-api', path: 'backend/src/main/kotlin/com/potaty/backend/api', focus: 'route contracts vs plan section 10, RBAC enforcement, DTO validation, stub responses, auth/TenantContext wiring gap' },
  { key: 'backend-security', path: 'backend/src/main/kotlin/com/potaty/backend/security', focus: 'secret/PII redaction coverage, prompt-injection guard efficacy, SVG sanitizer completeness, regex correctness' },
  { key: 'backend-auth', path: 'backend/src/main/kotlin/com/potaty/backend/auth', focus: 'RBAC model, TenantContext resolution, the unwired SessionAuth (routes 401), privilege checks' },
]

const results = await pipeline(
  AREAS,
  (a) =>
    agent(
      `You are a meticulous staff engineer doing an adversarial code review of the Potaty "anything-to-diagram" codebase.\n` +
        `Review ONLY this area: ${a.path}\n` +
        `Focus: ${a.focus}\n\n` +
        `The binding spec is the implementation plan at llm_anything_to_diagram_service_plan.md in the repository root ` +
        `(sections 8 DDL, 9 IR v1.1, 10 API, 11 pipeline, 15 LLM, 17 renderer security, 20 security). ` +
        `Read the relevant area files and judge them against that plan.\n\n` +
        `Report concrete, actionable findings. Severity rubric:\n` +
        `  P0 = correctness/security defect that produces wrong output, a crash, a data/tenant leak, or an injection.\n` +
        `  P1 = a feature the plan REQUIRES that is missing/stubbed and blocks the core flow, or a serious robustness gap.\n` +
        `  P2 = meaningful quality/robustness/aesthetic gap that should be fixed for production.\n` +
        `  P3 = minor polish, naming, docs, nice-to-have.\n` +
        `Be specific with file paths and (where possible) line numbers. Do not invent issues; if the code is correct, return fewer findings. ` +
        `Distinguish "intentionally stubbed for a later workstream" (completeness P1/P2) from "actually broken" (correctness P0).\n` +
        `IMPORTANT: review by READING source files only. Do NOT run gradle, builds, or tests (a build is running concurrently and the lock must not be contended).`,
      { label: `review:${a.key}`, phase: 'Review', schema: FINDINGS_SCHEMA, agentType: 'Explore' },
    ),
  (review, a) => {
    if (!review || !review.findings) return { area: a.key, findings: [] }
    const critical = review.findings.filter((f) => f.severity === 'P0' || f.severity === 'P1')
    return parallel(
      critical.map((f) => () =>
        agent(
          `Adversarially verify this code-review finding in the Potaty repo. Try to REFUTE it.\n` +
            `Area: ${a.path}\nFinding: ${f.title}\nFile: ${f.file}\nSeverity claimed: ${f.severity}\n` +
            `Description: ${f.description}\nEvidence cited: ${f.evidence || '(none)'}\n\n` +
            `Read the actual file(s) and decide if the defect is real and the severity is right. ` +
            `If the code is actually correct or the "missing" thing exists elsewhere, mark isReal=false / refinedSeverity=invalid. ` +
            `Default to skepticism. Review by READING source only; do NOT run gradle/builds/tests.`,
          { label: `verify:${a.key}:${f.file}`, phase: 'Verify', schema: VERDICT_SCHEMA, agentType: 'Explore' },
        ).then((v) => ({ ...f, area: a.key, verdict: v })),
      ),
    ).then((verified) => ({
      area: a.key,
      findings: [
        ...verified.filter(Boolean),
        ...review.findings.filter((f) => f.severity === 'P2' || f.severity === 'P3').map((f) => ({ ...f, area: a.key, verdict: null })),
      ],
    }))
  },
)

const all = results.filter(Boolean).flatMap((r) => r.findings || [])
const confirmed = all.filter((f) => !f.verdict || f.verdict.isReal)
const bySev = (s) => confirmed.filter((f) => (f.verdict?.refinedSeverity || f.severity) === s)

return {
  totalReported: all.length,
  confirmed: confirmed.length,
  P0: bySev('P0'),
  P1: bySev('P1'),
  P2: bySev('P2'),
  P3: bySev('P3'),
}
