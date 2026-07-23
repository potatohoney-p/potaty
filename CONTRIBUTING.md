# Contributing to Potaty

Thank you for helping make source-grounded diagrams easier to create and trust. Small, reviewable changes with tests are the fastest path to a merge.

By participating, you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md). Security reports belong in the private process described in [SECURITY.md](SECURITY.md), not in a public issue.

## Before you start

- Search existing issues and pull requests.
- Open an issue before a large feature, dependency, schema change, or UI redesign.
- Read [docs/DESIGN.md](docs/DESIGN.md) before visual work and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) before changing data flow or module boundaries.
- Never include customer data, real credentials, private repositories, proprietary fonts, or assets without redistribution rights.

## Development setup

Install JDK 17, Node.js 22 LTS, npm, and Chrome or Chromium. Clone the repository, then install the locked JavaScript build dependencies:

```bash
npm ci
```

Run the browser app:

```bash
./gradlew browserDevelopmentRun --continuous --no-parallel
```

Run the API on port 8090 in another terminal:

```bash
export POTATY_PORT=8090
export POTATY_AUTH_MODE=dev
export POTATY_DEV_AUTH=true
export POTATY_DEV_TOKEN="$(openssl rand -hex 32)"
export POTATY_DEV_PROJECT_ID=00000000-0000-0000-0000-000000000010
export POTATY_CREDENTIAL_KMS_KEY="$(openssl rand -hex 32)"
./gradlew :backend:run
```

The browser Studio keeps the access token only in page memory, so a reload requires entering it
again. The runtime URL, project ID, optional GitHub App URL, and opaque retry identifiers are
tab-scoped in `sessionStorage`; raw source text and credentials are not. Enter the generated token
under **Runtime settings** and use project ID `00000000-0000-0000-0000-000000000010` for the
localhost H2 flow.

Windows contributors should replace `./gradlew` with `.\gradlew.bat` and set environment variables through PowerShell. The root README includes an example.

## Verification

Run the smallest relevant test while iterating, then the full gate before opening a pull request:

```bash
npm ci
npm audit --audit-level=moderate
./gradlew ktlint --no-daemon --no-parallel
./gradlew test browserProductionWebpack :backend:installDist --no-daemon --no-parallel
```

Backend-only changes should also pass:

```bash
./gradlew :backend:test --no-daemon --no-parallel
```

See [docs/TESTING.md](docs/TESTING.md) for targeted modules, current infrastructure gaps, and manual smoke tests.

## Engineering expectations

- Preserve the canonical Diagram IR as the renderer-independent source of truth.
- Keep evidence, confidence, warnings, and source snapshots intact through every transformation.
- Treat prompts, transcripts, repository files, provider output, SVG, and Mermaid as untrusted input.
- Never execute imported repository code.
- Keep every tenant-owned read and write scoped by workspace ID.
- Keep secrets out of logs, all browser storage, fixtures, generated diagrams, and Git history.
- Add negative-path tests for authorization, malformed input, external failures, and tenant isolation.
- Preserve deterministic rendering. Update golden output only when the visual change is deliberate and reviewed.
- Avoid adding a dependency when the platform or standard library already solves the problem.
- Preserve the original MonoSketch copyright line in every derived editor/build file; add Potaty's
  notice on a separate line instead of replacing upstream attribution.

## Commit and pull-request guidance

- Keep commits focused and use clear imperative subjects, for example `fix: reject unscoped GitHub installations`.
- Do not mix mechanical formatting with behavioral changes.
- Explain the user-visible outcome, failure modes, tests, and any migration or deployment impact in the pull request.
- Include screenshots for UI changes and before/after ASCII output for renderer changes.
- Link the issue being addressed. If no issue exists, explain why the change is small enough to review directly.
- Do not commit generated build directories, `.env`, local IDE state, private fixtures, or proprietary font binaries.
- Treat `LICENSE`, `NOTICE`, and `THIRD_PARTY_NOTICES.md` as release-critical files.

## Documentation

User-visible changes must update the [changelog](CHANGELOG.md), README, or the relevant document under `docs/`. New environment variables belong in `.env.example` and [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md). New dependencies or bundled assets require compatible licence information in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Getting help

Use a GitHub discussion if enabled, or open a focused issue using the question/feature templates. Include operating system, JDK/Node versions, the exact command, and the first actionable error. Redact tokens, repository contents, and personal data.
