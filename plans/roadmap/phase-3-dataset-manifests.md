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

Current verification state: the canonical JSON contract, deterministic lineage-inclusive identity, exact-byte physical identity, immutable version plus READY paths, shared fixtures, Python writer/reader, and Java read compatibility are present. On 2026-08-25, `nx run query-service:sync` reproduced the historically failing CI step successfully. Fresh py-common verification then passed `nx run py-common:sync`, `nx run py-common:format -- --check` (41 files), `nx run py-common:lint`, `nx run py-common:test` (144 passed with 8 existing FastAPI `on_event` deprecation warnings), and `nx run py-common:build`; three formatter-only line-wrap corrections were applied before the successful gate. Focused graph impact review identified consumers across Analyzer, Ingestor, Query Service, and date backfill, and post-edit `detect_changes` reported risk 0.40 with no affected execution flow. [PR #12](https://github.com/tanlocit9/omni/pull/12) remains merged as `fd23482d2bb764b145ad2a199d45553c85ea39e8`, but both the [PR CI run](https://github.com/tanlocit9/omni/actions/runs/32634867746) and [merge-commit CI run](https://github.com/tanlocit9/omni/actions/runs/32635823560) failed at `Sync query service dependencies`, with subsequent verification skipped. P3-I1 remains blocked—not completed—because the historical CI failure has no fresh successful CI replacement. Current local and graph evidence does not substitute for that missing canonical delivery evidence.

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
| status                  | completed                                                          |
| priority                | critical                                                           |
| depends_on              | [P1-I2]                                                            |
| blocks                  | [P9-I1, P9-I2, P10-I2]                                             |
| owned_modules           | [libs/py-common, apps/ingestor, apps/analyzer, apps/query-service] |
| execution_mode          | autonomous                                                         |
| requires_owner_decision | false                                                              |
| pr                      | https://github.com/tanlocit9/omni/pull/16                          |
| last_verified_commit    | ab2cc3cb0044c87d2b61a6736652c6fd4cfb2124                           |

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

Completion evidence: all recorded targeted tests and owning-project lint/build
checks pass; the workspace formatter and `git diff --check` pass; refreshed graph
change detection reports no affected execution flow; and
[CI run #154](https://github.com/tanlocit9/omni/actions/runs/32870691112)
passed for exact PR head `ab2cc3cb0044c87d2b61a6736652c6fd4cfb2124`.
The PR remains draft and unmerged for owner review.

Stop conditions: stop on wildcard/multi-object READY partitions until the owning
dataset supplies an explicit partition rewrite, or if a physical path ownership
change becomes necessary.

## Increment P3-I5 — Automatic EOD metadata reconciliation

| Field                   | Value                                                                 |
| ----------------------- | --------------------------------------------------------------------- |
| id                      | P3-I5                                                                 |
| title                   | Automatic EOD metadata reconciliation                                 |
| status                  | verification_pending                                                  |
| priority                | high                                                                  |
| depends_on              | [P3-I1, P7-I2]                                                        |
| blocks                  | []                                                                    |
| owned_modules           | [apps/core, apps/analyzer, libs/py-common, docs/data, docs/flows]     |
| execution_mode          | autonomous                                                            |
| requires_owner_decision | false                                                                 |
| pr                      | https://github.com/tanlocit9/omni/pull/16                            |
| last_verified_commit    | 392a0c244fb172c1b4a11426d71daeba8efc8be3                            |

Implementation scope supersedes the earlier manual-exact-partition proposal below:
the owner requested an automatic metadata job after observing accepted triggers
with no data. The verified cause was a missing Analyzer consumer for
`topic-sync-metadata`. The implemented worker reconciles every canonical EOD object
on the existing weekday 20:00 definition, and the same job remains manually
triggerable through Phase 7. It accepts legacy `UNIVERSAL` messages as EOD during
deployment transition. Derived datasets remain excluded because their exact input
lineage cannot be reconstructed safely from output bytes.

Implemented acceptance:

- [x] Platform keeps one stable `SYNC_METADATA` definition with weekday 20:00 cron.
- [x] Analyzer consumes `topic-sync-metadata` and emits terminal job status.
- [x] Only canonical, non-empty EOD Parquet objects can produce manifests.
- [x] Exact persisted bytes determine checksum and deterministic `dataVersion`.
- [x] Unchanged READY versions are not rewritten; catalog is published after manifests.
- [x] Zero valid partitions is ERROR; mixed corrupt/valid objects are PARTIAL_SUCCESS.
- [x] Errors and status metrics do not expose physical storage paths.
- [x] Parquet data is never rewritten and derived lineage is never inferred.
- [x] Python lint, 230 tests, and py-common/Analyzer builds pass locally.
- [ ] Platform test/build, canonical Nx formatting, and exact-head CI pass.

### Superseded manual-exact-partition proposal

Goal: let an authenticated operator rebuild canonical metadata for one exact
existing dataset partition without recomputing or rewriting its Parquet data and
without creating a scheduler bypass. V1 supports `eod` partitions whose exact
logical input is `dataset=eod` plus `exchange=HOSE`, `HNX`, or `UPCOM`.

Outcome: Dataset Explorer exposes a `Refresh Metadata` action for supported exact
partitions. It submits `REBUILD_DATASET_METADATA` through the existing Phase 7
Platform trigger API with a reason and logical `dataset`/`partition` parameters,
then displays the execution ID, polls the existing status API, and refreshes the
metadata view only after success.

Dataset outputs: no analytical dataset output. Existing Parquet is read and
validated but is neither recomputed, backfilled, deleted, nor rewritten.

Metadata outputs: the rebuild resolves physical storage internally, derives the
canonical schema, row/object/byte statistics, exact persisted-byte checksums, and
business-date range where applicable, then calculates deterministic `dataVersion`.
It writes `_metadata/datasets/<dataset>/<partition_path>/versions/<dataVersion>.json`,
validates the persisted immutable manifest, and replaces
`_metadata/datasets/<dataset>/<partition_path>/READY.json` last. Any failure
preserves the previous READY pointer.

Algorithm feature outputs: no direct algorithm feature output. The rebuilt
metadata restores reproducible schema/statistics/checksum identity for existing
Parquet without changing analytical values.

Algorithms unlocked: metadata health and lineage inspection become recoverable
through a controlled operator action; no new analytical algorithm is introduced.

Contract impact:

- Kafka/service-to-service protobuf: unchanged for V1. The browser never publishes
  to Kafka, and no new wire schema is planned; implementation must stop for a
  producer/consumer migration plan if the existing dispatch contract cannot carry
  the typed logical parameters safely.
- Object-storage JSON manifest: no schema change is planned. P3-I5 republishes the
  existing canonical deterministic manifest contract with immutable-before-READY
  ordering and previous-READY preservation.
- Storage path/dataset ownership: unchanged. Clients cannot supply bucket, object,
  or manifest paths; shared registry/path builders resolve physical storage.
- Public Java/Python API: a reusable Python `rebuild(dataset, partition)` service
  is planned in `libs/py-common`, using existing hashing and `ManifestWriter`
  publication behavior rather than duplicating it. Platform extends its existing
  typed manual-trigger parameter validation and registered job handling.
- Configuration/environment contract: the Phase 7 allow-list gains the dedicated
  manual-only job definition. No storage credential or browser-visible path
  configuration is added.

Scope and approach:

1. Add manual-only `REBUILD_DATASET_METADATA`; no cron is required for V1.
2. Add it to the existing Phase 7 allow-list and reuse operator identity,
   idempotency, audit, dependency guard, exact claim/concurrency, producer/outbox,
   and execution-status behavior.
3. Validate supported datasets, exact required partition keys, allowed values, and
   reject unknown or physical-path-like fields.
4. Resolve existing Parquet through the shared logical storage contract; derive
   canonical metadata from persisted bytes and reuse shared deterministic hashing
   and publication rules.
5. Serialize work by normalized `dataset + partition`; duplicate idempotent requests
   must not create duplicate executions and concurrent rebuilds of the same exact
   partition must not run together.
6. Add Dataset Explorer confirmation with dataset, partition, and required reason;
   prevent duplicate clicks, poll boundedly, preserve the current metadata view on
   failure, and refresh it after `SUCCESS`.
7. Extend the same typed contract later to indicators, signals, sector-wave, and
   sector-transition only after each dataset's exact partition contract is defined
   and covered. V1 does not implement bulk or wildcard rebuilds.

Repository guidance updates: implementation must review and synchronize
[`AGENTS.md`](../../AGENTS.md), [`CLAUDE.md`](../../CLAUDE.md),
[`.roo/rules`](../../.roo/rules), [`docs/README.md`](../../docs/README.md),
[`docs/data/data-lake.md`](../../docs/data/data-lake.md),
[`docs/flows/job-execution.md`](../../docs/flows/job-execution.md), and the relevant
Platform, py-common, and Omni Console documentation. This planning-only update
changes no runtime behavior, so agent rules require no immediate amendment.

Required tests/checks:

- py-common unit/storage tests for dataset and partition validation, deterministic
  metadata generation, persisted Parquet statistics, immutable-before-READY
  publication, unchanged-content identity, and prior-READY preservation;
- Platform tests for manual allow-listing, authorization, typed parameter rejection,
  idempotency, duplicate execution prevention, exact-partition concurrency, audit,
  sanitized failures, and SUCCESS/FAILED status;
- Console tests for supported-partition visibility, confirmation/reason, duplicate
  click prevention, bounded polling, success refresh, and failure-view preservation;
- inspect targets first, then run `nx run py-common:format -- --check`,
  `nx run py-common:lint`, `nx run py-common:test`, `nx run py-common:build`,
  `nx run platform:test`, `nx run platform:build`, `nx run omni-console:lint`,
  `nx run omni-console:typecheck`, `nx run omni-console:test`, and
  `nx run omni-console:build`, followed by applicable affected and CI checks.

Migration/backward compatibility: additive manual-only job and typed parameters;
no existing cron, Parquet, manifest schema, READY pointer, or Phase 7 endpoint is
replaced. Do not depend on P1-I4 `workType`/`workKey`. Rollback disables/removes the
allow-list entry and Console action while retaining all existing Parquet and the
last valid READY object.

Risks and stop conditions: stop if the existing trigger API cannot safely validate
and dispatch typed parameters, if exact dataset path ownership is ambiguous, if
wildcard/multi-object resolution lacks a dataset-owned contract, or if the design
would require force/bypass, direct browser-to-Kafka/object-storage, physical paths,
credential exposure, Parquet rewrite, or a manifest schema change without impact
analysis.

Acceptance criteria:

- [ ] One exact EOD partition for HOSE, HNX, or UPCOM can be submitted from Dataset Explorer.
- [ ] Submission uses the existing Phase 7 Platform API and authenticated operator boundary.
- [ ] Browser payload contains only logical dataset/partition, reason, and existing trigger metadata.
- [ ] Unsupported datasets, invalid values, unknown keys, and physical path fields are rejected.
- [ ] Existing Parquet is read and validated but not recomputed, deleted, or rewritten.
- [ ] Metadata uses canonical deterministic schema/statistics/checksum and `dataVersion` rules.
- [ ] Immutable manifest is persisted and validated before READY replacement.
- [ ] Failure preserves the previous READY pointer and exposes only a sanitized execution error.
- [ ] Identical persisted content produces the same `dataVersion`.
- [ ] The same normalized dataset/partition cannot rebuild concurrently.
- [ ] Existing idempotency prevents duplicate execution creation.
- [ ] Existing execution status exposes the terminal SUCCESS or FAILED result.
- [ ] Console requires confirmation/reason, prevents duplicate clicks, refreshes after success, and preserves the current view after failure.
- [ ] Targeted Nx checks, affected checks, formatting, and CI pass with evidence recorded.
- [ ] Roadmap, data/flow/Console docs, and repository guidance are synchronized.

## Verification

Use only inspected Nx targets. P3-I1 requires `nx run py-common:lint`, `nx run py-common:test`, and `nx run py-common:build`; Java fixture compatibility requires `nx run platform:test`. Producer migrations additionally require their owning project targets and storage-backed failure-path tests. P3-I5 uses the inspected py-common, Platform, and Omni Console targets listed in its increment and requires storage-backed failure safety plus Phase 7 trigger regression coverage. Record any unavailable graph, PR, commit, or CI evidence rather than inferring it from local success.

## Acceptance Criteria

Each increment must satisfy its scoped criteria above, preserve JSON manifests as the object-storage source of truth, publish READY last, retain logical dataset routing, pass its targeted and relevant Nx checks, and synchronize affected data, flow, service, and repository guidance. Completion additionally requires PR, verified commit, CI, graph impact/change review, and compatibility evidence required by the roadmap definition of done.
