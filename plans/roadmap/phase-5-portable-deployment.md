# Phase 5 — Portable Containers and Centralized Object Storage

## Goal

Turn existing container and Compose assets into reproducible local/shared deployments with rehearsed recovery.

## Increment P5-I1 — Image hardening and configuration contracts

MVP decision (2026-09-05): all Phase 5 increments are deferred to [`docs/technical-debt/004-post-mvp-roadmap-work.md`](../../docs/technical-debt/004-post-mvp-roadmap-work.md) until a concrete deployment target is selected. Existing deployment assets and safeguards remain intact.

| Field                   | Value                                                     |
| ----------------------- | --------------------------------------------------------- |
| id                      | P5-I1                                                     |
| title                   | Image hardening and configuration contracts               |
| status                  | superseded                                                |
| priority                | high                                                      |
| depends_on              | [P1-I2]                                                   |
| blocks                  | [P5-I2, P6-I3]                                            |
| owned_modules           | [docker-compose, apps/core, apps/analyzer, apps/ingestor] |
| execution_mode          | autonomous                                                |
| requires_owner_decision | false                                                     |
| pr                      | null                                                      |
| last_verified_commit    | null                                                      |

Goal: harden Core, Analyzer, and Ingestor images and document startup configuration contracts.

Acceptance criteria: images use pinned runtime versions, non-root users where practical, health/readiness checks, graceful shutdown, no local credentials, and validated configuration with no unsafe production defaults.

Required tests/checks: image builds from clean checkout, config validation tests, container smoke checks, and relevant Nx targets.

Stop conditions: stop if deployment target architecture or required environment variables are owner decisions.

## Increment P5-I2 — Compose profiles and centralized object storage compatibility

| Field                   | Value                                                         |
| ----------------------- | ------------------------------------------------------------- |
| id                      | P5-I2                                                         |
| title                   | Compose profiles and centralized object storage compatibility |
| status                  | superseded                                                    |
| priority                | high                                                          |
| depends_on              | [P4-I2, P5-I1]                                                |
| blocks                  | [P5-I3, P6-I1]                                                |
| owned_modules           | [docker-compose, configs, libs/py-common]                     |
| execution_mode          | approval_required                                             |
| requires_owner_decision | true                                                          |
| pr                      | null                                                          |
| last_verified_commit    | null                                                          |

Goal: add local/cloud/backup/restore profiles and validate S3/R2-compatible centralized storage behavior.

Acceptance criteria: local profile starts from empty volumes, cloud profile uses external dependencies, object storage has environment isolation, and API compatibility is tested rather than assumed.

Required tests/checks: local Compose smoke test, shared-object-store compatibility test, and docs checks.

Stop conditions: owner must approve shared storage provider assumptions, credential source, and environment separation.

## Increment P5-I3 — Backup/restore rehearsal and immutable image publication

| Field                   | Value                                                    |
| ----------------------- | -------------------------------------------------------- |
| id                      | P5-I3                                                    |
| title                   | Backup/restore rehearsal and immutable image publication |
| status                  | superseded                                               |
| priority                | medium                                                   |
| depends_on              | [P5-I2]                                                  |
| blocks                  | [P6-I4]                                                  |
| owned_modules           | [docker-compose, .github, docs]                          |
| execution_mode          | manual                                                   |
| requires_owner_decision | true                                                     |
| pr                      | null                                                     |
| last_verified_commit    | null                                                     |

Goal: prove backup/restore and immutable-image publication.

Acceptance criteria: backup upload has checksum/metadata, restore verifies into an empty validation database where practical, application smoke tests pass, images are tagged immutably, and rollback rehearsal is documented.

Required tests/checks: backup upload, checksum verification, restore rehearsal, migration smoke, and image publication checks.

Stop conditions: requires credentials, registry access, or destructive environment operations.
