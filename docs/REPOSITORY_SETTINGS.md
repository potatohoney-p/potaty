# GitHub repository settings

Files in the repository cannot enforce every GitHub control. A repository administrator should review this checklist before accepting outside contributions or publishing a release.

## Default branch and pull requests

Protect `main` with a ruleset that:

- requires pull requests and at least one approving review;
- dismisses stale approvals when the diff changes;
- requires all review conversations to be resolved;
- requires the `Test and build` and `Validate container` status checks from CI;
- blocks force pushes and branch deletion;
- applies to administrators, with a documented emergency bypass process;
- requires signed commits or vigilant mode if the maintainer workflow supports it;
- uses squash merge or another single documented history policy.

Do not require a CODEOWNERS review until a valid maintainer or team handle is committed in `.github/CODEOWNERS`. A broken owner entry can block every pull request.

## Actions

- Keep the default workflow token read-only. Grant write permissions only to a specific job that needs them.
- Require approval before workflows from first-time fork contributors run.
- Allow only GitHub-authored actions and explicitly approved third-party actions where practical.
- Keep action references pinned to full commit SHAs; Dependabot can update the pins.
- Store deployment credentials in a protected GitHub Environment, not repository variables or workflow files.
- Require environment reviewers for production and prevent self-review when the team is large enough.

## Security and dependency management

Enable:

- the dependency graph;
- Dependabot alerts and security updates;
- secret scanning and push protection;
- private vulnerability reporting;
- branch protection bypass notifications;
- CodeQL for Kotlin/Java and JavaScript once its build is validated against this repository.

Treat a secret-scanning alert as a rotation event. Closing an alert or deleting the current line does not invalidate an exposed credential.

## Issues and community

- Keep blank issues disabled so reports include reproducible, redacted context.
- Enable Discussions only if maintainers can moderate and answer them.
- Publish a private contact path for security and conduct reports.
- Review spam permissions before the first public announcement.

## Repository presentation

- Use the concise repository description: `Source-grounded ASCII diagrams from prompts, transcripts, and GitHub repositories.`
- Add focused topics such as `ascii-art`, `diagram`, `documentation`, `kotlin`, `kotlin-js`, `ktor`, `source-code-analysis`, and `self-hosted`; avoid topics that imply a hosted production service.
- Upload [`docs/assets/github-social-preview.png`](assets/github-social-preview.png) under **Settings → Social preview**. It is the checked-in 1280×640, solid-background render prepared for repository sharing.
- Confirm the README hero, pipeline diagram, QA screenshots, badge URLs, and relative documentation links render on the default branch after the first push.
- Keep the repository website field empty until a maintained HTTPS deployment exists. Do not link a local or development-auth environment.

## Releases

- Publish only from reviewed, protected commits after the checklist in [Releasing](RELEASING.md) passes.
- Use signed, annotated semantic-version tags and immutable release artifacts.
- Attach checksums, an SBOM, provenance, licence material, and accurate known limitations.
- Separate production deployment credentials from package or container publication credentials.

Revisit these settings after maintainer changes, a security incident, or a new deployment path. Record intentional exceptions in a public issue or architecture decision rather than relying on undocumented administrator state.
