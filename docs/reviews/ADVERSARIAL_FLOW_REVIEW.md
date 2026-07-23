# Adversarial flow review

Review date: 2026-07-17

This review asks what happens when a user, source, provider, browser, worker, or upstream service
behaves incorrectly at each product boundary.

## Attack and failure journeys

| Journey | Expected safe outcome | Current result |
|---|---|---|
| Empty/oversized prompt or control arrays | Reject before expensive work; actionable message | Enforced by browser and API bounds |
| Transcript with NUL, ANSI escape, bidi override, or secret assignment | Controls removed, secrets redacted, ordinary evidence retained | Enforced and regression-tested |
| Transcript disguised as an unsupported source kind | Route rejects instead of invoking an unintended pipeline | Enforced |
| Prompt injection asks the model to reveal keys or publish code | Source remains fenced data; model has no tool/credential/publish authority | Enforced structurally plus advisory detection |
| Source includes Potaty's exact closing prompt marker | Marker is neutralized and remains inside the sole authoritative untrusted-data fence | Enforced and regression-tested |
| LLM returns huge arrays, control text, fake IDs, or unknown enums | Schema/parse boundary rejects or bounds/remaps data before IR | Enforced |
| `github.com.evil.invalid`, userinfo host, encoded path tricks, invalid ref | Request rejected without fetching or reflecting hostile input | Enforced |
| Repository contains build hooks, binaries, symlinks, or enormous files | Treat only selected bounded text blobs as data; execute nothing | Enforced by architecture and index limits |
| Token belongs to another/deleted workspace membership | Reject even if JWT claims look valid | Membership-bound session resolution and tenant tests |
| Reuse one idempotency key with altered source/control fields | Return conflict; do not return or mutate the earlier source or job | Enforced for source/GitHub/job request hashes |
| Text/transcript storage fails between source, version, and chunks | Roll back every row; an exact retry creates or replays one complete artifact | Enforced with injected mid-chunk failure and API replay tests |
| GitHub response is lost, the connection is revoked, or the default branch changes before retry | Return the original stored ids/ref before remote or installation-token work | Enforced with an external-request counter and stored metadata |
| Two callers race on first-time GitHub ingestion | A database-clocked claim selects one pre-outbound owner; stale owners cannot renew/finalize, and the winner commits one replay artifact | Enforced in H2 and a gated concurrent PostgreSQL/Flyway CI contract |
| GitHub returns `200` with omitted tree/blob fields, a mismatched SHA/size, or duplicate blob paths | Reject the entire snapshot and release the claim; persist nothing | Enforced with malformed and zero-byte payload regressions |
| Worker stalls past lease, is cancelled, or loses ownership | Renew or stop stale work; fenced transition cannot overwrite winner | Enforced |
| Lease-reclaimed workers both finish generation | One transaction commits diagram/version/renderings; the unique workspace/job binding makes every replay return the same ids | Enforced and replay-tested |
| Exception contains an API key or source body | Generic client/job message and sanitized operator trace | Enforced |
| Malformed JSON embeds sensitive source text in a parser message | Stable source-free 400 response | Enforced and regression-tested |
| LLM succeeds after invalid paid repair attempts | All returned token usage is accumulated, billed to the job/workspace, and traced on the version | Enforced and H2 integration-tested |
| Two requests race just below the monthly spend cap | Workspace-row serialization admits only the request that fits; the other receives quota exceeded | Enforced with a concurrent H2 regression test; PostgreSQL soak remains a hosted gate |
| A released/expired reservation key is reused after later spend | Re-run admission against current usage instead of reviving it blindly | Enforced and regression-tested |
| A long-running attached job crosses the unattached reservation TTL | Follow authoritative non-terminal job state; do not create false quota headroom | Enforced and regression-tested |
| Audio request supplies both sources, oversized base64, or a forged/revoked/cross-tenant credential id | Bound raw bytes before JSON decode; reject invalid input before the external-spend fence/provider call with a stable source-free response; encrypted refs never cross HTTP | Enforced and regression-tested |
| Audio request targets an unknown or another tenant's project | Return indistinguishable not-found before reading the body, resolving a credential, reserving quota, or calling the provider | Enforced with route-order tests |
| Chunked audio JSON omits `Content-Length` and grows without bound | Stop channel reads at the same hard ceiling before UTF-8/JSON decoding and return `413` | Enforced with declared-length and streaming regression tests |
| Provider speaker field contains a key, control characters, or bidi text | Store only speaker metadata re-derived from the normalized/redacted canonical transcript; restore numeric times only | Enforced and regression-tested |
| Provider returns a billable transcription but usage persistence fails | The pre-call external-spend fence survives TTL; usage insert and release roll back together, so later requests cannot consume false quota headroom | Enforced with a synthetic database insert failure |
| Provider succeeds and the process dies before transcript/usage completion | Persist a redacted checkpoint, retain it outside the uncertain-spend queue, and let only the exact idempotent retry finish after lease expiry without a second provider call | Enforced with crash, lease, rollback, and replay tests |
| Original transcription credential is rotated or revoked after provider success | Stored result/checkpoint replay bypasses credential lookup while still requiring the original body/hash | Enforced; resolver-call counter stays unchanged on recovery |
| Owner tries to charge/release a successful checkpoint | Hide it from the queue and reject direct reconciliation under a locked checkpoint-null predicate | Enforced in query, service guard, update fence, and H2 test |
| Owner chooses charge but omits the provider receipt amount | Reject at HTTP and transactional boundaries; never substitute an estimate | Enforced and regression-tested |
| PostgreSQL rewrites `1.230e-5` as decimal JSON or receives a seven-digit charge | Compare numeric JSON by value and reject charges above `999999.999999` before persistence | Enforced and regression-tested |
| Provider returns malformed nested JSON with valid paid usage | Preserve usage, classify as invalid output, and do not retry it as a network failure | Enforced for OpenAI and Anthropic |
| Worker is asked for D2/DOT/PlantUML/Markdown | Requested rendering is stored and returned after polling | Enforced and API-tested |
| Retention runs with diagrams, usage, credentials, GitHub state and audit rows | Remove dependent tenant data in FK-safe order | Enforced in application tables; backups/providers remain external work |
| Browser reloads after an unknown response | Keep only SHA-256 request digests and opaque retry keys; require the token again and resume the same logical operation | Enforced; token and raw source are never stored |
| Browser/BFCache restores raw source fields, or a pending FileReader finishes after the privacy scrub | Clear controls during mount/next-task and persisted `pageshow`; abort and epoch-fence transcript reads without touching the opaque retry journal | Enforced with production reload/history checks and a stale-read lifecycle regression |
| Production minification or Kotlin/JS interop breaks Web Crypto digest conversion | Fail before any outbound side effect, cover native byte access in a browser test, and prove the built artifact with a clean-cache live API run | Native bracket indexing is regression-tested; final production run completed with zero console errors |
| Cancel request fails or races with server success | Stop locally but retain the job key until cancellation or result consumption is authoritative | Enforced with client orchestration regressions |
| A nominally succeeded job omits output, or its response arrives after cancellation | Keep the same job key and recover the authoritative output instead of creating another billable attempt | Enforced by result-consumption and cancel/success tests |
| Fetch or response-body read stalls indefinitely | Abort at the whole-response deadline, settle once, leave the key unresolved, and allow a safe exact retry | Enforced in Node and Chromium transport tests |
| Back-to-input is clicked while a source request is in flight | Abort the transport and keep the generation gate owned until the stale coroutine unwinds; only then allow retry | Enforced by gate tests and a delayed-request production-bundle run |
| A 17th distinct unknown outcome exceeds browser retry capacity | Preserve all unresolved keys and block the new mutation before allocating another key | Enforced with a bounded-capacity replay regression |
| Session retry storage is denied, full, corrupted, or fails during retirement | Latch the tab fail-closed; do not issue another mutation or commit candidate memory state until durable storage succeeds | Enforced with throwing-store and failed-retirement reload tests |
| API is unavailable/unauthorized | Preserve source, show recovery action, never claim success | Browser flow verified |

## Residual worst cases

1. The transcription route has a local pre-deserialization ceiling, but a public deployment without
   ingress-wide body/header/rate/concurrency limits can still be resource-exhausted by other routes
   or many individually valid uploads. Hosted release requires distributed controls and load proof.
2. A compromised application instance can use the local credential-encryption key. Hosted release
   requires KMS/HSM policy separation and audited decrypt calls.
3. Process-local JWT and webhook replay state is insufficient across replicas. Use durable shared
   state and test race/expiry behavior.
4. The focused source-claim test proves Flyway constraints, concurrent takeover, completion/replay,
   and retention isolation on PostgreSQL, but it cannot prove every queue, lock, upgrade, or restore
   path. Run the exact production image/topology and complete those drills in CI/staging.
5. Regex SVG cleaning is not a proof against every XML/CSS parser differential. Keep arbitrary SVG
   out of trusted browser contexts until a parser allowlist is implemented.
6. A malicious but authorized operator can misuse admin or GitHub write APIs. Production needs
   narrow operator roles, immutable audit records, review confirmation, and anomaly alerts.
7. Manual Chromium QA cannot prove Safari/Firefox, assistive-technology, or every locale. Automated
   cross-browser and WCAG gates remain mandatory.

## Failure-recovery quality

- Source input is retained in the browser when a request fails, allowing correction without
  re-entering sensitive text.
- Authentication and connection failures point to Runtime settings rather than exposing transport
  internals.
- Server errors carry a correlation ID and never expose raw exception text to the client.
- Job history is immutable; retry/cancellation/needs-input states are explicit and tenant-scoped.
- GitHub truncation is surfaced as evidence context rather than silently implying full coverage.

## Verdict

The supported local flow fails closed under the reviewed malicious inputs and concurrency cases.
The residual cases are deployment and distributed-systems gates; they are severe enough that the
project must continue to label itself a preview until the roadmap's P0 acceptance evidence exists.
