# QA Report: Potaty production bundle

| Field | Value |
|---|---|
| Date | 2026-07-16–17 |
| URL | Primary pass at `http://127.0.0.1:4173`; final clean-cache regression at `http://127.0.0.1:4175` |
| API | Fresh `:backend:installDist`; final regression used `http://127.0.0.1:18082` |
| Branch | `main` + uncommitted production-readiness worktree |
| Commit | `c70cee0` |
| Tier | Exhaustive local production-bundle QA |
| Browser | Headless Chromium, 1440×1000 and 390×844 |
| Scope | Prompt, UTF-8 transcript, public GitHub, settings, errors, keyboard, accessibility tree, responsive layout, console, network, storage, reload/BFCache privacy, abort/retry races, and performance |

## Health score: 96/100

| Category | Score |
|---|---:|
| Console and network | 100 |
| Functional | 98 |
| Visual | 98 |
| UX and recovery | 97 |
| Accessibility baseline | 94 |
| Responsive | 100 |
| Performance | 86 |

The supported local/self-hosted evaluation flow is shippable as an open-source preview. The score
is intentionally below 100 because this pass is Chromium-only, is not an assistive-technology
certification, the JavaScript bundle is close to its ceiling, and the full Korean diagram font is
large when first needed.

## Core-flow evidence

| Flow | Result | Evidence |
|---|---|---|
| Prompt | Pass | 263-character live source → async job → 7-node/3-edge grounded ASCII result with 100% node/edge evidence coverage |
| Transcript | Pass | Uploaded 303-byte/147-character UTF-8 Korean fixture → 9-node/5-edge action map with speaker/timestamp evidence and 100% coverage |
| Public GitHub | Pass | `octocat/Hello-World` completed with 1 file/1 chunk; `tuanchauict/MonoSketch` read 55 files, safely skipped 256, produced 518 chunks in 28.6 s, and rendered 16 nodes/23 edges at 100% coverage without executing code |
| Needs input | Pass | Unstructured `...` source returned terminal `needs_input`, progress 1.0, and an actionable reason |
| Upstream recovery | Pass | A later anonymous GitHub retry received an upstream 502 and rendered the bounded, actionable repository recovery state without exposing provider details |

After the exact final production build, a fresh Selenium-managed Chrome 150 profile repeated the
prompt flow against port 18082: source `201`, job `202`, polling/result `200`, 529-character ASCII
artifact, four evidence facts, 100% coverage, zero warnings, and zero severe console entries.
Reload cleared prompt/repository/ref/token state while retaining only non-secret tab settings and
opaque retry ownership.

## Issues found and closed

1. Runtime settings actions were inert. Event wiring was corrected; save/reset/close/backdrop and
   Escape now work, focus returns to the opener, and the token remains only in page memory.
2. An invalid IPv6 CSP source polluted the console. The directive was corrected and the final
   successful runs have no console errors.
3. Pasting an API URL that already ended in `/api/v1` produced a doubled route. The field now says
   “API origin — without /api/v1” and normalizes an accidentally pasted suffix.
4. A long prompt objective dominated the result H1 and document title. Concise IR titles are kept;
   long objectives use the bounded source display name.
5. Single-item summaries said “1 nodes / 1 chunks.” Result, quality, and GitHub source summaries
   now use correct singular/plural labels.
6. Leaving the Studio during generation could invalidate the guarded cleanup and leave Generate
   disabled. Studio exit now restores the control explicitly.
7. The minified production bundle failed before its first API request because Kotlin's external
   `Uint8Array[index]` mapping emitted a nonexistent `bytes.get(index)` call. Native JavaScript
   bracket indexing plus a browser regression test fixed the SHA-256 retry fingerprint path. The
   clean-cache rerun completed source creation, job enqueue, status polling, and result fetch with
   expected `201`/`202`/`200` responses and no console error.
8. A successful job without usable output, a stalled response body, or Back during an in-flight
   request could rotate/unlock retry state too early. Usable-result consumption, whole-response
   abort/deadline, and generation ownership gates now preserve exact replay semantics.
9. Reload/history restoration and late FileReader callbacks could revive raw source controls.
   Source autocomplete is disabled, lifecycle scrubs run at mount/next-task/persisted `pageshow`,
   and transcript reads are aborted plus generation-fenced. Production reload/history checks and
   a controllable-FileReader ChromeHeadless test cover the full wiring.
10. The bounded retry journal could evict an unresolved attempt or treat storage failure as empty
    state. It now rejects new work at capacity, latches storage/corruption failures, and commits
    memory only after durable session storage. Throwing-store and retirement-reload tests protect
    the boundary.

## Accessibility and responsive checks

- One visible H1, banner/main/contentinfo landmarks, real tabs/tabpanels, labelled controls,
  status/live feedback, and a skip link are exposed in the accessibility tree.
- Settings initially focuses the API field; Escape closes the modal and restores focus to the
  settings button.
- At 390 px the document and body are exactly 390 px wide; no document-level horizontal overflow
  occurs.
- Every visible button, link, input, select, and tab measured at least 44 px in one dimension at
  the mobile result viewport; the automated small-target query returned an empty list.
- Complex diagrams scroll inside the labelled diagram canvas rather than shifting the document.
- Korean labels retain box borders and connector alignment with D2Coding display-cell metrics.

## Console, storage, and network

- Final successful prompt/transcript/GitHub runs produced no console errors.
- All API calls returned expected 2xx responses; failure-state checks returned their expected
  4xx/terminal contracts.
- The runtime access token was absent from both `sessionStorage` and `localStorage`, source
  summaries, generated ASCII, Mermaid, and screenshots. A reload cleared the in-memory credential
  and returned the header to `Connection required`.
- Tab-scoped retry storage contained only `sha256:<64 lowercase hex>` request digests and opaque
  `gen-<UUID>` idempotency keys; the raw prompt was absent.
- A delayed source request was aborted through Back, the stale coroutine unwound before retry
  ownership reopened, and the following live prompt completed normally. Reload and history restore
  left prompt/GitHub controls empty; the token and both raw fixtures remained absent from storage.
- Settings normalized `http://127.0.0.1:8090/api/v1` to `http://127.0.0.1:8090` before storage.

## Performance observations

- Local production page: about 0.9 s load event in the QA environment.
- Production bundle is 302,571 bytes gzip against the enforced 327,680-byte ceiling (92.3%).
- Pretendard loads as small Unicode-range subsets. D2Coding is deferred until a result needs the
  diagram font, but then transfers about 1.49 MiB; subsetting/splitting is on the roadmap.

## Screenshots

- [Final prompt result — desktop](../assets/qa/final-prompt-desktop.png)
- [Final prompt result — 390 px mobile](../assets/qa/final-prompt-mobile.png)
- [UTF-8 Korean transcript result — desktop](../assets/qa/final-transcript-desktop.png)

## Residual QA limits

- Firefox, Safari/WebKit, screen readers, forced colors, 200%/400% zoom, reduced motion, and an
  automated axe/WCAG 2.2 AA matrix were not available in this local pass.
- Private GitHub App and provider-paid flows require opt-in sandbox credentials and must not use
  secrets or private fixtures in public CI.
- Hosted abuse controls, PostgreSQL topology, KMS, backup/restore, and deployment canaries are
  production gates, not browser-only concerns.

## Verdict

The production bundle passes the three supported browser journeys and the reviewed accessibility,
responsive, error, storage, console, and performance baseline for an open-source preview. It must
not be represented as a fully hosted, cross-browser, WCAG-certified production service until the
P0 roadmap evidence exists.
