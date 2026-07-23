export const meta = {
  name: 'potaty-p2p3-shared-fixes',
  description: 'Craft conservative, behavior-preserving fixes for the shared-module P2/P3 findings',
  phases: [
    { title: 'Fix', detail: 'one agent per shared module proposes full updated files + tests' },
    { title: 'Verify', detail: 'adversarially check each proposal preserves behavior' },
  ],
}

const FIX_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['module', 'changedFiles', 'newTestFiles', 'triage'],
  properties: {
    module: { type: 'string' },
    changedFiles: {
      type: 'array',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['path', 'fullContent', 'rationale'],
        properties: {
          path: { type: 'string', description: 'repo-relative path' },
          fullContent: { type: 'string', description: 'COMPLETE new file content, compilable as-is' },
          rationale: { type: 'string' },
        },
      },
    },
    newTestFiles: {
      type: 'array',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['path', 'fullContent'],
        properties: { path: { type: 'string' }, fullContent: { type: 'string' } },
      },
    },
    triage: {
      type: 'array',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['finding', 'decision', 'note'],
        properties: {
          finding: { type: 'string' },
          decision: { type: 'string', enum: ['fixed', 'skipped_by_design', 'deferred'] },
          note: { type: 'string' },
        },
      },
    },
  },
}

const VERDICT_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['safe', 'concerns'],
  properties: {
    safe: { type: 'boolean', description: 'true if the changes are additive/behavior-preserving and plausibly compile' },
    concerns: { type: 'array', items: { type: 'string' } },
  },
}

const MODULES = [
  {
    key: 'diagram-ir',
    path: 'shared/diagram-ir/src/main/kotlin/com/potaty/ir',
    testPath: 'shared/diagram-ir/src/test/kotlin/com/potaty/ir',
  },
  {
    key: 'layout-engine',
    path: 'shared/layout-engine/src/main/kotlin/com/potaty/layout',
    testPath: 'shared/layout-engine/src/test/kotlin/com/potaty/layout',
  },
  {
    key: 'render-core',
    path: 'shared/render-core/src/main/kotlin/com/potaty/render',
    testPath: 'shared/render-core/src/test/kotlin/com/potaty/render',
  },
  {
    key: 'renderer-codegen',
    path: 'shared/renderer-codegen/src/main/kotlin/com/potaty/codegen',
    testPath: 'shared/renderer-codegen/src/test/kotlin/com/potaty/codegen',
  },
]

const CONSTRAINTS =
  'HARD CONSTRAINTS (this is Kotlin/JS — Kotlin 1.8.20):\n' +
  '- Changes MUST be additive and behavior-preserving. Do NOT change existing public function ' +
  'signatures, existing @SerialName wire values, or the DEFAULT rendering output (existing golden ' +
  'tests and the diagram-demo golden must still pass). New data-class fields MUST have defaults.\n' +
  '- Kotlin/JS limits: no inline (?i) regex flags (use RegexOption.IGNORE_CASE); no enum .entries ' +
  '(use .values()); no JVM-only stdlib.\n' +
  '- Prefer small, surgical changes. For findings that would require a breaking change or a large ' +
  'feature, mark them decision=deferred with a note rather than forcing them.\n' +
  '- Return the COMPLETE new content for every file you change (it will be written verbatim), and ' +
  'full content for any new test file. Tests use kotlin.test (import kotlin.test.*). ' +
  'The test source root for this module is the testPath given.\n' +
  '- Every finding you were given MUST appear in triage with a decision.'

const results = await pipeline(
  MODULES,
  (m) =>
    agent(
      `You are improving the Potaty "${m.key}" shared module. Read tools/review/FINDINGS.md and ` +
        `select every P2/P3 finding whose file is under "${m.path}" (the area label may be ` +
        `"${m.key}" or "render-core+ascii"/"layout-engine"). Read the actual source files under ` +
        `${m.path}. Implement the worthwhile fixes.\n\n${CONSTRAINTS}\n\n` +
        `Module main source: ${m.path}\nModule test source root: ${m.testPath}\n\n` +
        `Good fixes to prioritise (high product value, low risk): adding new StyleProfile variants ` +
        `(slate/sage/terracotta/monochrome) additively; adding new LayoutQualityScore metrics ` +
        `(symmetryScore, visualDensityScore) as new fields with the scorer populating them; adding ` +
        `additive EvidenceCoverage metrics (confidence-weighted / buckets); adding an additive ` +
        `userConfirmed:Boolean=false field to IrNode and having R009 accept evidence||userConfirmed||userModified; ` +
        `fixing stale doc comments; and adding focused unit tests (e.g. CycleDetector order-independence). ` +
        `Keep isAcceptable() thresholds permissive enough that current fixtures still pass.`,
      { label: `fix:${m.key}`, phase: 'Fix', schema: FIX_SCHEMA },
    ),
  (proposal, m) => {
    if (!proposal || proposal.changedFiles.length === 0) return proposal
    const summary = proposal.changedFiles.map((f) => `FILE ${f.path}:\n${f.fullContent}`).join('\n\n---\n\n')
    return agent(
      `Adversarially verify these proposed changes to the Potaty "${m.key}" module are SAFE: ` +
        `additive, behavior-preserving (no changed public signatures, no changed @SerialName, no ` +
        `changed default rendering output), Kotlin/JS-compatible (no (?i), no enum .entries), and ` +
        `plausibly compile. If anything looks like it could break existing tests or change default ` +
        `output, list it as a concern.\n\n${summary}`,
      { label: `verify:${m.key}`, phase: 'Verify', schema: VERDICT_SCHEMA },
    ).then((v) => ({ ...proposal, verdict: v }))
  },
)

return {
  modules: results.filter(Boolean).map((r) => ({
    module: r.module,
    changedFiles: r.changedFiles,
    newTestFiles: r.newTestFiles,
    triage: r.triage,
    verdict: r.verdict || { safe: true, concerns: [] },
  })),
}
