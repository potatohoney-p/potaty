# Testing

Potaty spans Kotlin/JS, Kotlin/JVM, browser rendering, persistence, provider adapters, and containers. A production release needs evidence at each boundary.

## Local commands

```bash
npm ci
npm audit --audit-level=moderate

# All configured Kotlin tests plus root verification
./gradlew ktlint --no-daemon --no-parallel
./gradlew test --no-daemon --no-parallel

# Backend only
./gradlew :backend:test --no-daemon --no-parallel

# Release artifacts
./gradlew browserProductionWebpack :backend:installDist --no-daemon --no-parallel
```

Windows uses `.\gradlew.bat`. Chrome or Chromium is required by Karma browser tests.

## Last verified local release gate

The 2026-07-23 worktree gate ran `npm ci`, `npm audit --audit-level=moderate`, and this combined
Windows command after the release-hardening pass:

```powershell
.\gradlew.bat ktlint test browserProductionWebpack :backend:installDist --no-daemon --no-parallel --console=plain
```

- 95 test suites / 518 tests: 0 failures, 0 errors, 1 conditional local skip. The skipped
  PostgreSQL/pgvector claim test runs in CI with its disposable database service.
- npm audit: 0 moderate-or-higher findings.
- Browser bundle: 302,568 bytes gzip, below the enforced 327,680-byte ceiling.
- Backend distribution: installed successfully (79 files, 32,732,516 bytes).
- `git diff --check`: no whitespace errors; Windows line-ending warnings only.
- Docker was not installed on the review machine, so Compose interpolation, image build, and the
  PostgreSQL/container health smoke remain CI/staging evidence rather than a local pass.

The Maven OSV scan still emits one Jackson range hit for 2.18.9. The authoritative advisory marks
2.18.9 fixed and OSV's enumerated vulnerable versions omit it; see [Security](../SECURITY.md). Treat
this as an explicit scanner-data adjudication, not a clean-scan claim or a hidden suppression.

## Targeted suites

```bash
./gradlew :diagram-ir:allTests
./gradlew :layout-engine:allTests
./gradlew :render-core:allTests
./gradlew :renderer-codegen:allTests
./gradlew :workbench-client:allTests
./gradlew :backend:test
```

Task names can vary with Kotlin plugin targets. Use `./gradlew tasks --all` if a targeted alias is unavailable.

## Manual smoke test

1. Start the H2 backend on port 8090 with `POTATY_AUTH_MODE=dev`, a random development token, and the default local project ID.
2. Start `browserDevelopmentRun`.
3. Enter the API URL, token, and local project UUID in Runtime settings.
4. Generate from a short prompt and confirm ASCII, Mermaid, evidence, warnings, copy, and download.
5. Upload a UTF-8 transcript file and verify speaker/timestamp evidence where present.
6. Index a small public GitHub repository and verify no imported code executes.
7. Open the manual editor and verify the generated diagram remains editable.
8. Exercise invalid token, unreachable API, invalid repository, empty input, oversized transcript, and provider failure states.

## CI gate

`.github/workflows/ci.yml` installs locked npm dependencies, audits them, runs Kotlin tests/style
checks, builds the production browser bundle and backend distribution, and supplies a disposable
pgvector PostgreSQL service to the Flyway source-ingestion claim integration test. It also validates
Compose interpolation and starts the backend container under its read-only, non-root runtime
controls before checking health.

## Known gaps

- Most backend tests use H2. CI additionally runs the focused source-ingestion claim/Flyway contract
  against real PostgreSQL/pgvector, but broad queue, crash, upgrade, and restore coverage is open.
- There is no Playwright/Cypress end-to-end suite or automated accessibility scan.
- GitHub, OpenAI, and transcription tests use mocked HTTP transports rather than live sandbox credentials.
- PostgreSQL V1–V10 migrations are exercised by the focused claim test; backup/restore, rollback,
  upgrade compatibility, and full multi-instance queue semantics are not exercised end to end.
- No coverage threshold or mutation testing gate is configured.
- Visual regression coverage is limited; ASCII golden fixtures do not replace browser screenshots.

These gaps block a hosted-production claim. Expand PostgreSQL coverage, add automated browser E2E,
visual/accessibility checks, restore drills, and live canaries before the first stable release.

The production bundle has also been exercised manually through Playwright-controlled Chromium,
including live prompt and transcript API round trips, storage inspection, reload recovery,
console/network checks, and a 390×844 responsive pass. The English product captures under
[`docs/assets/qa`](assets/qa) are versioned presentation evidence, not an automated repository gate.

The final hardening pass additionally runs Node and ChromeHeadless regressions for fetch/body
timeouts, manual abort and late callbacks, retry-journal capacity/storage failure, generation-owner
serialization, native typed-array/UUID behavior, and a controllable-FileReader controller lifecycle
covering replacement, removal, mount, persisted `pageshow`, and stale success/error callbacks.

## Test data

Use synthetic sources. Never add real call transcripts, private repositories, production logs, access tokens, personal data, or proprietary assets to fixtures or snapshots.
