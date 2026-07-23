# Security Policy

Potaty handles source code, transcripts, API credentials, and generated artifacts. Please report security problems privately so maintainers can investigate before details become public.

## Supported versions

Potaty has not published a stable release yet. Security fixes target the latest commit on `main`. Once versioned releases begin, this table will list supported release lines.

## Report a vulnerability

Use GitHub's private vulnerability-reporting flow:

<https://github.com/potatohoney-p/potaty/security/advisories/new>

If that flow is unavailable, contact the repository owner privately through GitHub. Do not open a public issue for an exploitable vulnerability.

Include, when possible:

- affected commit or version;
- affected endpoint, module, or deployment mode;
- impact and required attacker access;
- minimal reproduction steps or a proof of concept;
- whether credentials or personal data may have been exposed;
- a suggested fix, if you have one.

Do not send real customer data, production tokens, private repository contents, or destructive payloads. Use synthetic fixtures.

## Response process

Maintainers will acknowledge a report, reproduce it, assess severity, prepare tests and a fix, and coordinate disclosure. Timing depends on impact and maintainer availability; reporters will receive updates through the private advisory.

## Security boundaries

- Imported source is data and must never be executed.
- Browser bearer tokens are memory-only and are cleared by reload/navigation. Browser storage may
  contain non-secret runtime settings plus SHA-256 request digests and opaque idempotency keys,
  never raw source text or credentials. Development bearer-token auth is local-only and is not
  suitable for a public deployment.
- JWT mode validates HS256 issuer, audience, signature, tenant claims, and token lifetime, then rechecks the claimed user role against active database membership on every resolution. It does not implement a public login flow, asymmetric OIDC/JWKS validation, key rotation, or durable cross-instance revocation; production operators must provide those identity controls.
- The current envelope credential store is a development AES/GCM implementation. Production deployments require a real KMS-backed implementation and startup enforcement.
- GitHub App installation IDs are verified and bound to the authenticated workspace. Private
  repository production use still requires live install/suspend/uninstall/token-expiry lifecycle
  tests and durable webhook replay protection.
- Text, transcript, and GitHub source mutations require an idempotency key bound to a stored
  request hash. Source, version, and evidence chunks commit atomically; an exact GitHub replay is
  served before any installation credential or remote API is reopened. A database-clocked,
  token-fenced source-ingestion claim is acquired before GitHub credential or network work;
  malformed successful tree/blob payloads fail closed.
- Audio uploads are byte-bounded before deserialization, and successful provider output crosses a
  durable checkpoint before source ingestion. Result/checkpoint replay does not reopen a rotated
  credential. Only uncertain, checkpoint-free attempts can enter owner reconciliation, where the
  decision, explicit provider-receipt amount, and secret-redacted reason are audited.
- PostgreSQL/pgvector, provider calls, backups, TLS, and operator access controls are the deployer's responsibility.
- Source text may be stored for evidence and may be sent to configured external providers. Operators must publish an accurate privacy and retention policy.

See [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) for the deployment gate,
[docs/reviews/PRODUCTION_READINESS_REVIEW.md](docs/reviews/PRODUCTION_READINESS_REVIEW.md) for the
latest review decision, [docs/ROADMAP.md](docs/ROADMAP.md) for closure criteria, and
[docs/PRIVACY.md](docs/PRIVACY.md) for the current data lifecycle and operator obligations.

## Dependency advisory posture

The latest reviewed runtime graph contains 108 Maven coordinates. Security alignment removed 37
reported findings across PostgreSQL JDBC, Ktor, Netty, Logback, and Jackson. The resolved runtime
now uses `jackson-databind` 2.18.9, the first fixed 2.18.x release identified by
[`GHSA-5jmj-h7xm-6q6v`](https://github.com/advisories/GHSA-5jmj-h7xm-6q6v). Gradle
`dependencyInsight` confirms that older transitive Jackson versions are evicted.

At the 2026-07-17 review, OSV's API still returned that advisory for 2.18.9 even though its own
enumerated vulnerable-version list omitted 2.18.9 and the upstream advisory marks versions before
2.18.9 as affected. This is recorded as scanner metadata lag, not silently suppressed. Maintainers
must continue to compare scanner output with the authoritative advisory and resolved dependency
graph on every update; a future range correction must not be mistaken for a new regression.

## Secrets accidentally committed

Rotate or revoke the credential first. Removing it from the current file is not sufficient because Git history and caches may retain it. After rotation, notify maintainers privately so history, CI logs, packages, and images can be assessed.
