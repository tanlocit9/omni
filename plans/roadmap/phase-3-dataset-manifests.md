# Phase 3 — Dataset Manifests and Version Lineage

## Goal

Create a canonical, queryable definition of dataset identity, readiness, schema, statistics, and exact upstream lineage.

## Increment P3-I1 — Manifest models, identity rules, and py_common abstractions

| Field                   | Value                                                       |
| ----------------------- | ----------------------------------------------------------- |
| id                      | P3-I1                                                       |
| title                   | Manifest models, identity rules, and py_common abstractions |
| status                  | verification_pending                                        |
| priority                | critical                                                    |
| depends_on              | [P1-I2]                                                     |
| blocks                  | [P3-I2, P4-I1, P6-I1, P9-I1]                                |
| owned_modules           | [libs/py-common, configs, docs/data]                        |
| execution_mode          | autonomous                                                  |
| requires_owner_decision | false                                                       |
| pr                      | null                                                        |
| last_verified_commit    | null                                                        |

Goal: define JSON `DatasetManifest`, `DatasetRef`, catalog pointer, identity/hash rules, and shared writer/reader abstractions in [`py_common`](../../libs/py-common/py_common).

Current verification state: the canonical JSON contract, deterministic lineage-inclusive identity, exact-byte physical identity, immutable version plus READY paths, shared fixtures, Python writer/reader, and Java read compatibility are present on `main`. Historical local Nx lint, test, and build evidence is recorded in [`execution-log.md`](execution-log.md), but fresh acceptance reconciliation and current Nx/CI evidence remain required before completion.

Acceptance criteria: manifests are immutable per `dataVersion`, READY pointer is published last, schema/data hashes are deterministic, inputs record exact upstream versions, and no Kafka message uses physical S3 paths for routing.

Required tests/checks: manifest model validation, deterministic version tests, schema hash tests, and py-common Nx test/lint/build targets.

Stop conditions: stop if object path ownership or JSON-vs-Proto persistence boundary is disputed.

## Increment P3-I2 — First Ingestor READY manifest and failure safety

| Field                   | Value                                            |
| ----------------------- | ------------------------------------------------ |
| id                      | P3-I2                                            |
| title                   | First Ingestor READY manifest and failure safety |
| status                  | pending                                          |
| priority                | critical                                         |
| depends_on              | [P3-I1, P2-I2]                                   |
| blocks                  | [P3-I3, P4-I1]                                   |
| owned_modules           | [apps/ingestor, libs/py-common, configs]         |
| execution_mode          | autonomous                                       |
| requires_owner_decision | false                                            |
| pr                      | null                                             |
| last_verified_commit    | null                                             |

Goal: migrate one Ingestor EOD dataset to safe staged write, validation, immutable manifest, and READY publication.

Verification state: the Ingestor EOD handler and its real-MinIO integration test are present on `main`. Historical local evidence records data read-back, deterministic `dataVersion` recomputation, immutable/READY byte equality, and preservation of the previous READY pointer after an injected replacement failure. Canonical status remains `pending` because P3-I1 and P2-I2 are not completed; fresh Nx/CI and increment acceptance evidence is still required.

Acceptance criteria: failed rewrites do not replace current READY, row/schema/partition validation runs before READY publication, and manifest fixtures are committed.

Required tests/checks: MinIO/S3-compatible failure-path test, last-READY preservation test, Ingestor tests, and py-common tests.

Stop conditions: stop if the first dataset family or object-store test backend is unclear.

## Increment P3-I3 — Java read-only manifest client and first Analyzer migration

| Field                   | Value                                                       |
| ----------------------- | ----------------------------------------------------------- |
| id                      | P3-I3                                                       |
| title                   | Java read-only manifest client and first Analyzer migration |
| status                  | pending                                                     |
| priority                | high                                                        |
| depends_on              | [P3-I2, P1-I3]                                              |
| blocks                  | [P4-I1, P6-I1]                                              |
| owned_modules           | [apps/core, apps/analyzer, libs/py-common]                  |
| execution_mode          | autonomous                                                  |
| requires_owner_decision | false                                                       |
| pr                      | null                                                        |
| last_verified_commit    | null                                                        |

Goal: let Platform resolve/read manifests and migrate one Analyzer dataset to exact lineage.

Acceptance criteria: Java client distinguishes not found/not ready/unsupported schema/storage errors, Analyzer publishes READY manifests with exact input versions, and Java validates Python-produced fixtures.

Required tests/checks: Java client fixture tests, Analyzer lineage tests, schema evolution tests, and affected Nx checks.

Stop conditions: stop if Java read-only scope expands into duplicate write logic.
