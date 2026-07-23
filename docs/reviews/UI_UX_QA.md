# Browser UI/UX QA

Review date: 2026-07-17
Target: production Kotlin/JS bundle served locally; the final clean-cache regression used
`http://127.0.0.1:4175` with the installed H2 API at `http://127.0.0.1:18082`
Browser: headless Chromium, desktop and 390 px mobile viewports

## Outcome

The Studio's supported prompt, text-transcript, and public-GitHub journeys are usable end to end in
the production bundle. The earlier blocking Runtime settings event-wiring defect and invalid CSP
source were fixed and regression-checked. The visual result follows the Potaty technical-field-
notebook system rather than a generic card dashboard.

## Checks performed

| Dimension | Result |
|---|---|
| Visual hierarchy | Clear source → generation → result progression; ASCII remains primary |
| Typography | Samsung Sharp Sans local-first stack; bundled Pretendard fallback; D2Coding for Korean-capable diagram output |
| Core flows | Prompt, UTF-8 transcript, and public GitHub complete against the live local API |
| Runtime settings | Save, close, reset, backdrop/Escape behavior and validation work; token is page-memory-only and clears on reload |
| Accessibility structure | One visible H1, banner/main/contentinfo landmarks, tabs/tabpanels, labels, status/live feedback and keyboard controls |
| Touch targets | Visible controls at 390 px meet the 44 px minimum in at least one dimension |
| Responsive layout | No document-level horizontal overflow at 390 px; toolbars wrap and the diagram uses an intentional inner viewport |
| Error recovery | Invalid token, unavailable API, invalid source/repository and validation failures remain actionable |
| Console | Clean after a fresh final navigation and successful generation |
| Storage | No access token in `localStorage` or `sessionStorage`; retry state contains only SHA-256 digests and opaque UUID keys, never source text |
| Reload and recovery privacy | Prompt/GitHub fields clear on reload/history restoration; transcript readers abort and stale callbacks cannot repopulate scrubbed text |
| Retry concurrency | Back aborts in-flight HTTP and a new generation starts only after the previous owner has unwound; unresolved keys are never evicted |
| Document context | Result H1 and browser title track the artifact; Back resets the title |

The recorded successful fixtures produced a 7-node/3-edge prompt diagram, a 9-node/5-edge Korean
transcript diagram, and a 16-node/23-edge realistic repository diagram, each with 100% evidence
coverage. The final production bundle is 302,571 bytes gzip. A final live prompt regression also
produced a 3-node/1-edge result at 100% coverage after the retry-fingerprint fix, with expected API
responses and no console errors. Reload cleared the access token while retaining only non-secret
tab settings and the source digest/idempotency key needed for safe retry.

## Result-layout observations

- The source/evidence rail and diagram canvas maintain a useful reading order on desktop.
- Korean labels use correct display-cell measurement; boxes and connectors do not drift because of
  double-width glyphs.
- Edge labels no longer collide with nearby node borders in the reviewed transcript fixture.
- Zoom/copy/download/back controls remain visible without obscuring evidence or the ASCII surface.
- At 390 px the page itself stays fixed-width while the complex diagram scrolls within its own
  labelled region, preventing the entire document from becoming horizontally lost.
- The final 390×844 Runtime settings regression measured a 390 px document/scroll width, kept the
  dialog within the viewport, and placed initial focus on the API field.

## Evidence

- [Final prompt result — desktop](../assets/qa/final-prompt-desktop.png)
- [Final prompt result — 390 px mobile](../assets/qa/final-prompt-mobile.png)
- [UTF-8 Korean transcript result — desktop](../assets/qa/final-transcript-desktop.png)
- [Detailed production-bundle browser run](BROWSER_QA_REPORT.md)

## Accessibility limitations

This was a browser/accessibility-tree and keyboard QA pass, not a certification. Automated axe
coverage, screen-reader sessions, 200%/400% zoom matrices, forced-colors, reduced-motion automation,
Safari/Firefox, and a full WCAG 2.2 AA audit remain release gates for a hosted stable version.

## Ship decision

The UI is ready for an open-source preview and controlled self-hosted evaluation. It should not be
described as universally accessible or cross-browser production-proven until the automated and
assistive-technology matrix in the roadmap passes.
