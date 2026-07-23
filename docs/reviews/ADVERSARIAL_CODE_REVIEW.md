# Adversarial code review

Review date: 2026-07-17
Attacker model: anonymous network client, authenticated malicious tenant, malicious repository or
transcript author, compromised upstream provider response, and an operator accidentally using
development configuration in production.

## Review method

The review traced untrusted bytes from HTTP/browser inputs through normalization, persistence,
jobs, provider prompts, canonical Diagram IR, renderers, logs, exports, and retention. It also
checked tenant predicates, concurrency/fencing, failure messages, environment validation,
dependency controls, and deterministic output. Positive tests were paired with malformed,
cross-tenant, stale-lease, replay, oversized, and secret-bearing inputs.

## Findings remediated

| Severity | Attack | Remediation and verification |
|---|---|---|
| High | Provider/SQL/source secrets escape through exception messages | Log only exception type/shape and an incident ID; persist a generic reason; regression tests include a synthetic secret |
| High | Same idempotency key mutates into a different job | Compare project, job type, and canonical request bytes; return `409`; database uniqueness remains authoritative under races |
| High | Retention deletion fails or leaves sensitive dependent rows | Delete every tenant-owned dependent table in foreign-key-safe order; comprehensive H2 fixture asserts no residue |
| High | Host-confusion or malformed GitHub repository input | Strict `github.com` URI/shorthand parser plus bounded owner/repository/ref validation; raw hostile input is not reflected |
| High | Unbounded or hostile LLM JSON enters persistent IR | Schema bounds, enum restrictions, collection caps, safe text normalization, identifier remapping, and output-boundary tests |
| High | Monthly LLM quota never accumulates actual spend | Aggregate provider usage across structured repair attempts and persist tenant/job-scoped usage before returning the generated version |
| High | Concurrent jobs and transcriptions all pass a stale monthly quota check | Lock the workspace row, reserve high-end spend durably, include active job-linked reservations, and release idempotently on every terminal state |
| High | Invalid but billable provider 2xx responses disappear from accounting | Parse usage before output extraction, preserve cached input tokens, and return paid invalid output with its usage record |
| High | A secret in the requested diagram objective bypasses source redaction | Process objectives through the source-safety gateway and validate persistent IR title/objective fields for sensitive values |
| High | Source copies the static close delimiter and escapes its untrusted prompt fence | Neutralize source-supplied boundary markers before prompt assembly; regression test asserts one authoritative close marker |
| Medium | Requested non-Mermaid output vanishes after the worker finishes | Persist every requested code rendering under the immutable diagram version and return only the stored formats |
| Medium | Queue execution drops creator and model provenance | Carry the authenticated creator in claims/context and persist provider, model, tokens, stage, outcome, and applied state |
| Medium | Failed/needs-input jobs lose their actionable reason | Store a bounded safe reason in the polling contract and treat all terminal statuses consistently |
| Medium | Malformed JSON parser text reflects source fragments | Return a stable generic 400 response; retain only sanitized exception shape for operators |
| Medium | CORS or upstream base URL accepts a path/query/userinfo lookalike | Parse with `URI` and require a real origin with expected scheme/host/port shape |
| Medium | Terminal escapes or bidi overrides corrupt logs/ASCII review | Remove ISO controls other than tab/LF and remove bidi override/isolate controls at normalization |
| Medium | Unsupported source kinds or oversized control fields reach the pipeline | Route allowlists and explicit length/count/value bounds with negative API tests |
| Medium | Expired worker writes over a reclaimed job | Per-claim fencing token, lease renewal, guarded transitions, and lease-loss cancellation tests |
| Medium | Forged transcription refs trigger provider or cross-tenant decrypt attempts | Bound and tenant-resolve the opaque credential only in the fresh-provider branch before the spend fence/call; never reflect decrypt errors, and never require the credential for stored replay |
| High | A reclaimed worker writes a second diagram or leaves a diagram without its renderings | Atomically insert diagram/version/renderings and enforce one generated artifact per workspace/job; replay returns the persisted winner |
| Medium | An unknown/cross-tenant project is discovered only after transcription-side work | Recheck project ownership before request-body allocation, credential resolution, quota reservation, or provider invocation |
| Medium | A client replays or substitutes an encrypted credential reference | Remove ciphertext from the DTO; resolve only active, non-revoked, same-workspace OpenAI API-key records server-side |
| Medium | A provider injects secrets or bidi controls through the speaker field | Safety-process and parse the rendered transcript; restore provider numeric times only, never raw speaker metadata |
| Medium | A deployment silently enables only half of the GitHub App connection flow | Document and pass through the complete App/OAuth/state/origin variable set; connection enablement remains all-or-nothing |
| High | A billable transcription succeeds while the usage database insert fails; the temporary reservation later expires | Persist an external-spend-start fence before the call and atomically insert usage/release afterward; database-failure regression proves the fence remains counted beyond TTL |
| High | A successful transcription is billed twice after a crash between provider response and persistence | Save a bounded redacted checkpoint before ingestion; reclaim it by lease/fence token and complete the original idempotent request without provider or credential access |
| High | An owner charges/releases a valid success checkpoint as if its outcome were uncertain | Exclude checkpoints from the pending queue, locked reconciliation guard, and final update predicate; a direct settlement attempt fails atomically |
| High | Inline/chunked transcription JSON exhausts heap before the encoded-audio length check | Count raw channel bytes before UTF-8 and JSON allocation; reject over-limit declared and undeclared-length bodies with `413` |
| Medium | JSONB rewrites numeric notation and makes an identical checkpoint look different | Compare JSON recursively and compare non-string numeric primitives with `BigDecimal`, preserving object order independence and array order |
| Medium | An accepted admin charge overflows PostgreSQL `numeric(12,6)` | Share the exact `999999.999999` ceiling between HTTP validation and the transactional recorder; boundary-overflow tests fail before insert |
| High | A database error after source insert leaves an invisible partial source/version, and retry duplicates it | Commit source, version, and all chunks in one transaction; bind the workspace key to the request hash and prove rollback with an injected chunk primary-key failure |
| High | A lost GitHub response causes another paid/rate-limited fetch and another source snapshot | Resolve an exact stored replay before installation-token or remote API work; deterministic path ordering and atomic persistence return the original snapshot |
| High | Concurrent first-time GitHub requests both open credentials and perform rate-limited outbound work | Acquire a unique tenant/request-bound claim first; use database-clocked lease takeover and token-fenced renewal/finalization/completion |
| High | A malformed GitHub 2xx payload defaults to an authoritative empty tree/blob | Make mandatory wire fields required and reject inconsistent SHA, size, encoding, content, entry types, and duplicate blob paths before persistence |
| Medium | An operator omits the receipt amount and accidentally books the reservation estimate | Require an explicit finite PostgreSQL-safe `chargeUsd` at the route and locked transaction boundary |
| Medium | Cancellation is swallowed as a retryable provider network error | OpenAI, Anthropic, and audio adapters rethrow coroutine cancellation before generic failure mapping |
| High | Browser cancellation failure clears the job key while the server job still runs | Treat unconfirmed cancellation and cancel/success races as recoverable non-terminal client states; retry reuses the exact job key and fetches authoritative output |
| Medium | Provider OAuth is activated without an operator policy gate | Require both `POTATY_ALLOW_PROVIDER_OAUTH=true` and an approved legal-use credential; consumer subscription tokens remain unsupported |
| High | Runtime token survives reload in browser storage | Keep it only in page memory, delete the legacy session key without reading it, and persist only SHA-256 request digests plus opaque retry keys |
| High | Kotlin/JS interop emits a nonexistent typed-array accessor and disables the retry-fingerprint trust boundary in the minified bundle | Use native bracket indexing, add a real-browser typed-array regression, rebuild the production artifact, and verify the live source/job/result sequence with no console errors |
| High | A server says `succeeded` without exposing its output yet, and the browser discards the only job key | Treat success-without-usable-output as an unknown outcome; the exact key remains until replay fetches an authoritative diagram version |
| Medium | A fetch or response body never settles and permanently owns the UI generation slot | Bound the whole response, abort the native request, settle once, retain the unknown-outcome key, and cover manual abort plus deadline in Node and Chromium |
| High | The user returns to input and immediately retries while the stale run can still cancel the adopted job | Gate run ownership through the stale coroutine's `finally`; transport abort shortens the wait, while mismatched owners cannot release the gate |
| High | Chromium restores raw source form state, or a stale FileReader callback repopulates transcript text after reload/BFCache navigation | Mark source controls non-restorable; clear them during mount, the next event-loop turn, and persisted `pageshow`; abort and epoch-fence transcript reads; never put source text in retry storage |
| High | A 17th unknown outcome evicts an unresolved idempotency key, or a storage exception is treated as an empty retry journal | Preserve every unresolved key, reject new attempts at capacity, fail closed on read/write/corruption, and persist candidate state before committing memory |
| Low | A two-cell Unicode glyph overwrites a one-cell label border | Replace an unrepresentable glyph with a deterministic one-cell marker and assert the display-cell budget |
| Low/legal | Rebranding removed upstream source notices | Restore all 240 original notice lines mechanically and verify none are missing; add explicit MonoSketch attribution |
| Critical/dependency | PostgreSQL JDBC 42.6.0 matched reviewed SQL-injection and SCRAM CPU-exhaustion advisories | Upgrade to patched 42.7.11 and rerun the backend/release gates |

## Deliberately open findings

| Priority | Finding | Why it remains open / required closure |
|---|---|---|
| P0 hosted | HS256 validator has no public identity, rotation, or durable revocation lifecycle | Requires an operator-selected identity system and distributed session store |
| P0 hosted | Credential store is local AES/GCM derived from application key material | Requires cloud/platform KMS integration and policy/audit design |
| P0 hosted | No distributed/global body/rate/concurrency limiter | Transcription has an endpoint-local pre-deserialization ceiling; hosted operation still requires ingress-wide and shared limiter/backpressure controls |
| P0 hosted | Only the source-ingestion claim slice is exercised against PostgreSQL in CI | Expand real PostgreSQL/pgvector coverage to queue/crash/upgrade paths and complete restore/rollback drills |
| P0 hosted | Regex SVG sanitizer is defense-in-depth, not a complete parser allowlist | Do not serve arbitrary third-party SVG; replace with a tested XML element/attribute allowlist before enabling SVG upload/export |
| P1 | Webhook replay memory is process-local | Persist delivery IDs with expiry and test concurrent replicas |
| P1 | Admin surface is API-only and lacks an operator console | External-spend decisions already require confirmation and create redacted immutable audit rows; add narrow views, receipt correlation, alerts, and equivalent audit coverage for every other mutation |
| P1 | Provider/GitHub write integrations are mock-transport tested | Add opt-in sandbox canaries; never put live credentials or private fixtures in the repository |
| Scanner metadata exception | OSV still returns Jackson `GHSA-5jmj-h7xm-6q6v` for resolved 2.18.9 | The authoritative advisory fixes the issue in 2.18.9 and OSV's own enumerated affected versions omit 2.18.9. Keep the discrepancy visible and recheck both sources on every update |

## Rejected shortcuts

- Imported repositories are never cloned into an executable workspace, built, or run.
- Provider output never chooses tools, credentials, repository permissions, destinations, or
  publishing actions.
- A user-supplied workspace ID is not trusted as authorization; durable membership is rechecked.
- Generated claims without evidence are not silently promoted to factual output.
- GitHub publishing creates a reviewable pull request and never auto-merges.
- Proprietary Samsung Sharp Sans binaries are not committed; the CSS uses a licensed local font
  when available and an OFL-compatible bundled fallback otherwise.

## Verdict

No known critical defect remains in the supported local prompt/transcript/public-GitHub flow after
remediation and a final independent read-only review returned `PASS`. The open P0 findings prevent
a credible public hosted-production claim and must stay visible in release notes and the roadmap.
