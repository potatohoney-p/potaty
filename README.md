# Potaty

<p align="center">
  <img src="docs/assets/readme-hero.svg" width="100%" alt="Potaty turns prompts, transcripts, and GitHub repositories into source-grounded ASCII diagrams you can verify." />
</p>

<p align="center"><strong>Turn untrusted source material into editable diagrams that retain evidence, confidence, and warnings.</strong></p>

<p align="center">
  <a href="https://github.com/potatohoney-p/potaty/actions/workflows/ci.yml"><img alt="CI status" src="https://github.com/potatohoney-p/potaty/actions/workflows/ci.yml/badge.svg" /></a>
  <a href="LICENSE"><img alt="Apache License 2.0" src="https://img.shields.io/badge/license-Apache--2.0-8eabb4?style=flat-square" /></a>
  <a href="https://kotlinlang.org/"><img alt="Kotlin JS and JVM" src="https://img.shields.io/badge/Kotlin-JS%20%2B%20JVM-7F52FF?style=flat-square&amp;logo=kotlin&amp;logoColor=white" /></a>
  <img alt="Open-source preview" src="https://img.shields.io/badge/status-open--source%20preview-c87a61?style=flat-square" />
</p>

> [!IMPORTANT]
> **Potaty is an open-source preview, not a public hosted-service release.** The prompt, text-transcript, and public-GitHub flows are release-gated for local development and controlled self-hosted evaluation. Public multi-tenant hosting still requires the identity, KMS, distributed abuse controls, PostgreSQL operations, privacy workflows, and cross-browser evidence tracked in the [production roadmap](docs/ROADMAP.md). Never expose development bearer-token mode to the public internet.

## What Potaty does

Potaty gives three very different inputs one consistent, reviewable path:

| Source | Current user flow | Evidence retained |
|---|---|---|
| Prompt or pasted text | Describe a system, process, or relationship | Normalized source chunks and claim references |
| UTF-8 transcript | Upload `.txt`, `.md`, `.markdown`, or `.log`, up to 2 MiB | File name, speaker, timestamp, and source spans when present |
| GitHub repository | Enter a public `github.com/{owner}/{repo}` URL, or use an operator-configured GitHub App | Repository ref, commit metadata, file path, line range, and truncation warnings |

Every supported flow produces a canonical, versioned Diagram IR before rendering. The result is ASCII first, Mermaid second, with evidence coverage, confidence, warnings, copy, download, zoom, and insertion into the manual editor.

The backend also has audio-transcription and GitHub pull-request publishing integration surfaces. They are not finished end-user flows. They require operator-owned credentials, deployment controls, and live canary evidence before production use.

## See the real product

The images below are from the production Kotlin/JS bundle running against the local Ktor API. They use synthetic input and were captured during the final browser QA pass.

![Prompt result in the Potaty evidence workbench, showing an editable Korean ASCII workflow with 100 percent evidence coverage](docs/assets/qa/final-prompt-desktop.png)

<p align="center"><sub>Prompt → normalized source → generated artifact → evidence and quality inspector</sub></p>

<table>
  <tr>
    <td width="68%"><img src="docs/assets/qa/final-transcript-desktop.png" alt="Korean transcript converted into an ASCII action map with speaker and timestamp evidence" /></td>
    <td width="32%"><img src="docs/assets/qa/final-prompt-mobile.png" alt="Potaty prompt result at a 390 pixel mobile viewport with accessible wrapped controls" /></td>
  </tr>
  <tr>
    <td align="center"><sub>UTF-8 transcript flow</sub></td>
    <td align="center"><sub>390 px responsive result</sub></td>
  </tr>
</table>

More detail, including console, storage, keyboard, failure, and responsive checks, is in the [browser QA report](docs/reviews/BROWSER_QA_REPORT.md).

## Why the output is reviewable

- **Evidence stays attached.** Nodes and relations point back to source chunks instead of becoming unsupported presentation copy.
- **Uncertainty stays visible.** Confidence, inferred claims, validation warnings, and coverage are first-class parts of the artifact.
- **The core is deterministic.** Canonical IR, layout, Unicode cell measurement, and ASCII rendering have repeatable outputs and golden tests.
- **Repository code is never executed.** GitHub imports fetch selected, bounded text files as untrusted data. Potaty does not clone into an executable workspace, install dependencies, build, or run imported code.
- **Retries preserve one logical operation.** Request-bound idempotency keys, atomic source persistence, job fencing, and browser recovery prevent a lost response from silently creating another source snapshot or provider charge.
- **Tenant boundaries are explicit.** Every tenant-owned backend operation is workspace-scoped, and JWT claims are rechecked against active database membership.

## How data moves

![Potaty pipeline from untrusted prompt, transcript, and GitHub input through normalization, canonical Diagram IR, deterministic layout, and ASCII, Mermaid, or editor output](docs/assets/readme-pipeline.svg)

Generation is not client-only. The browser sends the selected source to the configured Potaty API.

1. The backend bounds, normalizes, safety-scans, and chunks the source.
2. Source, initial version, and evidence chunks commit atomically under a request-bound idempotency key.
3. Deterministic extractors build entities and relations. Optional provider enrichment can assist sparse prose when an operator explicitly configures it.
4. Diagram IR validation records evidence coverage, confidence, inferred claims, and warnings.
5. The backend persists the immutable artifact and requested code renderings, then the browser lays out and renders the ASCII result.

OpenAI receives source text only when an operator configures optional enrichment and the pipeline invokes it, or when the transcription API is explicitly called. GitHub receives repository API requests only for repository indexing. The browser bearer token is page-memory-only and clears on reload; browser storage retains only non-secret settings plus opaque recovery keys and SHA-256 request digests.

Read [Architecture](docs/ARCHITECTURE.md), [Security](SECURITY.md), and [Privacy](docs/PRIVACY.md) before using real data.

## Quick start

### Prerequisites

- JDK 17
- Node.js 22 or a newer supported LTS release
- npm 10+
- Chrome or Chromium for browser tests
- Docker with Compose v2, optional for PostgreSQL

### 1. Clone and install the locked web dependencies

```bash
git clone https://github.com/potatohoney-p/potaty.git
cd potaty
npm ci
```

### 2. Start the local H2 backend

The local backend uses an ephemeral in-memory H2 database. Generate separate random values for the development token and local credential-encryption key:

```bash
export POTATY_PORT=8090
export POTATY_AUTH_MODE=dev
export POTATY_DEV_AUTH=true
export POTATY_DEV_TOKEN="$(node -e 'console.log(require("node:crypto").randomBytes(32).toString("hex"))')"
export POTATY_DEV_PROJECT_ID=00000000-0000-0000-0000-000000000010
export POTATY_CREDENTIAL_KMS_KEY="$(node -e 'console.log(require("node:crypto").randomBytes(32).toString("hex"))')"
./gradlew :backend:run
```

Explicit development auth seeds the local workspace, user, membership, and project. Restarting the backend clears them with the rest of the H2 data.

<details>
<summary>PowerShell equivalent</summary>

```powershell
$env:POTATY_PORT = "8090"
$env:POTATY_AUTH_MODE = "dev"
$env:POTATY_DEV_AUTH = "true"
$env:POTATY_DEV_TOKEN = node -e "console.log(require('node:crypto').randomBytes(32).toString('hex'))"
$env:POTATY_DEV_PROJECT_ID = "00000000-0000-0000-0000-000000000010"
$env:POTATY_CREDENTIAL_KMS_KEY = node -e "console.log(require('node:crypto').randomBytes(32).toString('hex'))"
.\gradlew.bat :backend:run
```

</details>

### 3. Start the browser Studio

In a second terminal:

```bash
./gradlew browserDevelopmentRun --continuous --no-parallel
```

Windows contributors should run `.\gradlew.bat browserDevelopmentRun --continuous --no-parallel`.

Open the URL printed by Gradle, then enter these values under **Runtime settings**:

- **API origin:** `http://localhost:8090`
- **Access token:** the generated `POTATY_DEV_TOKEN`
- **Project ID:** `00000000-0000-0000-0000-000000000010`

The token is deliberately not restored after a reload. Enter it again when the page returns to **Connection required**.

<details>
<summary>Run PostgreSQL and the backend with Docker Compose</summary>

```bash
cp .env.example .env
# Fill POTATY_DB_PASSWORD, POTATY_DEV_TOKEN, and POTATY_CREDENTIAL_KMS_KEY.
docker compose config
docker compose up --build
```

Compose binds the API to localhost and keeps PostgreSQL on the internal network. This is local infrastructure, not a production deployment. See [Deployment](docs/DEPLOYMENT.md) before changing the network boundary or using real data.

</details>

## Current capabilities

### Generation Studio

- Prompt, pasted text, text-transcript, public GitHub, and optionally configured private GitHub App inputs.
- Deterministic entity/relation extraction with optional OpenAI enrichment for sparse prose.
- Versioned, renderer-independent Diagram IR with source snapshots and validation reports.
- ASCII and Mermaid views, evidence inspector, warnings, copy, download, zoom, and cancellation/retry states.
- Native `.potaty` editor files with read-compatible import for legacy `.mono` files.

### Backend and operator foundations

- Ktor API with explicit development auth or HS256 JWT validation.
- H2 for local development/tests and Flyway-managed PostgreSQL/pgvector infrastructure.
- Tenant-scoped repositories, job leases and fencing, atomic quota/cost admission, provider adapters, metrics, retention foundations, and audit events.
- Owner-only API recovery for genuinely uncertain external provider spend. The operator console remains roadmap work.

### Safety boundaries

- Bounded request, transcript, GitHub file, provider-output, and control-field shapes.
- Secret redaction, terminal/bidirectional control removal, strict GitHub/origin parsing, and stable source-free error messages.
- Server-side provider credential resolution. Credentials do not enter Diagram IR, generated diagrams, browser storage, or HTTP source DTOs.
- Request-bound source and job idempotency, atomic evidence persistence, and durable checkpoint recovery around billable transcription calls.

## Architecture

```text
app/                       Browser composition and Studio controller
backend/                   Ktor API, jobs, persistence, providers, GitHub and transcription
libs/                      Manual editor and reusable drawing primitives
shared/diagram-ir/         Canonical source-grounded Diagram IR
shared/layout-engine/      Deterministic diagram layout
shared/render-core/        IR-to-shape mapping and styles
shared/renderer-ascii/     Whitespace-preserving ASCII renderer
shared/renderer-codegen/   Mermaid, D2, PlantUML, Graphviz and Markdown compilers
shared/workbench-client/   Typed browser client, polling and source orchestration
src/                       Browser entry point, resources, fonts and styles
docs/                      Deployment, testing, assurance reviews and roadmap
```

Diagram IR is the renderer-independent source of truth. Renderers cannot silently discard evidence or redefine the graph. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for module responsibilities, generation stages, trust boundaries, and persistence modes.

## Configuration

The backend reads environment variables. The JVM does not automatically load `.env`; Docker Compose does.

| Variable | Purpose | Local default or requirement |
|---|---|---|
| `POTATY_ENV` | `development`, `test`, or fail-closed `production` profile | `development` |
| `POTATY_PORT` | Backend listen port | `8080` |
| `POTATY_DB_MODE` | `h2` or `postgres` | `h2` |
| `POTATY_AUTH_MODE` | Explicit `dev` or `jwt` bearer validation | JWT unless development auth is explicitly selected |
| `POTATY_DEV_TOKEN` / `POTATY_DEV_PROJECT_ID` | Local auth and seeded project | Required in development auth mode |
| `POTATY_JWT_ISSUER` / `POTATY_JWT_AUDIENCE` / `POTATY_JWT_SECRET` | Self-hosted HS256 token validation | Required in JWT mode |
| `POTATY_CORS_ORIGINS` | Exact browser origins allowed to call the API | `http://localhost:8080` |
| `POTATY_CREDENTIAL_KMS_KEY` | Local AES/GCM envelope-key material | Development implementation only, not production KMS |
| `POTATY_WORKSPACE_MONTHLY_COST_CAP_USD` | Atomic per-workspace provider-spend cap | `50`; non-positive disables the cap |
| `OPENAI_API_KEY` | Optional sparse-text enrichment and deployment transcription credential | Disabled when blank |
| `GITHUB_APP_ID` / `GITHUB_APP_PRIVATE_KEY` | Optional private-repository App identity | Disabled when blank; the full OAuth/state configuration is all-or-nothing |

The complete Compose surface is documented in [.env.example](.env.example). Production requirements and every GitHub App variable are in [Deployment](docs/DEPLOYMENT.md).

## Build and verification

Run the complete local gate before opening a pull request:

```bash
npm ci
npm audit --audit-level=moderate
./gradlew ktlint --no-daemon --no-parallel
./gradlew test --no-daemon --no-parallel
./gradlew browserProductionWebpack :backend:installDist --no-daemon --no-parallel
```

Use `gradlew.bat` on Windows. The release evidence was refreshed on 2026-07-23
(manual browser QA remains the 2026-07-17 production-bundle pass):

| Gate | Result |
|---|---|
| Kotlin/JVM and Kotlin/JS tests | 95 suites, 518 tests, 0 failures, 0 errors, 1 conditional local PostgreSQL skip |
| npm audit | 0 moderate-or-higher findings |
| Production browser bundle | 302,568 bytes gzip, below the enforced 327,680-byte ceiling |
| Backend distribution | 79 files, 32,732,516 bytes, installed successfully |
| Manual production-browser QA | Prompt, transcript, and public GitHub flows passed in Chromium at desktop and 390 px mobile |
| Local Docker evidence | Not run on the review machine; Compose interpolation, container build, PostgreSQL service, and health smoke are configured in CI |

This is a dated verification snapshot, not a substitute for running the gate on the exact commit you plan to publish. See [Testing](docs/TESTING.md) for targeted suites, CI coverage, scanner adjudication, and known gaps.

## Production status

Potaty's code and UX are ready for an honest open-source preview. A public hosted multi-tenant service is not yet approved.

| Suitable now | Still blocks hosted production |
|---|---|
| Local H2 development | OIDC/JWKS identity, onboarding, rotation, logout, and durable revocation |
| Controlled self-hosted evaluation | KMS/HSM-backed credential encryption and audited decrypt policy |
| Prompt, text-transcript, and public GitHub browser flows | Distributed body, rate, concurrency, spend, and queue backpressure controls |
| Deterministic IR/layout/ASCII and manual editing | Broad PostgreSQL queue/upgrade/restore/rollback evidence |
| Hardened local Compose and CI container smoke workflow | Retention scheduling, export/deletion propagation, operator console, and privacy operations |
| Chromium keyboard, responsive, storage, console, and flow QA | Automated Chromium plus non-Chromium WCAG, visual regression, staging canary, and DR drills |

The full evidence-backed decision is in the [production readiness review](docs/reviews/PRODUCTION_READINESS_REVIEW.md).

## Roadmap

| Milestone | Focus | Exit signal |
|---|---|---|
| `0.1.x` preview | Publishable repository, deterministic core, honest self-hosting docs | Exact tagged commit passes GitHub CI, notices/licences/secrets are clean, preview limits are prominent |
| `0.2.x` reliability | PostgreSQL breadth, automated browser/accessibility, operator experience, observability | Crash, replay, restore, and cross-browser gates run continuously |
| `0.3.x` product depth | Incremental repository indexing, end-user audio, evidence-preserving edits and version diff | New flows preserve tenant, evidence, determinism, cost, consent, and recovery contracts |
| `1.0` hosted | Identity, KMS, privacy, abuse prevention, and deployment operations | Every P0 gate has production-topology evidence plus incident and restore drills |

Milestones are outcomes, not dates. Read the [risk-ordered roadmap](docs/ROADMAP.md) for acceptance criteria and P1–P3 work.

## Documentation

| Audience | Start here |
|---|---|
| Users and self-hosters | [Deployment](docs/DEPLOYMENT.md) · [Troubleshooting](docs/TROUBLESHOOTING.md) · [Privacy](docs/PRIVACY.md) |
| Contributors | [Contributing](CONTRIBUTING.md) · [Architecture](docs/ARCHITECTURE.md) · [Design system](DESIGN.md) · [Testing](docs/TESTING.md) |
| Maintainers | [Releasing](docs/RELEASING.md) · [Repository settings](docs/REPOSITORY_SETTINGS.md) · [Asset provenance](docs/assets/README.md) · [Changelog](CHANGELOG.md) |
| Security and governance | [Security policy](SECURITY.md) · [Code of Conduct](CODE_OF_CONDUCT.md) · [Third-party notices](THIRD_PARTY_NOTICES.md) |

<details>
<summary>Assurance and review records</summary>

- [Production readiness review](docs/reviews/PRODUCTION_READINESS_REVIEW.md)
- [Code review](docs/reviews/CODE_REVIEW.md)
- [Adversarial code review](docs/reviews/ADVERSARIAL_CODE_REVIEW.md)
- [Product flow review](docs/reviews/FLOW_REVIEW.md)
- [Adversarial flow review](docs/reviews/ADVERSARIAL_FLOW_REVIEW.md)
- [Browser UI/UX QA](docs/reviews/UI_UX_QA.md)
- [Detailed browser QA report](docs/reviews/BROWSER_QA_REPORT.md)

</details>

## Contributing

Bug reports, documentation fixes, tests, and focused feature contributions are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) first, use synthetic fixtures, and open an issue before a large architecture, dependency, schema, or UI change.

Security reports must follow the private process in [SECURITY.md](SECURITY.md), not a public issue. Never attach a real token, private repository, customer transcript, or production log.

## Attribution and licence

Potaty is licensed under the [Apache License 2.0](LICENSE).

The manual ASCII editor foundation is derived from [MonoSketch](https://github.com/tuanchauict/MonoSketch), also under Apache-2.0. Potaty retains the upstream source notices and records the relationship in [NOTICE](NOTICE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

JetBrains Mono, Pretendard, and build-supplied D2Coding remain under the SIL Open Font License 1.1. Samsung Sharp Sans is proprietary and is **not** distributed in this repository. Potaty prefers it only when a licensed local or deployment-provided copy exists, then falls back to bundled Pretendard and system fonts. See the [font deployment note](src/main/resources/fonts/samsung-sharp-sans/README.md).
