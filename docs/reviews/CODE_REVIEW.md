# Code review

Review date: 2026-07-17
Review target: commit `c70cee0` plus the uncommitted production-readiness worktree
Decision: the supported local and controlled self-hosted preview paths are internally consistent;
public hosted production remains gated by the operational P0 items in the roadmap.

## Method

Multiple static-review passes, including a final independent read-only adversarial pass, traced browser DTOs, API routes, tenant repositories, queue
state transitions, provider adapters, usage accounting, canonical Diagram IR, renderers, exports,
and deployment configuration. Findings were reproduced against the implementation, fixed with
focused tests, and then rechecked across module boundaries. Imported repository content was
reviewed strictly as untrusted data and was never executed.

## Findings closed

| Priority | Finding | Resolution |
|---|---|---|
| P1 | A secret in the user-supplied diagram objective could reach persistent IR | The objective now passes through the same safety/redaction gateway as source text; IR validation scans title and objective |
| P1 | Paid invalid provider responses could lose usage | OpenAI and Anthropic preserve token/cached-token usage on malformed 2xx outputs; malformed envelopes are `INVALID_OUTPUT`, not network failures |
| P1 | Parallel requests could each pass a stale monthly quota check | Admission locks the workspace row and creates a durable reservation atomically; active attached reservations follow job state and terminal cleanup is idempotent |
| P1 | Audio transcription attempts were not consistently budgeted | Exactly one audio source is required, size is preflighted, credentials are tenant-resolved before a fresh provider fence/call, and every provider attempt records a conservative configurable cost |
| P1 | Browser publication state could ignore the server validation report or average away a weak metric | The client preserves the full validation report and applies canonical per-metric node/edge thresholds; blocked/review results never claim “Ready” |
| P2 | Operator-configured enrichment was accidentally limited to development auth | Deployment credentials are sealed just in time for the authenticated workspace, so JWT-mode enrichment remains tenant-bound |
| P2 | Provider OAuth could be enabled by credential shape alone | Anthropic OAuth now requires both the deployment feature flag and explicit legal-use approval; OpenAI remains API-key only |
| P2 | Legacy manual-editor projects stopped opening after rebranding | New files use `.potaty`; legacy `.mono` remains read-compatible |
| P2 | Supplying both inline audio and an object key created ambiguous behavior | The API enforces an exact-one contract and returns a stable validation error |
| P2 | LLM and GitHub upstream failures could expose raw response text or accept unsafe endpoint shapes | Provider base URLs are strict origins and GitHub routes map failures to stable source-free messages |
| P1 | A worker retry could persist a diagram before all renderings existed, then create duplicates or orphans | Diagram, version, and renderings now commit atomically; a unique job binding and deterministic ids make retries return the first complete artifact |
| P2 | Transcription could reach credential/provider work before rejecting an unknown or cross-tenant project | The route now performs a tenant-scoped project existence check before body parsing, credential lookup, quota reservation, or provider invocation |
| P2 | Transcription trusted caller-supplied encrypted credential references | The HTTP contract accepts only `credentialId`; the server resolves an active, non-revoked, workspace-bound OpenAI API-key row or the explicit deployment credential |
| P2 | Raw provider speaker metadata could overwrite normalized/redacted chunk metadata | The canonical transcript is safety-processed and reparsed; only exact timestamps are restored from provider segments |
| P2 | GitHub App connection code depended on variables absent from public deployment surfaces | `.env.example`, Compose, and README now expose the complete App slug, web base, OAuth, callback/public origin, and signed-state configuration contract |
| P1 | A provider response could be billable, then disappear from quota after usage persistence failed and its reservation expired | The route durably marks external-spend start before the call; usage insert and release settle atomically, while a failed insert leaves the reservation counted until reconciliation |
| P1 | A successful provider response could be called twice after the process crashed before source persistence | Store a redacted durable checkpoint under a rotating lease token; the original idempotent retry claims it after lease expiry and never calls the provider again |
| P1 | Transcript source rows could commit while usage settlement rolled back | Source, version, chunks, usage, replay result, and reservation release now share one transaction; a forced usage-ID collision proves complete rollback and exact-once recovery |
| P1 | Generic owner reconciliation could clear a valid success checkpoint | Checkpoint rows are excluded both from the pending query and from the locked settlement predicate; only the original request can finish them |
| P1 | Credential rotation/revocation blocked stored-result and checkpoint replay | Credential lookup moved inside the fresh-provider branch; replay and completion require the original request body/hash but not the old credential |
| P1 | A chunked JSON upload could allocate without limit before the 25 MiB audio check | The request channel is counted under a hard ceiling before UTF-8/JSON decoding, with and without `Content-Length` |
| P2 | PostgreSQL JSONB numeric normalization and `numeric(12,6)` limits could break replay or reconciliation | JSON numbers compare through `BigDecimal.compareTo`; owner charges share the exact `999999.999999` upper bound across API and persistence |
| P1 | Text/transcript ingestion committed source, version, and chunks in separate transactions | A request-bound atomic repository operation now commits all three together; injected chunk failure proves source/version rollback, and exact API retries return the same ids |
| P1 | GitHub indexing had the same partial-artifact window and no stable replay key | Public/App routes require a request-bound key; atomic persistence plus a pre-network replay lookup returns the stored snapshot without another tree/blob request or installation credential |
| P2 | An owner could select `charge` without entering the provider receipt amount | Both HTTP and transactional layers now require an explicit bounded amount; the reservation estimate is never silently substituted |
| P1 | Two equivalent GitHub requests could both open a credential and fetch the repository before either persisted a replay row | A unique source-ingestion claim is acquired before credential/network work; database-clocked leases and tokens fence takeover, renewal, finalization, and completion |
| P1 | GitHub could accept a malformed successful response as an empty tree or zero-byte blob | Required DTO fields no longer default; tree/blob SHA, size, encoding, content, entry type/path and duplicate-path semantics fail closed |
| P1 | A browser reload or uncertain cancel could rotate a job key and duplicate provider spend | Tab-scoped SHA-256 retry fingerprints retain opaque source/job keys; failed cancel acknowledgement and cancel/success races preserve the job key until authoritative recovery |
| P1 | Browser runtime tokens were recoverable from `sessionStorage` | Tokens are memory-only, the legacy session key is deleted without hydration, and persisted retry state contains neither credentials nor raw source text |
| P2 | Source-claim behavior was proven only by H2/unit tests | CI now runs Flyway V1–V10 and a concurrent claim/constraint/completion/retention contract against a disposable pgvector PostgreSQL service |
| P1 | The production bundle's SHA-256 byte conversion called a nonexistent `Uint8Array.get`, blocking every generation before the API request | Read native bytes with JavaScript bracket indexing, cover the conversion in a browser test, and repeat a clean-cache live production-bundle generation with zero console errors |
| P1 | A `succeeded` job with missing output retired its browser job key, so retry could create a second billable job instead of recovering the first | Retire the key only after a usable diagram version or a known non-success terminal state; missing success output and cancel/success races remain replayable |
| P2 | A stalled fetch or response body could suspend the Studio forever | Give every fetch and body read one bounded deadline, abort it with `AbortController`, settle the continuation exactly once, and ignore late callbacks |
| P2 | Back-to-input could unlock a retry before the previous coroutine unwound, letting the old run cancel a newly adopted key | Serialize generations with an owner gate, abort the active transport, and unlock only when the matching owner's `finally` completes |
| P1 | Browser form restoration or a late FileReader callback could revive raw prompt, repository, or transcript text after reload/history restore | Disable source-field autocomplete; clear controls at mount, the next task, and persisted `pageshow`; abort and generation-fence transcript reads; live reload/BFCache and lifecycle tests prove stale callbacks cannot repopulate source state |
| P1 | Bounded retry bookkeeping evicted the oldest unresolved job and swallowed storage failures | Never evict unresolved attempts, fail closed before key allocation at capacity, latch read/write failure, and commit candidate memory state only after durable session storage succeeds |

## Additional hardening applied during review

- Hard-denied repository secret paths cannot be re-included by a later ignore negation.
- Provider coroutines rethrow cancellation instead of converting cancellation into retryable
  network errors.
- Embedding vectors must be present, finite, non-empty, and dimensionally consistent before use.
- Job terminal-state semantics are defined once and reused for progress, completion timestamps,
  cancellation cleanup, and cost reservations.
- Transcription credential identifiers are bounded; forged, revoked, wrong-provider, wrong-type,
  cross-tenant, and empty ids fail before the external-spend fence or provider call. Stored results
  and checkpoints intentionally bypass credential lookup. Ciphertext is never an HTTP request field.

The final focused independent review rechecked the six transcription/accounting corrections above
against their regression tests and returned `PASS` with no actionable finding.

A later broad four-angle review found the three source-ingestion and reconciliation issues above;
all were reproduced, remediated, and added to the release regression gate before sign-off.

Two subsequent independent adversarial rounds found and closed fail-open GitHub payloads, pre-claim
outbound work, lease-clock skew, stale browser retry bookkeeping, cancellation ambiguity, stored
browser credentials, and insufficient PostgreSQL race/constraint evidence. The final narrow
read-only re-review returned `PASS` with no P1/P2 finding.

Final production-bundle dogfooding and another independent adversarial pass then exposed the
typed-array interop failure plus five browser recovery/privacy gaps above. Each was reproduced,
covered in Node and real-Chromium tests, and re-reviewed after fail-closed remediation. The final
read-only re-review returned `PASS` with no actionable P0/P1/P2 finding.

## Residual review notes

No known correctness defect remains in the supported prompt, text-transcript, and public-GitHub
preview flows. The project must still avoid a hosted-production claim until external identity and
KMS lifecycles, distributed abuse controls, broad PostgreSQL/restore drills, cross-browser accessibility,
and deployment canaries meet the acceptance evidence in [the roadmap](../ROADMAP.md).
