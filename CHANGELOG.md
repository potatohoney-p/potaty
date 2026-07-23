# Changelog

Notable Potaty changes are documented here. The project follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and will use [Semantic Versioning](https://semver.org/) once the first public version is tagged.

## [Unreleased]

### Added

- Generation Studio for prompts, UTF-8 transcript files, and public GitHub repository URLs.
- Optional GitHub App entry point for private repository integrations.
- Source-grounded Diagram IR with evidence, confidence, validation warnings, and immutable versions.
- Deterministic extraction and layout, optional OpenAI enrichment, ASCII output, Mermaid output, and insertion into the manual editor.
- Kotlin/JVM Ktor backend with H2 development mode, PostgreSQL/pgvector migrations, job processing, provider adapters, usage foundations, and admin endpoints.
- Runtime connection settings, explicit loading/error states, copy/download controls, and a responsive accessibility-focused Studio interface.
- OFL-licensed Pretendard 1.3.9 variable webfont, shipped as dynamic unicode-range subsets, as the bundled Latin/Hangul fallback when licensed Samsung Sharp Sans is unavailable.
- Contributor, security, architecture, deployment, testing, troubleshooting, release, conduct, and third-party licence documentation.
- GitHub-first README artwork, real production-bundle QA screenshots, accessible asset provenance, and an upload-ready repository social preview.
- Hardened local container assets, CI verification, dependency updates, and repository maintenance templates.
- Risk-ordered hosted-production roadmap plus consolidated security, testing, and browser QA
  documentation.
- Restored MonoSketch copyright notices throughout derived editor modules and added explicit
  upstream attribution to `NOTICE` and third-party documentation.

### Security

- Imported repositories are treated as untrusted text and are never executed.
- Browser bearer tokens are memory-only and cleared on reload; reload-safe unknown-outcome retry
  state stores only SHA-256 request digests and opaque idempotency keys.
- Startup fails closed unless explicit development auth or a configured HS256 JWT validator is selected; local identity records are seeded only in development mode.
- JWT sessions enforce issuer, audience, bounded lifetime, workspace/user claims, and matching active database membership on every resolution.
- GitHub input/origin parsing, request controls, idempotency conflict handling, LLM-output bounds,
  terminal/bidi normalization, worker lease fencing, secret-safe failure reporting, and
  foreign-key-safe tenant retention received negative-path regression coverage.
- PostgreSQL JDBC was upgraded from 42.6.0 to 42.7.11 to close
  `GHSA-24rp-q3w6-vc56` and `GHSA-98qh-xjc8-98pq`.
- Ktor 2.3.13, Netty 4.1.135.Final, Logback 1.5.34, and Jackson 2.18.9 are aligned after a
  complete 108-coordinate runtime OSV review; the remaining OSV Jackson hit is documented as
  advisory-range metadata lag because 2.18.9 is the upstream fixed line.
- Actual LLM token usage (including structured repair attempts), model provenance, job creator,
  terminal job reasons, and requested rendering formats now survive the asynchronous job boundary.
- Generated diagram, immutable version, and requested renderings now commit atomically and are
  bound uniquely to their generation job, so reclaimed/retried workers return one stable artifact.
- Audio transcription now verifies project ownership before any credential or provider work,
  accepts only a credential id, resolves an active tenant-owned OpenAI API key on the server, and
  preserves sanitized speaker labels instead of restoring untrusted provider metadata.
- A transcription reservation is durably fenced before the billable provider call; usage insertion
  and reservation release settle atomically. If accounting persistence fails, the reservation
  remains counted beyond its normal TTL for explicit reconciliation instead of losing spend.
- Transcription request bytes are capped before JSON deserialization even without
  `Content-Length`; every attempt requires a request-bound `Idempotency-Key`, and successful
  provider output is checkpointed before transcript persistence.
- Checkpoint recovery now commits source, version, chunks, usage, stored HTTP result, and quota
  release in one transaction. Retries resume without another provider call or credential lookup,
  including after key rotation or revocation.
- The owner-only external-spend queue exposes only uncertain, expired-lease attempts. Durable
  success checkpoints cannot be charged or released through that queue; reconciliation requires
  confirmation, a redacted audit reason, and a PostgreSQL-safe monetary value.
- JSONB idempotency comparisons now preserve PostgreSQL numeric meaning across decimal/exponent
  normalization, and reconciliation charges respect the exact `numeric(12,6)` upper bound.
- Prompt/text-transcript and GitHub ingestion now require request-bound idempotency keys and commit
  source, version, and all evidence chunks in one transaction. Exact retries return the original
  artifact; GitHub retries do so before reopening an installation token or remote API, while
  changed requests receive `409`.
- GitHub indexing now acquires a database-clocked, token-fenced claim before credential or outbound
  work. Lease takeover/finalization uses PostgreSQL server time, malformed successful tree/blob
  payloads fail closed, and a real pgvector PostgreSQL/Flyway CI test covers claim constraints,
  concurrent takeover, completion/replay, tenant FKs, and retention isolation.
- Browser cancellation failures and cancellation/success races retain the original job key; an
  exact retry resumes or loads the authoritative job instead of creating duplicate provider spend.
- Browser fetches now have an abortable whole-response deadline, success without usable output
  remains replayable, and stale generation owners cannot cancel a newly adopted retry key.
- The same-tab retry journal never evicts unresolved attempts and fails closed on capacity,
  corruption, or storage errors; candidate memory state commits only after durable storage.
- Reload/BFCache cleanup clears source controls, aborts active transcript reads, and generation-
  fences FileReader callbacks so raw source text cannot reappear after a privacy scrub.
- Owner reconciliation no longer substitutes a reservation estimate when a charge amount is
  omitted: every `charge` decision requires the explicit provider-receipt amount.
- The complete GitHub App connection environment surface is documented and passed through Compose,
  including web base, App slug, OAuth callback/client credentials, public base, and signed state.
- Source-supplied prompt-fence markers, malformed-body reflection, and one-cell wide-glyph border
  overwrite received dedicated regression coverage.
- `needs_input` jobs now terminate browser polling immediately and present an actionable source-
  structure recovery message instead of timing out after the full polling window.
- Containers use localhost binding, a non-root backend user, a read-only filesystem, dropped capabilities, and required local secrets.
- Samsung Sharp Sans binaries are not redistributed without a valid licence; JetBrains Mono includes its SIL OFL 1.1 text.

### Known limitations

- The project has no stable public release yet.
- Hosted login/token issuance, durable multi-instance revocation, production KMS, audited tenant
  onboarding, distributed abuse controls, complete observability/privacy operations, PostgreSQL
  integration/restore coverage, automated cross-browser E2E/accessibility, and a production
  deployment remain release blockers.

[Unreleased]: https://github.com/potatohoney-p/potaty/commits/main
