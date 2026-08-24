# Phase 3 — Dataset Manifests and Version Lineage

## Goal

Create a canonical, queryable definition of dataset identity, readiness, schema, statistics, and exact upstream lineage.

## Outcome

Dataset producers and consumers share deterministic JSON manifest identity, READY-last publication, and exact upstream-version lineage across Python and Java boundaries.

## Dataset Outputs

No new analytical dataset output. Phase 3 adds metadata for existing canonical dataset partitions; P3-I2 and P3-I3 migrate selected producers without changing logical dataset ownership.

## Metadata Outputs

Canonical immutable manifests are published at `_metadata/datasets/<dataset>/<partition_path>/versions/<dataVersion>.json`, followed by the mutable `_metadata/datasets/<dataset>/<partition_path>/READY.json` pointer only after data validation and immutable-manifest publication.

## Algorithm Feature Outputs

No direct algorithm feature output.

## Algorithms Unlocked

Exact dataset versions and upstream lineage make reproducible analysis, stale-input detection, and dependency-aware scheduling safer.

## Contract Impact

- Kafka/service-to-service protobuf: unchanged; business messages continue to use logical dataset references rather than physical object paths.
- Object-storage JSON manifest: changed by defining canonical manifest, identity, lineage, immutable-version, and READY-last semantics.
- Storage path/dataset ownership: metadata paths are added; logical analytical dataset ownership remains unchanged.
- Public Java/Python API: shared Python writer/reader abstractions are added in P3-I1, with a read-only Java compatibility boundary in P3-I3.
- Configuration/environment contract: shared logical path configuration may be extended without exposing credentials or physical paths in Kafka payloads.

## Repository Guidance Updates

[`AGENTS.md`](../../AGENTS.md), [`CLAUDE.md`](../../CLAUDE.md), [`.roo/rules`](../../.roo/rules), [`docs/README.md`](../../docs/README.md), and [`docs/data/data-lake.md`](../../docs/data/data-lake.md) must remain synchronized when manifest architecture, storage workflow, or development guidance changes. The P3-I1 verification repair only restores the configured pytest HTML reporter and does not change runtime architecture or guidance.

## Increment P3-I1 — Manifest models, identity rules, and py_common abstractions

| Field                   | Value                                                       |
| ----------------------- | ----------------------------------------------------------- |
| id                      | P3-I1                                                       |
| title                   | Manifest models, identity rules, and py_common abstractions |
| status                  | blocked                                                     |
| priority                | critical                                                    |
| depends_on              | [P1-I2]                                                     |
| blocks                  | [P3-I2, P4-I1, P6-I1, P9-I1]                                |
| owned_modules           | [libs/py-common, configs, docs/data]                        |
| execution_mode          | autonomous                                                  |
| requires_owner_decision | false                                                       |
| pr                      | https://github.com/tanlocit9/omni/pull/12                   |
| last_verified_commit    | fd23482d2bb764b145ad2a199d45553c85ea39e8                    |

Goal: define JSON `DatasetManifest`, `DatasetRef`, catalog pointer, identity/hash rules, and shared writer/reader abstractions in [`py_common`](../../libs/py-common/py_common).

Current verification state: the canonical JSON contract, deterministic lineage-inclusive identity, exact-byte physical identity, immutable version plus READY paths, shared fixtures, Python writer/reader, and Java read compatibility are present. Fresh local verification at branch head `a24edd2` on 2026-08-23 passed Nx graph construction, `nx run py-common:format -- --check`, `nx run py-common:lint`, `nx run py-common:test` (137 passed), `nx run py-common:build`, `nx run platform:test`, and `nx run platform:build`. The test run initially exposed a missing `pytest-html` development dependency required by the configured `--html` option; the dependency and lockfile were corrected. The Linux Nx graph bootstrap defect was also repaired by tracking `apps/core/gradlew` as executable and moving the defensive CI permission step before the first Nx invocation. [PR #12](https://github.com/tanlocit9/omni/pull/12) merged as `fd23482d2bb764b145ad2a199d45553c85ea39e8`, but both the [PR CI run](https://github.com/tanlocit9/omni/actions/runs/32634867746) and [merge-commit CI run](https://github.com/tanlocit9/omni/actions/runs/32635823560) failed at `Sync query service dependencies`, with all subsequent verification steps skipped. P3-I1 is blocked—not completed—because successful CI and current code-review-graph change/impact evidence are absent.

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

## Increment P3-I4 — Normalize Parquet date contracts and safe rewrite

| Field                   | Value                                                              |
| ----------------------- | ------------------------------------------------------------------ |
| id                      | P3-I4                                                              |
| title                   | Normalize Parquet date contracts and safe versioned rewrite        |
| status                  | verification_pending                                               |
| priority                | critical                                                           |
| depends_on              | [P1-I2]                                                            |
| blocks                  | [P9-I1, P9-I2, P10-I2]                                             |
| owned_modules           | [libs/py-common, apps/ingestor, apps/analyzer, apps/query-service] |
| execution_mode          | autonomous                                                         |
| requires_owner_decision | false                                                              |
| pr                      | https://github.com/tanlocit9/omni/pull/16                          |
| last_verified_commit    | 2ecb6baaddc056955aba6df5c113e4038faed2ca                           |

Goal: distinguish business dates (`date32`/DuckDB `DATE`) from UTC event
timestamps (`timestamp[us, UTC]`/DuckDB `TIMESTAMPTZ`) across EOD, Indicators,
Signals, Sector Wave, and Sector Transition while preserving semantic names.

Acceptance criteria: shared encoding and legacy decoding are authoritative;
manifest columns report canonical types; Query Service normalizes legacy files;
cross-dataset joins work without string/timestamp drift; and backfill writes a
validated versioned object before immutable-manifest and READY publication. The
previous READY object is never overwritten.

Required tests/checks: schema encode/decode, legacy compatibility, analytical
joins, Sector Wave/Transition schema, manifest metadata, backfill idempotency and
failure safety, owning Python project lint/test/build, workspace formatter, and
green CI for the exact branch head. Detailed execution scope is in
[`../parquet-date-normalization-increment.md`](../parquet-date-normalization-increment.md).

Stop conditions: stop on wildcard/multi-object READY partitions until the owning
dataset supplies an explicit partition rewrite, or if a physical path ownership
change becomes necessary.

## Verification

Use only inspected Nx targets. P3-I1 requires `nx run py-common:lint`, `nx run py-common:test`, and `nx run py-common:build`; Java fixture compatibility requires `nx run platform:test`. Producer migrations additionally require their owning project targets and storage-backed failure-path tests. Record any unavailable graph, PR, commit, or CI evidence rather than inferring it from local success.

## Acceptance Criteria

Each increment must satisfy its scoped criteria above, preserve JSON manifests as the object-storage source of truth, publish READY last, retain logical dataset routing, pass its targeted and relevant Nx checks, and synchronize affected data, flow, service, and repository guidance. Completion additionally requires PR, verified commit, CI, graph impact/change review, and compatibility evidence required by the roadmap definition of done.
