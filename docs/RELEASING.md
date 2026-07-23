# Releasing

Potaty does not yet have an automated public release pipeline or a stable release line. This checklist defines the minimum bar for the first tagged release.

1. Work from a release branch through a reviewed pull request. Do not release a dirty `main` worktree.
2. Move relevant entries from the [Unreleased changelog](../CHANGELOG.md) into a versioned section, then update README, deployment/security docs, and third-party notices.
3. Choose a semantic version and tag format (`vX.Y.Z`) once, then use it consistently.
4. Run the complete CI gate, PostgreSQL/pgvector integration suite, browser E2E, accessibility and visual regression checks.
5. Build the frontend and backend from a clean checkout with locked dependencies.
6. Generate an SBOM for browser assets and the container; scan source, dependencies, secrets, licences, and the image.
7. Pin release base images by digest and publish an immutable image tag plus provenance/signature.
8. Exercise migrations, fresh install, upgrade, backup, restore, rollback, health/readiness, and a generation canary.
9. Verify no development token, local key, private fixture, proprietary font, or customer data is
   present, and confirm the release contains `LICENSE`, `NOTICE`, third-party licences, and all
   retained MonoSketch source notices.
10. Publish release notes that distinguish stable features, experimental integrations, breaking changes, and known limitations.

Repository administrators must verify that `main` requires pull requests and the `Test and build`
and `Validate container` checks, blocks force pushes and deletion, and keeps secret scanning plus
push protection enabled. Add an approving-review requirement once a second active maintainer can
provide independent review without blocking routine maintenance.

Until those checks exist in automation, releases should be described as development snapshots rather than production-ready builds.
