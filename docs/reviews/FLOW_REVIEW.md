# Product flow review

Review date: 2026-07-17

## Common generation contract

Every supported input follows the same auditable sequence:

```text
select source -> validate locally -> ingest under tenant -> normalize/redact/chunk
              -> enqueue idempotently -> extract/enrich -> validate Diagram IR
              -> persist immutable version -> render ASCII/Mermaid -> inspect evidence
```

The browser exposes explicit preparing, queued, processing, success, needs-input, failure, and
retry states. It never fabricates a successful diagram locally when the API failed.

## Prompt flow

1. The user writes a bounded prompt and chooses the diagram controls.
2. The Studio checks runtime connection settings and sends a `TEXT_PASTE` source.
3. The browser assigns independent source and job keys to the exact logical request. It persists
   only SHA-256 request digests and opaque keys, so a lost response or same-tab reload can safely
   replay without storing source text or the bearer token. Native typed-array indexing is covered
   in a browser regression so the production bundle cannot silently substitute a nonexistent
   accessor while converting the Web Crypto digest. The retry journal commits to session storage
   before a mutation is issued, never evicts an unresolved attempt, and fails closed if storage is
   unavailable or its bounded unresolved capacity is full.
4. The backend binds the source key to the text request, normalizes/redacts, and atomically stores
   source, first version, and evidence chunks; the separate job key enqueues one production-quality
   diagram attempt.
5. The Studio polls through the backend's bounded provider retry window until a terminal job state
   is available; every HTTP response has a native abort signal and whole-response deadline.
   `needs_input` and failures include a safe actionable reason. A nominal success without usable
   output remains an unknown outcome and keeps its job key for authoritative replay.
6. The result view presents ASCII first, evidence coverage, warnings, source summary, Mermaid,
   copy/download, zoom, and manual-editor insertion.
7. If a lease-reclaimed worker repeats the job after persistence, the unique job binding returns
   the already committed diagram/version/renderings rather than producing a second artifact.

Review outcome: complete for the local API flow, including empty input, invalid authentication,
unreachable API, validation error, and retry presentation.

## Transcript-file flow

1. File selection is limited to documented text formats and 2 MiB; the browser decodes UTF-8 and
   reports decoding/size errors before upload. Replacement and lifecycle cleanup abort the active
   `FileReader` and invalidate its generation token, so a late callback cannot restore scrubbed
   transcript text.
2. The backend accepts `TRANSCRIPT`, preserves display name, and creates evidence chunks with
   speaker/timestamp metadata when present. Source/version/chunks share the same atomic,
   request-bound replay contract as prompt input.
3. Korean and English relation phrases feed deterministic extraction; optional enrichment is
   constrained by the same evidence and output boundary as prompt input.
4. The result view shows the source file summary and lets the user trace claims back to transcript
   evidence.

Review outcome: complete for text transcripts. The backend audio transcription endpoint remains
an operator integration surface, not yet a finished end-user audio-upload flow. Its security flow
is nevertheless closed: tenant project check → pre-deserialization request ceiling → audio/hash
validation → quota reservation and prior-result/checkpoint replay → server-side credential lookup
for a fresh call → provider fence/call → durable checkpoint → atomic normalized transcript and
usage completion.

## GitHub flow

1. The Studio accepts an HTTPS `github.com/{owner}/{repo}` URL and optional bounded ref.
2. Public repositories use read-only GitHub API requests. Private repositories require a verified,
   workspace-bound App installation.
3. The indexer reads repository metadata/tree and selected text blobs under byte/file/path limits,
   applies ignore rules, and records truncation warnings. Before credential or network work, it
   acquires a database-clocked tenant/request claim; malformed successful tree/blob payloads abort
   without a partial snapshot. It never executes repository content.
4. The resulting source/version/path chunks commit together. If the response is lost, the exact
   key replays the stored ids and original ref before another GitHub request or installation-token
   lookup; changed input returns a conflict.
5. Indexed chunks retain path/line/commit evidence and enter the common job flow.

Review outcome: the public read-only flow is complete and browser-tested. Private App installation
and pull-request publishing are code/mock tested but require a live operator-owned integration
canary before production use.

## Result and recovery flow

- The result H1 and document title identify the current artifact.
- Server validation and per-metric evidence thresholds drive both the quality card and the top-level
  status; a blocked or review-required artifact never claims “Ready”.
- ASCII preserves whitespace and Unicode cell width; controls remain keyboard reachable and at
  least 44 px in the tested mobile layout.
- Copy/download feedback is announced; evidence and warnings remain visible instead of being
  discarded after generation.
- Back returns to source preparation without retaining a stale result title.
- Back aborts the active request and keeps run ownership until the stale coroutine has unwound, so
  an immediate retry cannot be cancelled by the prior run. Source controls are explicitly cleared
  on reload and BFCache restoration; the browser never restores raw prompt/repository text.
- Requested code formats remain attached to the immutable version; reopening a D2/DOT/PlantUML/
  Markdown result does not silently turn it into Mermaid.
- A proven terminal failure/cancellation rotates to a new explicit attempt without mutating
  historical evidence. Unknown outcomes, failed cancellation acknowledgement, and cancellation
  racing with success retain the original key so retry resumes or loads the same server job.
  Storage failures and unresolved-capacity exhaustion block before another server mutation.

## Operator flow

Health, readiness, Prometheus output, job diagnostics, cancellation, usage, connection management,
retention foundations, and owner-only uncertain-spend reconciliation exist as backend APIs. A
production operator console, audited identity onboarding, retention scheduler, and disaster-
recovery workflow remain roadmap work.

Cost admission now uses durable per-workspace reservations: equivalent idempotent requests reuse
one reservation, concurrent requests cannot overbook a stale snapshot, active jobs retain their
reservation beyond the unattached crash timeout, and every terminal state releases or excludes it.
For direct external calls such as transcription, the route additionally fences the reservation
before calling the provider. A successful response is checkpointed first; source, version, chunks,
usage, replay result, and release then commit together. An expired checkpoint is resumed only by
the exact original request, even after credential rotation. Only checkpoint-free uncertain spend
enters the expired-lease owner queue, where charge/release requires confirmation and an audited,
secret-redacted reason. A charge additionally requires the correlated provider-receipt amount;
the estimate cannot be accepted implicitly.
