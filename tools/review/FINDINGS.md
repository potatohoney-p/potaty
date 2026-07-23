# Potaty P0–P3 Review Findings (adversarially verified)

> Historical planning snapshot, superseded by the 2026-07-17 evidence-backed reviews in
> [`docs/reviews`](../../docs/reviews). File/line references and open/fixed status below describe an
> earlier implementation and must not be used as the current release decision.

Total reported: 106 · Confirmed: 90

## P0 (2)
1. **[P0|backend-api]** Missing TenantContext wiring in auth middleware — routes will crash with 401
   - file: `backend/src/main/kotlin/com/potaty/backend/Application.kt:112-113`
   - fix: Implement SessionAuth middleware and install it in Application.module() before routing. The middleware must: (1) extract authentication credential from request (JWT token, API key, or session cookie), (2) validate it, (3) resolve the associated workspaceId/userId/role, (4) construct TenantContext, (5) attach it via call.attributes.put(TenantContextKey, context). Until SessionAuth exists, routes are unusable.
2. **[P0|backend-auth]** SessionAuth middleware is completely unwired; all routes return 401
   - file: `backend/src/main/kotlin/com/potaty/backend/Application.kt:112-113`
   - fix: Immediately implement a SessionAuth Ktor plugin that extracts credentials from the Authorization header or session cookie and populates TenantContextKey. This is blocking all authenticated routes. For now, a minimal stub that reads a Bearer token and looks up user/workspace from a session table (or a test mock for local dev) would unblock route testing. The plan section 5 does not specify OAuth vs JWT vs sessions; align that choice with the broader auth design.

## P1 (11)
1. **[P1|backend-llm]** OpenAI and Anthropic HTTP implementations are stubbed but well-architected
   - file: `openai/OpenAiProvider.kt, anthropic/AnthropicProvider.kt`
   - fix: Implement the four TODO methods in both providers following the commented guidance. For OpenAI: use response_format json_schema (strict mode) for structured calls. For Anthropic: use forced tool use (single tool with input_schema matching the required schema, tool_choice forcing that tool). Both should use ProviderErrorMapper to classify HTTP errors. Add integration tests with mock HTTP servers or provider sandbox keys.
2. **[P1|backend-llm]** Credential master key uses local dev AES/GCM; production KMS integration is stubbed
   - file: `auth/CredentialStore.kt`
   - fix: For production: Implement a CredentialStore variant that calls AWS KMS GenerateDataKey, decrypts using KMS Decrypt, and never materializes the master key in application memory. For development: the current implementation is acceptable. Add a guard in AppConfig that rejects non-KMS credentials in production environments, or at minimum warns in logs. Store the KMS key ARN in SecurityConfig, not a simple string.
3. **[P1|backend-llm]** No TokenUsage tracking back to usage_events table; CredentialStore seal/open lack audit logging
   - file: `provider/LlmResult.kt, auth/CredentialStore.kt`
   - fix: Add a UsageRecorder dependency to LlmResult consumers (or to a Job execution layer above). Pass the TokenUsage + metadata (job_id, workspace_id, provider, model, stage) to a persistence function. For CredentialStore.open, accept an optional auditContext and log the decryption event (without logging the plaintext secret) to an audit sink.
4. **[P1|backend-jobs]** Missing workspace isolation in PostgresJobQueue.claim() across tenants
   - file: `backend/src/main/kotlin/com/potaty/backend/jobs/PostgresJobQueue.kt:26-33`
   - fix: Add 'and workspace_id = ?' filter to the claim CTE. Update JobQueue.claim() signature to accept workspaceId parameter. Update the idx_jobs_poll index to (workspace_id, status, priority, run_after, locked_until) to allow efficient workspace-scoped claims. Regenerate tests to verify cross-workspace jobs are never claimed together.
5. **[P1|backend-jobs]** Worker pool does not track leaseSeconds expiry and does not extend leases
   - file: `backend/src/main/kotlin/com/potaty/backend/jobs/JobWorkerPool.kt:21-97`
   - fix: Either: (a) implement a background coroutine in the worker that extends the lease every 60 seconds while the stage is running, or (b) increase leaseSeconds to a value appropriate for the longest expected stage (e.g., 600 or 900 seconds for LLM stages), or (c) add a monitoring/heartbeat mechanism. Include a test that simulates a job running past lease expiry and verifies duplicate prevention via idempotency key.
6. **[P1|backend-jobs]** Application.kt does not wire JobWorkerPool or JobQueue at startup
   - file: `backend/src/main/kotlin/com/potaty/backend/Application.kt:38-122`
   - fix: In the Application module: (1) instantiate a DataSource from AppConfig, (2) create a PostgresJobQueue(dataSource), (3) create a JobWorkerPool with the queue and a pipeline factory, (4) call pool.start() after the Ktor server is initialized, (5) register a shutdown hook to call pool.stop() when the server shuts down. Add integration tests that verify a job enqueued via the API is claimed and processed by the worker pool.
7. **[P1|backend-persistence]** DiagramVersionRecord does not include source_snapshot or model_trace JSON
   - file: `backend/src/main/kotlin/com/potaty/backend/persistence/repositories/DiagramRepository.kt:30-39`
   - fix: Add sourceSnapshotJson and modelTraceJson fields to DiagramVersionRecord. Update the appendVersion() method's return statement to include them. Update findVersion() to deserialize and include them in the returned record. This is necessary for plan 11.5 'durable artifacts' and 19.5 'version diff' which require source snapshots to be retrievable.
8. **[P1|backend-api]** POST diagram-jobs lacks sourceVersionIds validation — can be empty or reference missing sources
   - file: `backend/src/main/kotlin/com/potaty/backend/api/Dtos.kt:38-45, DiagramRoutes.kt:40-52`
   - fix: Add validation in DiagramRoutes after call.receive(): if (body.sourceVersionIds.isEmpty()) throw ValidationException('sourceVersionIds cannot be empty'). Also resolve each sourceVersionId via SourceRepository.findVersion(tenant.workspaceId, sourceVersionId) and reject any that don't exist or belong to a different workspace.
9. **[P1|backend-security]** SVG Sanitizer uses regex-based stripping instead of XML parsing/allowlist
   - file: `backend/src/main/kotlin/com/potaty/backend/security/SvgSanitizer.kt:1-42`
   - fix: Replace regex-based sanitization with an XML DOM parser (e.g., `xmlpull` or `jsoup` on JVM if available to Kotlin; or a hand-written recursive descent parser for the subset of SVG we allow). Implement an explicit allowlist of safe SVG elements (svg, g, path, rect, line, text, circle, polygon, polyline, defs, style/text-only, title, desc, etc.) and a smaller allowlist of safe attributes per element (id, class, d, points, x, y, width, height, fill, stroke, etc.). Reject everything else. This is security-critical for export delivery and browser preview rendering.
10. **[P1|backend-security]** SvgSanitizer does not explicitly remove <style> tags which can contain script-like CSS
   - file: `backend/src/main/kotlin/com/potaty/backend/security/SvgSanitizer.kt:16-37`
   - fix: Add an explicit removal rule for `<style>` tags: `private val styleBlock = Regex("""<style\b[^>]*>[\s\S]*?</style>""", RegexOption.IGNORE_CASE)` and call `strip(styleBlock, "")` in the sanitize() function. Alternatively, if styles are needed for output, parse the `<style>` content and validate that it contains only safe CSS (no `@import`, no `-moz-binding`, no `behavior`, no `expression()`, no `javascript:`, no `data:text/html`). Given that SVG output from Potaty should be self-contained and not rely on external styles, removing `<style>` entirely is the safest approach.
11. **[P1|backend-security]** Export paths not integrated with sanitization and escaping; SVG Sanitizer and LabelEscaper security checks not called
   - file: `backend/src/main/kotlin/com/potaty/backend/api/DiagramRoutes.kt:108`
   - fix: Implement the export endpoint to: (1) load the diagram IR from the database, (2) for each requested format, call the appropriate renderer (MermaidCompiler, D2Compiler, etc. from renderer-codegen module once it is MPP-shared), (3) if SVG, call SvgSanitizer.sanitize() and record the removed count in logs/metrics, (4) store renderings in the renderings table with format/content_hash/status, (5) return the rendering content or object key. Add tests that verify: secret/PII patterns are escaped in Mermaid labels, SVG scripts are removed, Markdown special chars are escaped, and no raw HTML is emitted.

## P2 (65)
1. **[P2|diagram-ir]** MarkInferred operation does not preserve or validate evidence
   - file: `shared/diagram-ir/src/main/kotlin/com/potaty/ir/IrPatch.kt:148-154`
   - fix: Document the semantics of MarkInferred: either (a) add a parameter preserveEvidence: Boolean = true and apply it, or (b) explicitly state in the Ir.kt comment that evidence on LLM_INFERRED edges is retained for traceability but does not constitute grounding. Add a test case verifying the behavior.
2. **[P2|diagram-ir]** EvidenceCoverage.edgeCoverage metric does not distinguish confidence strength
   - file: `shared/diagram-ir/src/main/kotlin/com/potaty/ir/EvidenceCoverage.kt:49-52`
   - fix: Either (a) add a weightedEdgeCoverage metric that penalizes low-confidence edges, or (b) document that edgeCoverage is binary ('has evidence') and add a separate 'confidenceWeightedEdgeCoverage' metric. Update the threshold logic in meetsThreshold() if applicable.
3. **[P2|diagram-ir]** IrPatcher.apply() silently deduplicates duplicate IDs without reporting
   - file: `shared/diagram-ir/src/main/kotlin/com/potaty/ir/IrPatch.kt:209-213`
   - fix: Modify apply() to return a Pair<DiagramIR, List<String>> where the second element is duplicate node/edge IDs that were removed. Or, enforce invariants in the patch operation builders so duplicates cannot be created. The applyAndValidate path is fine, but apply() should either fail or report.
4. **[P2|diagram-ir]** R009 accept logic conflates 'user-modified' with 'evidence-grounded'
   - file: `shared/diagram-ir/src/main/kotlin/com/potaty/ir/IrValidator.kt:152-162`
   - fix: Split userModified into two fields: userModified: Boolean (node was edited) and userConfirmed: Boolean (user explicitly confirmed this node, bypassing the evidence gate). Update R009 to check userConfirmed instead of userModified. Alternatively, document in IrNode that userModified == true implies implicit confirmation and update the plan to reflect this.
5. **[P2|diagram-ir]** MergeNodes operation silently removes self-loop edges without warning
   - file: `shared/diagram-ir/src/main/kotlin/com/potaty/ir/IrPatch.kt:194`
   - fix: Modify the patch operation to include a 'dropSelfLoops: Boolean = true' parameter and document the behavior. Alternatively, track dropped edges in a side channel or as a return value from apply().
6. **[P2|diagram-ir]** IrValidator.DEFAULT_SENSITIVE_PATTERNS may miss JS-incompatible regex features
   - file: `shared/diagram-ir/src/main/kotlin/com/potaty/ir/IrValidator.kt:278-285`
   - fix: Add a comment clarifying that patterns are Kotlin-side and are automatically transpiled correctly for JS. No code change needed, but document this explicitly to prevent future confusion.
7. **[P2|diagram-ir]** Determinism of CycleDetector.findCycle output is correct but fragile
   - file: `shared/diagram-ir/src/main/kotlin/com/potaty/ir/GraphUtil.kt:33-36`
   - fix: Add a test case in GraphUtil.kt verifying that findCycle() returns the same result regardless of nodeIds list order. This prevents future regressions.
8. **[P2|layout-engine]** Edge routing for swimlane does not verify message order determinism
   - file: `shared/layout-engine/src/main/kotlin/com/potaty/layout/SwimlaneLayoutEngine.kt:53-95`
   - fix: Add a validation check in IrValidator for sequence diagrams: if diagramType == SEQUENCE, verify that edges maintain topological order (i.e., no later edge has an earlier timestamp in metadata, or if metadata-less, at least warn that manual ordering is assumed). Add to SwimlaneLayoutEngine a comment: '// Assumes ir.edges are in order for sequence diagrams; IrValidator enforces this.' If ordering is not guaranteed, add a sort or explicit error.
9. **[P2|layout-engine]** Quality scoring threshold may not enforce all plan requirements
   - file: `shared/layout-engine/src/main/kotlin/com/potaty/layout/LayoutQualityScorer.kt:28-36`
   - fix: Add min/max visual density bounds to isAcceptable(). For example: 'val densityMin = 0.2; val densityMax = 0.95; return ... && (1.0 - unusedSpaceRatio) in densityMin..densityMax' or implement via a threshold-configuration object.
10. **[P2|layout-engine]** Determinism guarantee comment in CycleDetector may not fully document JS map ordering risks
   - file: `shared/diagram-ir/src/main/kotlin/com/potaty/ir/GraphUtil.kt:33-36`
   - fix: Add explicit tests in layout-engine test suite (if created in WS3) that verify the same IR produces identical LayoutResult on both Kotlin/JVM and Kotlin/JS by comparing serialized LayoutResult objects. Alternatively, add a doc comment to LayeredLayoutEngine.layout() and LayoutEngineFactory.forType() stating: 'Determinism is guaranteed only if the IR node list order is stable; if nodes are reordered, layout may change slightly due to layer assignment variance.'
11. **[P2|layout-engine]** Self-loop handling surfaces warning but behavior is incomplete per plan
   - file: `shared/layout-engine/src/main/kotlin/com/potaty/layout/LayeredLayoutEngine.kt:42-44`
   - fix: Add explicit IR-R011b or extend IR-R011 to specifically detect and warn about self-loops (for acyclic diagrams, reject; for cyclic diagrams, warn and exclude from layout). Alternatively, verify that the existing cycle detection error message is clear enough: 'cycle: A -> A' should be understandable as a self-loop.
12. **[P2|layout-engine]** Label wrapping algorithm may produce inconsistent width calculations
   - file: `shared/layout-engine/src/main/kotlin/com/potaty/layout/LabelFormatter.kt:19-31`
   - fix: Simplify to: 'val actualInner = lines.maxOf { it.length }.coerceIn(metrics.minInnerWidth, metrics.maxInnerWidth); return Wrapped(actualInner, lines)'. This ensures actualInner is always within [minInnerWidth, maxInnerWidth]. If a label line exceeds maxInnerWidth after wrapping, document that as a label validation issue (IR-R013 in IrValidator already exists) and reject it at validation time.
13. **[P2|layout-engine]** No enforcement of crossing count target formula across diagram sizes
   - file: `shared/layout-engine/src/main/kotlin/com/potaty/layout/LayoutQualityScorer.kt:34-35`
   - fix: Justify or document the crossing count formula. Either: (1) cite a reference (e.g., Sugiyama layout literature) that supports (n²/4 + 1); (2) define a table of thresholds by diagram size in plan section 16.4 and update the code; or (3) make the formula configurable via LayoutMetrics so different diagram types can have different limits. Add a unit test that verifies the formula produces reasonable results for small (5 nodes), medium (20 nodes), and large (100+ nodes) graphs.
14. **[P2|render-core+ascii]** IrShapeMapper class docstring z-order is incorrect/outdated
   - file: `shared/render-core/src/main/kotlin/com/potaty/render/IrShapeMapper.kt:26-27`
   - fix: Update the class docstring on line 26-27 to correctly state: 'group containers (back) -> instance "stack" shadows -> node boxes -> edges -> edge labels -> group labels (front)'.
15. **[P2|render-core+ascii]** Plan section 16.2 mentions TimelineLayoutEngine but implementation embeds it in SwimlaneLayoutEngine
   - file: `shared/layout-engine/src/main/kotlin/com/potaty/layout/LayoutEngine.kt:26`
   - fix: Refactor TimelineLayoutEngine into its own file (TimelineLayoutEngine.kt) to match plan section 16.2's layout engine separation. This improves modularity and clarity.
16. **[P2|render-core+ascii]** IrValidator R009 blocks publication if node has no evidence AND is not user-modified, but does not account for user confirmation
   - file: `shared/diagram-ir/src/main/kotlin/com/potaty/ir/IrValidator.kt:152-163`
   - fix: Either (1) rename userModified to userConfirmed and add metadata like confirmTime/confirmByUserId to match plan section 9.5, or (2) add a separate boolean userConfirmed field to IrNode and update R009 to check that instead of userModified. The current conflation is semantically imprecise.
17. **[P2|render-core+ascii]** IrShapeMapper.groupLabel hard-codes label positioning to top-left with fixed +2,+1 offset
   - file: `shared/render-core/src/main/kotlin/com/potaty/render/IrShapeMapper.kt:121-129`
   - fix: Enhance groupLabel() to (a) clip/wrap the label if it exceeds the group's width minus padding, (b) check for collision with member node labels, (c) consider shifting the label downward if the top row would overlap with a member node. Alternatively, move label sizing/wrapping into the layout engine (GridContainerLayoutEngine/LayeredLayoutEngine) so group label bounds are pre-computed during layout, not as an afterthought in the mapper.
18. **[P2|render-core+ascii]** LayoutQualityScorer.score() does not measure visual density or symmetry mentioned in plan 16.4
   - file: `shared/layout-engine/src/main/kotlin/com/potaty/layout/LayoutQualityScorer.kt:16-36`
   - fix: Add two new fields to LayoutQualityScore: symmetryScore (Double, range [0,1]) and visualDensityScore (Double, range [0,1]). Implement scoring logic: symmetry via axis-alignment checks (vertical/horizontal balance of node distribution), visual density as (usedArea / canvasArea). Update the isAcceptable() thresholds to include these new metrics per plan section 16.4 thresholds if needed.
19. **[P2|render-core+ascii]** EvidenceCoverageScorer counts inferred edges but does not expose confidence distribution (plan 21.2 eval metrics missing)
   - file: `shared/diagram-ir/src/main/kotlin/com/potaty/ir/EvidenceCoverage.kt:40-69`
   - fix: Extend EvidenceCoverage to include a confidenceDistribution map, e.g., 'confidenceByBucket: Map<String, Int>' with keys like '0.0-0.3', '0.3-0.7', '0.7-1.0'. Update EvidenceCoverageScorer.score() to populate this map. This enables fine-grained eval dashboards.
20. **[P2|render-core+ascii]** StyleProfile does not support all profiles mentioned in plan section 16.3
   - file: `shared/render-core/src/main/kotlin/com/potaty/render/StyleProfile.kt:30-56`
   - fix: Add the missing StyleProfile implementations: SLATE (darker colors, adjusted dashing), SAGE (muted greens), TERRACOTTA (warm earth tones), and MONOCHROME_DOCS (minimal styling for documentation). These should map to predefined border/stroke/anchor styles in the shape engine to guarantee consistency.
21. **[P2|render-core+ascii]** GridContainerLayoutEngine does not enforce plan section 12.2 layout-focused chunking (max_nodes_per_group underused)
   - file: `shared/layout-engine/src/main/kotlin/com/potaty/layout/GridContainerLayoutEngine.kt:42-64`
   - fix: Add a check in GridContainerLayoutEngine.layout() to warn or split groups that exceed a reasonable size (e.g., > 12 nodes). Alternatively, enforce the IR validator rule: add IR-R006 extension that checks group.nodeIds.size <= maxNodesPerGroup if specified, and emit a warning or error if violated.
22. **[P2|renderer-codegen]** PlantUML angle-bracket replacement loses semantic information but defeats creole injection
   - file: `shared/renderer-codegen/src/main/kotlin/com/potaty/codegen/LabelEscaper.kt:72-76`
   - fix: Document this lossy transformation explicitly in the javadoc. Consider an alternative: escape to Unicode entities like < / > (though PlantUML support may vary). For max compatibility with the creole injection risk, keep the current approach but add a comment explaining the tradeoff, or store the original label in metadata for reference.
23. **[P2|renderer-codegen]** MermaidCompiler sequence edge type check uses string equality instead of enum comparison
   - file: `shared/renderer-codegen/src/main/kotlin/com/potaty/codegen/MermaidCompiler.kt:148`
   - fix: Use direct enum comparison: `edge.type == EdgeType.RESPONSE`. If EdgeType doesn't have a RESPONSE member, define it or map the actual edge types (request/reply/etc.) to the correct Mermaid arrow semantics.
24. **[P2|renderer-codegen]** GraphvizCompiler cluster indices are not stable if groups are reordered
   - file: `shared/renderer-codegen/src/main/kotlin/com/potaty/codegen/GraphvizCompiler.kt:36-37`
   - fix: Use a stable group identifier instead of array index. Allocate group ids via IdentAllocator just like node ids, so `subgraph cluster_${ids.identify(group.id)}`. This ensures cluster names remain stable across reorderings.
25. **[P2|renderer-codegen]** LabelEscaper.markdown backslash escaping may double-escape already-escaped text
   - file: `shared/renderer-codegen/src/main/kotlin/com/potaty/codegen/LabelEscaper.kt:91-102`
   - fix: Document the limitation: 'Input is assumed to be raw text without pre-existing backslash escapes. If the IR may contain markdown-escaped text, the caller should normalize it first.' Alternatively, implement a lookahead check to detect and preserve existing escapes (though this is complex and likely not worth the effort for untrusted user input).
26. **[P2|backend-llm]** StructuredCaller correctly implements repair loop but delegates schema validation to caller
   - file: `provider/StructuredCaller.kt`
   - fix: Add an optional validate() helper that uses a JSON schema validator (e.g., everit-org/json-schema or similar) and wire it as a factory method. Document that callers MUST provide a schema-validating lambda. Consider making validation mandatory (non-optional) in a future hardening pass.
27. **[P2|backend-llm]** ApiKeyCredential does not include createdAt and lastUsedAt timestamps required by plan
   - file: `auth/LlmCredential.kt`
   - fix: Add createdAt: Instant and lastUsedAt: Instant? to the LlmCredential interface. Update ApiKeyCredential and ProviderOAuthCredential to include these fields with appropriate serialization. Default createdAt to Instant.now() on creation; initialize lastUsedAt to null and update it on successful provider calls (tracked by usage events).
28. **[P2|backend-llm]** ModelRouter does not enforce provider-to-model availability; null returns are not validated
   - file: `provider/ModelRouter.kt`
   - fix: Add a validation method to ModelRouter that runs at startup and asserts all ModelTiers have at least one provider with a configured model. Alternatively, add a best-effort fallback to a default provider if the preferred provider cannot serve the tier. Document tier availability constraints in config comments.
29. **[P2|backend-llm]** PromptPartRole enforcement for prompt injection defense is not mechanically enforced
   - file: `provider/LlmProvider.kt`
   - fix: Implement PromptInjectionGuard as a validator that runs before sending a StructuredGenerationInput to any provider. It should assert that any PromptPart with role in {SYSTEM_POLICY, DEVELOPER_INSTRUCTIONS} contains only safe, hard-coded developer text (no user input, no source chunks). Alternatively, create separate input types (SystemPrompt, UserPrompt) to make role separation unavoidable at the type level.
30. **[P2|backend-llm]** MockProvider generates deterministic stubs but does not conform to schema validation
   - file: `provider/MockProvider.kt`
   - fix: Either (1) parameterize MockProvider to accept a schema and return matching mock output (e.g., with required fields null-filled), or (2) document that MockProvider is only for placeholder integration tests and that real testing must use actual providers. For now, add a comment warning that MockProvider output is not schema-correct.
31. **[P2|backend-llm]** ProviderFactory caches provider instances but does not support credential rotation or refresh
   - file: `provider/ProviderFactory.kt`
   - fix: Either store the HTTP client and config in the factory but create new provider instances per call (if credentials are per-call), or add a credential refresh capability to LlmProvider (e.g., refreshCredential(credential): LlmCredential). Document which credentials support refresh and ensure the factory invalidates cached credentials when rotation happens.
32. **[P2|backend-llm]** OpenAiProvider should reject ProviderOAuthCredential explicitly
   - file: `openai/OpenAiProvider.kt`
   - fix: Explicitly check credential type and throw a clear error: 'OpenAI does not support provider OAuth. Use an API key credential (Console billing) for hosted Potaty, or self-hosted mode with API keys.' This makes the constraint obvious.
33. **[P2|backend-jobs]** RetryPolicy backoff does not account for workspace quota or provider rate limits
   - file: `backend/src/main/kotlin/com/potaty/backend/jobs/RetryPolicy.kt:11-43`
   - fix: Extend StageResult.RetryableFailure to include optional retryAfterSeconds or retryAfterHeader. Update FailureClassifier to parse provider error responses and extract Retry-After. Update JobWorkerPool.runJob() to prefer result.retryAfter over the backoff policy. Add a quota check before rescheduling: if the retry would exceed workspace monthly quota, fail fatally instead.
34. **[P2|backend-jobs]** Error JSON encoding is fragile and may produce invalid JSON on unescaped input
   - file: `backend/src/main/kotlin/com/potaty/backend/jobs/JobWorkerPool.kt:92-96`
   - fix: Replace manual JSON construction with buildJsonObject { put("kind", kind); put("reason", reason) } from kotlinx.serialization.json, or use Json.encodeToString(). This ensures all special characters are correctly escaped.
35. **[P2|backend-jobs]** PostgresJobQueue transactions do not isolate against concurrent workers
   - file: `backend/src/main/kotlin/com/potaty/backend/jobs/PostgresJobQueue.kt:138-150`
   - fix: Explicitly set isolation level to SERIALIZABLE or READ COMMITTED (depending on requirements). Add connection retry logic with exponential backoff in withConnection(). Consider wrapping all updates in a try-catch for transient failures (connection reset, deadlock) and retrying. Document the isolation assumptions.
36. **[P2|backend-jobs]** JobRepository.findByIdempotencyKeyInternal() is not guarded against race conditions
   - file: `backend/src/main/kotlin/com/potaty/backend/persistence/repositories/JobRepository.kt:122-129`
   - fix: Use the database unique constraint as the source of truth. Wrap the insert in a try-catch, and if a unique constraint violation occurs, re-query for the existing job and return it. Alternatively, use an INSERT ... ON CONFLICT DO UPDATE pattern if the query builder supports it.
37. **[P2|backend-jobs]** JobRepository.enqueue() does not enforce idempotency key uniqueness per the schema constraint
   - file: `backend/src/main/kotlin/com/potaty/backend/persistence/repositories/JobRepository.kt:51-74`
   - fix: Document the idempotency contract: if a retry request arrives with the same idempotency_key but different input, should it be rejected (recommended for safety) or replaced? Implement client-side or server-side validation to enforce consistent input per key. Use a failing constraint violation as an error signal, not a silent collision.
38. **[P2|backend-persistence]** Missing Exposed table definitions for plan-required tables
   - file: `backend/src/main/kotlin/com/potaty/backend/persistence/Tables.kt`
   - fix: Add Exposed DSL table object definitions for all 7 missing tables in Tables.kt, following the existing pattern (e.g., `object SourceChunksTable : Table("source_chunks") { ... }`). Each must expose workspace_id for tenant scoping. Prioritize source_chunks, extracted_entities, extracted_relations, and renderings as they are needed by WS5-WS7 pipeline stages.
39. **[P2|backend-persistence]** PostgresJobQueue.claim() does not filter by workspace in SELECT
   - file: `backend/src/main/kotlin/com/potaty/backend/jobs/PostgresJobQueue.kt:25-46`
   - fix: Add a comment documenting that claim() is a privileged worker-pool-only operation that intentionally reads across all workspaces to implement fair job distribution. Consider adding an access control layer in JobWorkerPool that ensures only authenticated workers (not user-facing APIs) can call queue.claim(). Alternatively, if per-workspace isolation is required, partition the queue logic or add workspace filtering to claim() with a fairness trade-off discussion.
40. **[P2|backend-persistence]** SourceRepository.listSources does not return source_chunks or metadata needed for evidence tracking
   - file: `backend/src/main/kotlin/com/potaty/backend/persistence/repositories/SourceRepository.kt:83-99`
   - fix: Add a SourceChunksRepository with methods: (a) findChunksBySourceVersion(workspaceId, sourceVersionId): List<SourceChunkRecord>, (b) findChunk(workspaceId, chunkId): SourceChunkRecord?, (c) insertChunks(workspaceId, sourceVersionId, chunks: List). Ensure all queries filter by workspace_id for tenant isolation per plan 20.5.
41. **[P2|backend-persistence]** TransactionContext does not provide serialization conflict retry
   - file: `backend/src/main/kotlin/com/potaty/backend/persistence/TransactionContext.kt`
   - fix: Either (a) document that callers must handle SQLRecoverableException and retry via the job layer, or (b) implement optional retry logic in TransactionContext.tx() with exponential backoff for serialization conflicts. The current approach (REPEATABLE_READ + job-layer retry) is valid but should be documented clearly in TransactionContext's kdoc.
42. **[P2|backend-persistence]** No method to look up diagram by ID without version
   - file: `backend/src/main/kotlin/com/potaty/backend/persistence/repositories/DiagramRepository.kt`
   - fix: Add a method `suspend fun findLatestVersion(workspaceId: UUID, diagramId: UUID): DiagramVersionRecord?` that selects from diagram_versions WHERE workspace_id=? AND diagram_id=? ORDER BY version_number DESC LIMIT 1, filtering by workspace_id for tenant isolation.
43. **[P2|backend-api]** DTO validation gap — no input validation on sourceType, diagramType, outputFormats, qualityMode
   - file: `backend/src/main/kotlin/com/potaty/backend/api/Dtos.kt:18-158`
   - fix: Define sealed classes or enums in Dtos.kt that mirror the canonical enums (SourceType, DiagramType from shared models). Use @Serializable(with=CustomSerializer) to decode/validate on deserialization, or add @Transient validator functions that throw ValidationException if values are not in the allowed set. Example: 'if (sourceType !in SourceType.values().map{it.wireName}) throw ValidationException(...)'
44. **[P2|backend-api]** Security: CreateSourceRequest.content field allows arbitrary large strings with no size limit
   - file: `backend/src/main/kotlin/com/potaty/backend/api/Dtos.kt:18-25, SourceRoutes.kt:32-46`
   - fix: In SourceRoutes, after call.receive(), validate: if (body.content?.length ?: 0 > maxSizeForWorkspaceTier(tenant)) throw ValidationException('content exceeds limit'). The maxSizeForWorkspaceTier should read from workspace settings or AppConfig tier pricing, then compare against plan section 12.4 thresholds.
45. **[P2|backend-api]** POST diagram-jobs misses cost estimation before job creation — quota not enforced upfront
   - file: `backend/src/main/kotlin/com/potaty/backend/api/DiagramRoutes.kt:42-52`
   - fix: Implement CostEstimator.estimateDiagramJobCost(sourceVersionIds, diagramType, qualityMode, modelTier) → (lowUsd, highUsd). Call it before responding. If lowUsd exceeds remaining budget, throw ValidationException('budget_exceeded'). Otherwise, reserve the estimated range in a usage_events or budget_reservation table, and enqueue the job.
46. **[P2|backend-api]** GET /api/v1/diagrams/{diagramId}/versions/{versionId} has no diagramId field in response — client cannot verify consistency
   - file: `backend/src/main/kotlin/com/potaty/backend/api/Dtos.kt:88-97`
   - fix: After calling DiagramRepository.findVersion(tenant.workspaceId, diagramId, versionId), verify the returned record's diagramId matches the path parameter. If not, respond 404. Only deserialize and return the IR/validation/coverage if they match.
47. **[P2|backend-api]** POST /api/v1/diagrams/{diagramId}/versions/{versionId}/exports missing versionId parameter validation
   - file: `backend/src/main/kotlin/com/potaty/backend/api/DiagramRoutes.kt:101-114`
   - fix: Extract and validate versionId from call.parameters (handle missing/invalid case). Load the DiagramVersionRecord via DiagramRepository.findVersion(). Deserialize ir JSON and validate it exists. Then compile each format and return renderings with either contentText or objectKey populated.
48. **[P2|backend-api]** POST diagram-jobs does not validate outputFormats list — can accept invalid renderer names
   - file: `backend/src/main/kotlin/com/potaty/backend/api/Dtos.kt:38-45`
   - fix: Validate in DiagramRoutes after call.receive(): val allowed = setOf('ascii', 'mermaid', 'd2', 'plantuml', 'dot', 'svg', 'png', 'pdf', 'markdown'). For each format in body.outputFormats, if !allowed.contains(format) throw ValidationException('unsupported_format'). Or define an enum OutputFormat and use custom deserialization.
49. **[P2|backend-api]** ExportRequest.theme field accepts arbitrary strings — no validation against StyleProfile list
   - file: `backend/src/main/kotlin/com/potaty/backend/api/Dtos.kt:139-143`
   - fix: Define enum Theme with the 6 valid values. Use it in ExportRequest instead of String, or validate in DiagramRoutes: if (body.theme !in listOf('potaty-clean', 'potaty-slate', ...)) throw ValidationException('invalid_theme').
50. **[P2|backend-api]** PatchRequest.instruction field lacks length/content limits — can inject adversarial text
   - file: `backend/src/main/kotlin/com/potaty/backend/api/Dtos.kt:116-120`
   - fix: Add validation in DiagramRoutes after call.receive(): if (body.instruction.length > 5000) throw ValidationException('instruction_too_long'). The PromptInjectionGuard (referenced in Application.kt line 19 and mentioned in plan 20.4) should be invoked before sending the instruction to the LLM, but that is downstream. The DTO layer should still enforce a reasonable upper bound.
51. **[P2|backend-api]** Stub responses return hardcoded/empty objects — lack consistency checks between related fields
   - file: `backend/src/main/kotlin/com/potaty/backend/api/DiagramRoutes.kt:68-78, 91-97, 109-112`
   - fix: When implementing the pipeline, add a consistency check before responding: if (status == 'needs_review' && validationReport.violations.isEmpty() && unsupportedClaims.isEmpty()) throw error('inconsistent state'). If outputFormats requested don't match renderings returned, throw error. Add a companion object factory function that builds consistent responses.
52. **[P2|backend-api]** No rate limiting or request throttling on API endpoints — plan 22.4 mentions quota guards but API does not implement them
   - file: `backend/src/main/kotlin/com/potaty/backend/api (all routes)`
   - fix: Implement a RateLimiter in the auth middleware or as a per-workspace check in the route. Example: if (workspace.diagramJobsCreatedInLastMin() >= 10) throw TooManyRequestsException(). For diagram jobs specifically, use the budget guard mentioned in plan 22.1.
53. **[P2|backend-api]** Repositories called from API routes do not auto-scope to workspace — caller must pass workspaceId explicitly
   - file: `backend/src/main/kotlin/com/potaty/backend/api/SourceRoutes.kt:27-47`
   - fix: Scaffold the actual call in the TODO: 'SourceRepository.createSource(tenant.workspaceId, projectId, body.sourceType, body.displayName, body.metadata?.toString() ?: "{}" , call.request.headers["Authorization"]?.let{...})'. This makes the pattern explicit and harder to misuse.
54. **[P2|backend-api]** Missing HTTP 400 handling for malformed JSON request bodies — Ktor default error is opaque
   - file: `backend/src/main/kotlin/com/potaty/backend/Application.kt:104-109`
   - fix: Add a StatusPages exception handler for kotlin.serialization.SerializationException (or whatever Ktor throws on JSON parse failure): 'exception<SerializationException> { call, cause -> call.respond(HttpStatusCode.BadRequest, ApiError("bad_request", "Invalid JSON: " + cause.message)) }'
55. **[P2|backend-security]** Redactor is called in only one place (stub) and not integrated into pipeline SafetyPreScan stage
   - file: `backend/src/main/kotlin/com/potaty/backend/api/SourceRoutes.kt:35-36`
   - fix: Create a SafetyPreScan stage implementation that: (1) calls Redactor.scan() and Redactor.redact() on normalized source text before storage, (2) emits warnings if secrets/sensitive PII are found, (3) stores the redacted version in the source_chunks table, (4) records findings in a new redaction_events table (workspace_id, source_version_id, category, count, created_at), and (5) optionally returns NeedsUserInput or FatalFailure if critical secrets are detected (configurable by workspace policy). Integrate this stage after SourceNormalizer and before Chunker so chunks are already sanitized. Also add redaction calls to export paths (DiagramRoutes.kt line 108 marked TODO for SvgSanitizer already hints at the pattern).
56. **[P2|backend-security]** Redactor does not implement entropy-based secret detection or allowlist/suppression
   - file: `backend/src/main/kotlin/com/potaty/backend/security/Redactor.kt:59-61`
   - fix: Add a second pass after regex matching that computes Shannon entropy of unmatched high-length strings (e.g., >=40 chars, entropy >= 5.5 bits/char) and flags them as probable secrets. Add an allowlist mechanism (e.g., a set of known-safe strings or a regex of known-false-positives) that can be passed to scan() and redact(). Consider provider-specific patterns (e.g., Stripe keys: sk_live_* or sk_test_*, Supabase keys: sbpb_*, etc.). Document which patterns are included and make the rules updateable without code changes.
57. **[P2|backend-security]** Redactor email regex will match many false positives and miss internationalized domains
   - file: `backend/src/main/kotlin/com/potaty/backend/security/Redactor.kt:51`
   - fix: Replace the email regex with a tighter pattern or a small validator function. At minimum: ensure local-part starts with `[A-Za-z0-9]`, does not contain consecutive dots, ends with `[A-Za-z0-9]`, and the domain has valid label structure (no leading/trailing dashes, no consecutive dots). Alternatively, accept over-broad matching but add an allowlist of known-safe patterns (e.g., 'localhost', '127.0.0.1', test@example.com variants) and test the regex against common false positives in fixture tests. Document the regex behavior in a comment.
58. **[P2|backend-security]** PromptInjectionGuard suspicious-phrase detection is advisory-only with limited coverage
   - file: `backend/src/main/kotlin/com/potaty/backend/security/PromptInjectionGuard.kt:53-68`
   - fix: This is low-priority if assertSourceIsolation() is enforced (which is the real control), but if detectSuspiciousPhrases() is kept, expand the list and make it more resilient: (1) add more common jailbreak patterns (e.g., 'simulate', 'pretend', 'what is your', 'give me', 'bypass', 'leak', 'exfiltrate', 'jailbreak', 'DAN', 'prompt injection', etc.), (2) consider word-boundary matching instead of substring, or a small trie-based matcher, (3) call it in fenceSourceData() and log matches for audit, (4) consider making the list configurable. However, rely on structural controls (SOURCE_DATA isolation + schema validation) as the primary defense; this is supplementary.
59. **[P2|backend-security]** IR Validator's R012 rule duplicates Redactor logic with inconsistent patterns
   - file: `backend/src/main/kotlin/com/potaty/backend/security/Redactor.kt:41-53`
   - fix: Extract the secret/PII patterns into a shared module (e.g., backend/src/main/kotlin/com/potaty/backend/security/SecretPatterns.kt) that is imported by both Redactor and IrValidator. Define canonical patterns once, test them together, and use them consistently. Alternatively, have IrValidator import Redactor's patterns directly (though this couples IR validation to the backend, which violates module boundaries since IrValidator is in the shared diagram-ir module). A third option: move pattern definitions into a config/constants file and load them at runtime. Choose based on the project's architecture preferences.
60. **[P2|backend-security]** PromptInjectionException handler installed but exception never raised from actual code paths
   - file: `backend/src/main/kotlin/com/potaty/backend/Application.kt:101-103`
   - fix: When implementing prompt builders, ensure every prompt assembly calls PromptInjectionGuard.assertSourceIsolation(parts, sourceTexts) and lets PromptInjectionException propagate. The exception handler in Application.kt is correctly wired; just ensure the guard is actually called. Add a test in the backend that tries to build a malicious prompt and verifies it raises PromptInjectionException, which is then caught by the status page handler and returns 400.
61. **[P2|backend-auth]** No defensive test for cross-workspace isolation at the route layer
   - file: `backend/src/test/kotlin/com/potaty/backend/CoreLogicTest.kt`
   - fix: After SessionAuth is implemented, add integration tests in a new file (e.g., TenantIsolationTest.kt) that: (1) create two workspaces and users, (2) create a diagram in workspace-A, (3) attempt to fetch it from a session in workspace-B, (4) verify the request is rejected (403 Forbidden or 404 Not Found, not a data leak). Repeat for sources, jobs, credentials. This is a P1 blocker for production launch; add it as a gating test in the CI config once SessionAuth lands.
62. **[P2|backend-auth]** TenantContext.userId and workspaceId are String; no UUID type safety
   - file: `backend/src/main/kotlin/com/potaty/backend/auth/TenantContext.kt:17-20`
   - fix: Wrap workspaceId and userId in value classes: 'value class WorkspaceId(val id: String)' and 'value class UserId(val id: String)'. Update TenantContext to use them. This is a quality improvement (P2) and can be done in a follow-up refactor; it is not blocking.
63. **[P2|backend-auth]** Rbac.require() does not distinguish between missing permission and stale tenant context
   - file: `backend/src/main/kotlin/com/potaty/backend/auth/Rbac.kt:52-58`
   - fix: This is a minor quality issue (P2). The current behavior (NotAuthenticatedException thrown before Rbac.require) is acceptable. However, for better observability, consider: (1) logging which role/permission combination failed in Rbac.require, (2) optionally validating tenant.workspaceId is non-empty (defensive), or (3) using distinct exception types (ForbiddenException for role check failure vs. a different type for tenant validation failure). Not a blocker.
64. **[P2|backend-auth]** CredentialStore.open() throws generic Exception on AAD validation failure; should be specific
   - file: `backend/src/main/kotlin/com/potaty/backend/llm/auth/CredentialStore.kt:58-66`
   - fix: Wrap the cipher.doFinal() call in a try-catch, catch AEADBadTagException, and throw a domain-specific exception such as 'CredentialVerificationFailedException' with a clear message: 'Credential reference mismatch; possible cross-workspace access attempt or corrupted credential data.' Log the workspace mismatch at WARN level. This improves observability and security audit trails.
65. **[P2|backend-auth]** RBAC permission matrix does not include fine-grained resource-level grants
   - file: `backend/src/main/kotlin/com/potaty/backend/auth/Rbac.kt:26-46`
   - fix: Current design is correct for WS4. Document in a comment: 'Per-resource access control (e.g., per-project permissions) is not supported in v1. All resources within a workspace are equally accessible to a user's role. If per-project access is added, extend Rbac to accept (role, permission, resourceId) and maintain a grants table.' No immediate fix needed; add to technical debt or future workstream.

## P3 (12)
1. **[P3|diagram-ir]** IrValidator does not check for cycles in evidence references
   - file: `shared/diagram-ir/src/main/kotlin/com/potaty/ir/IrValidator.kt:138-149`
   - fix: Add an optional parameter to IrValidator constructor: knownSourceChunkIds: Set<String>? If provided, R008 should warn or error if evidence references unknown chunks. If null (default), skip this check (current behavior). This allows callers to optionally validate evidence references against a live source registry.
2. **[P3|diagram-ir]** IrJson ignores strict schema validation; SUPPORTED_SCHEMA_VERSIONS includes 1.0 without upgrade path
   - file: `shared/diagram-ir/src/main/kotlin/com/potaty/ir/Ir.kt:52-53`
   - fix: Either (a) remove 1.0 from SUPPORTED_SCHEMA_VERSIONS and require explicit migration, or (b) add a schema migration function in IrJson that upgrades 1.0 to 1.1 and document what changed. Include tests for the upgrade path.
3. **[P3|diagram-ir]** EvidenceRef does not validate startLine <= endLine constraints
   - file: `shared/diagram-ir/src/main/kotlin/com/potaty/ir/IrValidator.kt:138-149`
   - fix: Add a check in R008 to warn if evidence ranges are inverted (startLine > endLine, etc.). Alternatively, add a method EvidenceRef.isValidRange(): Boolean and call it in the validator.
4. **[P3|renderer-codegen]** MarkdownExporter percentage formatting may lose precision or round unexpectedly for edge cases
   - file: `shared/renderer-codegen/src/main/kotlin/com/potaty/codegen/MarkdownExporter.kt:141-146`
   - fix: Use Kotlin's built-in rounding: `val rounded = (value * 1000).toInt()` or a proper rounding library. Or document that coverage percentages are approximate and may have ±0.1% error. For display to users, this is minor, but for machine-readable output, it could matter.
5. **[P3|renderer-codegen]** IdentAllocator does not validate that rawId is not null/empty until after calling sanitizeIdent
   - file: `shared/renderer-codegen/src/main/kotlin/com/potaty/codegen/IdentAllocator.kt:33-46`
   - fix: Document that rawId must be non-empty (enforced by IrValidator upstream). Optionally, add a comment to identify() explaining the empty-id behavior. No code change needed if IrValidator ensures non-empty node ids.
6. **[P3|backend-llm]** Anthropic provider correctly requires legalUseCaseApproved but does not log feature flag violation
   - file: `anthropic/AnthropicProvider.kt`
   - fix: Log a warning or audit event before throwing the require() error. Include workspace_id, credential_id, and timestamp. This helps detect misconfiguration or policy violations.
7. **[P3|backend-jobs]** JobStatus enum uses hardcoded wire strings but plan specifies lifecycle states
   - file: `backend/src/main/kotlin/com/potaty/backend/jobs/JobQueue.kt:12-24`
   - fix: Add a JobStatusTransitions enum or state machine that documents valid transitions. Define what NEEDS_INPUT means (e.g., user must provide missing information before the job can resume). Update the plan or documentation to clarify job state semantics and their relationship to diagram lifecycle states.
8. **[P3|backend-persistence]** Exposed schema mirrors DDL but does not validate migration compatibility
   - file: `backend/src/main/kotlin/com/potaty/backend/persistence/Tables.kt:1-8`
   - fix: Consider adding a startup-time schema validator that compares Exposed table definitions against the actual database schema and warns if there is a mismatch. Alternatively, document in CONTRIBUTING.md that any DDL changes must be mirrored in Tables.kt. For now, this is low-priority maintenance guidance.
9. **[P3|backend-persistence]** JobRepository.findByIdempotencyKeyInternal is private but used for critical idempotency check
   - file: `backend/src/main/kotlin/com/potaty/backend/persistence/repositories/JobRepository.kt:122-129`
   - fix: Consider exposing a public variant: `suspend fun findByIdempotencyKey(workspaceId: UUID, idempotencyKey: String): JobRecord?` for observability and testing. Ensure it filters by workspace_id for tenant isolation.
10. **[P3|backend-api]** Idempotency-Key header validation missing — stub allows empty/missing keys to collide
   - file: `backend/src/main/kotlin/com/potaty/backend/api/DiagramRoutes.kt:35-52`
   - fix: After call.receive<DiagramJobRequest>(), derive the canonical idempotency key using Idempotency.diagramJobKey(workspaceId, projectId, sourceSnapshotHash, diagramType, objective, scopeCanonical, rendererVersion, promptVersion). Then attempt to look up an existing job in JobRepository with that key. If found and completed, return the existing diagram version. If found and running, return the existing job ID. Otherwise, insert the new job with the derived key.
11. **[P3|backend-auth]** LlmCredential.ProviderOAuthCredential is disabled by default but feature-flag is not documented in SecurityConfig
   - file: `backend/src/main/kotlin/com/potaty/backend/config/AppConfig.kt:40`
   - fix: Add a validation function in CredentialStore or a new SecurityValidator module that: (1) receives an LlmCredential and the AppConfig, (2) if credential is ProviderOAuthCredential and allowProviderOAuth is false, throw a clear CredentialNotAllowedException, (3) call this validator before any provider instantiation. This closes the feature-flag gap and ensures the ToS-restricted auth path cannot be accidentally enabled without explicit code review.
12. **[P3|backend-auth]** NotAuthenticatedException in TenantContext is an unchecked exception; callers cannot recover
   - file: `backend/src/main/kotlin/com/potaty/backend/auth/TenantContext.kt:49`
   - fix: This is a minor style point (P3) and not a blocker. If desired for explicitness, consider: (1) providing a call.tenantOrNull(): TenantContext? function (already exists in TenantContext.kt:47), (2) documenting that routes should prefer call.tenantOrNull() if they need to handle missing auth gracefully, or (3) leaving the unchecked exception design as-is for simplicity. Current approach is acceptable.
