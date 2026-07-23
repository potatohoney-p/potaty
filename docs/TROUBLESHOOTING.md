# Troubleshooting

## The Studio says “Connection required”

Open **Runtime settings** and provide all three required values:

- API URL, normally `http://localhost:8090`;
- the same bearer token used to start the backend;
- a valid project UUID.

Settings live only for the browser session. Opening a new tab/session may require entering them again.

## The browser cannot reach the API

Check backend health:

```bash
curl -i http://localhost:8090/health
```

Confirm the backend listens on 8090 and `POTATY_CORS_ORIGINS` contains the exact browser origin printed by Gradle. An HTTPS browser page cannot call a plaintext HTTP API without a same-origin TLS proxy.

## Requests return 401

The access token is missing, mistyped, expired, or rejected by the configured session store. For local development, restart the backend with `POTATY_AUTH_MODE=dev`, `POTATY_DEV_AUTH=true`, and the same `POTATY_DEV_TOKEN` entered in the Studio. In JWT mode, verify the HS256 signature, issuer, audience, UUID subject/workspace, role, token ID, and lifetime claims. Never solve this by enabling a shared development token on a public host.

## PostgreSQL requests fail with a tenant or foreign-key error

The schema requires matching workspace, user, membership, and project rows. Explicit development auth creates the deterministic local identity and project. JWT mode never seeds records; provision matching identities through a trusted onboarding workflow before using their tokens. Do not disable foreign keys or reuse another workspace's project ID.

## `docker compose config` says a variable is required

Copy `.env.example` to `.env` and fill these blank values:

- `POTATY_DB_PASSWORD`
- `POTATY_CREDENTIAL_KMS_KEY`

Development mode also requires a non-default `POTATY_DEV_TOKEN`; JWT mode requires issuer, audience, and a 32-byte-or-stronger random HMAC secret. Fail-closed startup is intentional.

## Gradle reports a corrupted Kotlin incremental cache

Stop active builds first. Then run:

```bash
./gradlew --stop
./gradlew clean test --no-daemon --no-parallel
```

On Windows use `.\gradlew.bat`. Do not delete source files or reset the worktree to fix a cache problem.

## Browser tests cannot find Chrome

Install Chrome/Chromium and make its binary discoverable to Karma. In minimal CI/container environments, use a runner image that includes Chrome or set the appropriate Chrome binary environment variable.

## Tailwind or npm output differs between machines

Use `npm ci`, not `npm install`, and commit the resulting lockfile only when intentionally updating dependencies. Check:

```bash
npm audit --audit-level=moderate
```

## GitHub indexing fails

- Public repositories must be valid `github.com/owner/repository` URLs.
- Anonymous GitHub API requests are rate-limited.
- Private repositories require a configured GitHub App and an installation authorized for that workspace.
- Large/binary/ignored files are skipped by design.
- Never paste an installation token into the browser.

## Generation succeeds but the diagram is sparse

Use explicit relationships such as `Web App -> API` or provide a focused source. Optional OpenAI enrichment runs only when the backend is configured with a key and the deterministic graph is sparse. Provider failure falls back to deterministic extraction.

## A transcription is “processing” or “pending accounting reconciliation”

- Retry a processing attempt with the exact original body and `Idempotency-Key`. After the
  ten-minute lease expires, a durable success checkpoint resumes without another provider call or
  credential lookup.
- Do not change the body under the same key; Potaty returns an idempotency conflict.
- A checkpoint-free uncertain provider attempt appears only to a workspace owner at
  `GET /api/v1/admin/external-spend/pending` after its lease expires. Verify the provider receipt,
  then use the audited reconcile endpoint described in [Deployment](DEPLOYMENT.md).
- Do not retry an uncertain attempt with a new key before reconciliation. The provider may already
  have billed the first request.
- If a stored result or checkpoint is reported invalid, preserve the database row and correlation
  logs for investigation; do not clear accounting fields manually.

## Reporting a problem

Use the appropriate GitHub issue template and include OS, JDK, Node, browser, exact command, and the first actionable error. Redact tokens, source content, repository names, and personal data. Security issues must follow [SECURITY.md](../SECURITY.md).
