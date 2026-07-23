# Potaty contributor instructions

## Product contract

Potaty turns untrusted source material into source-grounded diagrams. Preserve evidence, confidence, tenant boundaries, and deterministic rendering in every change. Never execute imported repository code.

## Design system

Always read `DESIGN.md` before making visual or interaction changes. Typography, colors, spacing, responsive behavior, motion, and accessibility requirements are defined there. Do not add generic gradients, glassmorphism, decorative blobs, or uniform card grids.

## Engineering checks

- Keep the canonical Diagram IR renderer-independent and evidence-linked.
- Treat prompts, transcripts, repository files, SVG, and Mermaid as untrusted input.
- Keep credentials out of source, logs, persistent browser storage, and generated diagrams.
- Add or update tests for behavior, security boundaries, and output determinism.
- Run `npm ci`, `npm audit --audit-level=moderate`, `./gradlew ktlint`, the test suite, the production browser build, and `:backend:installDist` before opening a pull request; [CONTRIBUTING.md](CONTRIBUTING.md) has the exact commands.
- On Windows, use `gradlew.bat`; the Tailwind task selects `npm.cmd` automatically.

## Open-source hygiene

- Do not add proprietary font binaries, trademarked logo packs, private fixtures, secrets, or customer data.
- New dependencies require a clear reason, compatible licence, and lockfile update.
- User-facing features need documentation, configuration examples, failure states, and keyboard access.
