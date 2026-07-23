# Privacy and data handling

Potaty is self-hostable software, not a hosted privacy promise. This document describes the current data flow so an operator can write an accurate, jurisdiction-specific privacy notice before accepting real source code or transcripts.

## Data the system can process

- prompts and pasted text;
- transcript file names and UTF-8 contents, which may contain voices converted to text, names, contact details, or sensitive conversations;
- GitHub repository owner/name, ref, paths, selected text-file contents, commit metadata, and optional App installation identifiers;
- generated entities, relations, diagrams, evidence spans, confidence, warnings, job events, and versions;
- workspace, user, project, role, usage, and cost metadata;
- provider and GitHub credentials supplied to the backend.

Do not infer that a diagram is anonymous. Labels, evidence, paths, speakers, timestamps, and repository metadata can identify people or confidential projects.

## Current processing path

1. The browser reads a selected transcript locally, then sends its text to the configured Potaty
   API. The bearer token stays only in page memory and is cleared on reload. `sessionStorage`
   contains non-secret runtime settings and, for unknown-outcome recovery, only SHA-256 request
   digests plus opaque idempotency keys; it does not contain raw source text or credentials.
2. The backend normalizes and safety-scans content, then stores source records, versions, and chunks so generated claims can retain evidence.
3. Public GitHub import requests repository metadata and selected text files from GitHub. Imported code is stored as data and is never executed.
4. Deterministic extraction runs locally in the backend. When optional provider enrichment or transcription is configured and invoked, relevant source data is sent to that provider.
5. Diagram IR, code renderings, job state, usage records, and evidence are stored in H2 or PostgreSQL. Application and infrastructure logs may add operational metadata.

## Retention and deletion

The codebase contains retention and soft-delete foundations, but it does not yet provide a complete end-user export/deletion workflow, verified deletion propagation, or a production retention scheduler. Database backups can retain source text after an application record is deleted.

Before production use, define and test:

- a default retention period for source chunks, transcripts, repository snapshots, diagrams, job events, usage data, logs, and backups;
- user-visible export and deletion workflows with authorization and audit records;
- deletion propagation to provider records, caches, replicas, search indexes, and backups where required;
- legal holds and the narrow roles permitted to apply them;
- account/workspace closure and GitHub App uninstall behavior.

## Operator responsibilities

- Establish a lawful basis and provide notice before processing personal or confidential data.
- Confirm recording, transcription, workplace-monitoring, and cross-border transfer rules for every relevant jurisdiction. A participant's consent may be required before a call is recorded or uploaded.
- Disclose GitHub, OpenAI, Anthropic, infrastructure, monitoring, and support providers actually used by the deployment, with their purposes and regions.
- Minimize repository scopes and imported paths; do not index an entire private repository when a smaller source is sufficient.
- Apply workspace authorization, least-privilege operator access, encryption, TLS, audit logging, incident response, and tested backup deletion.
- Never use customer source or transcripts as public examples, evaluation fixtures, or model-training data without explicit authorization.

## Development and issue reports

Use synthetic fixtures. Redact access tokens, repository names, file paths, speakers, email addresses, and unique business facts before sharing logs or screenshots in an issue. Security incidents belong in the private process in [SECURITY.md](../SECURITY.md).

## Hosted-service gate

Before Potaty is offered as a hosted service, publish an operator-specific privacy notice and data-processing terms, complete a data inventory and threat model, configure retention/deletion, document subprocessors, and verify access/export/deletion requests end to end. The repository's Apache-2.0 licence and this document are not legal advice or a substitute for that work.
