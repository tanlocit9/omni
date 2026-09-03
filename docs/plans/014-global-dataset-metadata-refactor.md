# Global Dataset Metadata Refactor

## Status

Implemented as a breaking hard cutover on 2026-09-01.

- Added V1 global document models, deterministic serialization, typed partition definitions, registry-driven full/dataset/exact synchronization, and read-back validation.
- Made `SYNC_METADATA` the sole metadata writer; producers now persist Parquet and authoritative lineage columns only.
- Switched Platform and Query Service to logical resolution from `_metadata/metadata.json`.
- Added logical command contracts, strict Platform validation, browser-safe paginated discovery/options APIs, dynamic Console controls, and all three operator synchronization actions.
- Removed legacy catalog, READY, immutable-version persistence code, paths, fixtures, and tests without a compatibility fallback.

## Objective

Replace the current object-per-partition metadata layout with one canonical JSON document in S3-compatible object storage. Make `SYNC_METADATA` the sole metadata writer, run a full synchronization after data-producing jobs by default, and support dataset-scoped and exact-partition synchronization. Update Analyzer, Ingestor, Platform, Query Service, and Omni Console together.

This is a breaking persistence-contract migration. It does not remove deterministic `dataVersion`, physical checksums, schema identity, statistics, or upstream lineage.

## Decisions

1. The canonical object is `_metadata/metadata.json` in the existing data bucket.
2. It replaces `_metadata/catalog.json`, partition `READY.json` pointers, and immutable metadata objects under `versions/`.
3. `SYNC_METADATA` is the only process allowed to write the global object.
4. Data-producing jobs write and validate Parquet but do not mutate metadata.
5. A scheduled full synchronization runs after relevant data-producing work has reached a terminal state.
6. A trigger with no target performs a full synchronization.
7. A target containing only `dataset` performs a complete synchronization of that dataset.
8. A target containing `dataset` and a complete logical `partition` synchronizes exactly one partition.
9. Dataset partition definitions are dynamic canonical metadata used by Platform, Query Service, Console, and Dashboard queries.
10. The browser never receives object-store credentials, bucket names, endpoints, or physical object paths.
11. Historical metadata snapshots are not retained in V1. Historical data identity remains available through `dataVersion` and lineage fields in the current snapshot.
12. Only one metadata synchronization may execute at a time across scheduled and manual triggers.

## Scope

### In scope

- A versioned global metadata JSON model.
- A canonical registry of supported datasets and partition-key definitions.
- Full, dataset, and exact-partition synchronization.
- Registry-driven adapters for supported Parquet dataset layouts.
- Safe candidate construction, validation, one-object publication, and read-back validation.
- Platform scheduling, command validation, concurrency, status, and dependency-reader changes.
- Query Service metadata APIs and global-document resolution.
- Omni Console dynamic metadata browsing, filtering, synchronization actions, and Dashboard integration.
- Additive protobuf changes needed for the logical `SYNC_METADATA` command.
- Migration, tests, observability, and documentation.

### Out of scope

- Browser access to S3/MinIO.
- Physical paths in trigger or query requests.
- Arbitrary user-provided object prefixes.
- A PostgreSQL or Redis metadata mirror.
- Historical global-document snapshots.
- Automatic reconstruction of lineage when no authoritative persisted evidence exists.
- Concurrent metadata writers or last-write-wins updates.

## Canonical Storage Contract

### Object path

```text
_metadata/metadata.json
```

### Top-level model

```json
{
  "version": 1,
  "generatedAt": "2026-09-01T13:00:00Z",
  "sourceExecutionId": "uuid-or-null",
  "datasets": [
    {
      "name": "eod",
      "label": "End-of-Day Prices",
      "dataPrefix": "eod/",
      "partitionKeys": [],
      "partitions": []
    }
  ]
}
```

Arrays are used instead of encoded `key=value` map keys or dynamic symbol keys. All arrays use deterministic canonical ordering.

### Partition-key definition

```json
{
  "name": "exchange",
  "type": "STRING",
  "required": true,
  "order": 0,
  "queryable": true,
  "label": "Exchange"
}
```

Required correctness fields are `name`, `type`, `required`, and `order`. `queryable` defaults to `true`. `label` is optional presentation metadata. Initial supported value types are `STRING`, `DATE`, `INTEGER`, and `BOOLEAN`.

Rules:

- Names are unique within a dataset.
- Orders are unique, contiguous, and start at zero.
- Every partition contains exactly the required keys and no unknown keys.
- Values match the declared types.
- A normalized `(dataset, partition values)` tuple is unique.
- Dataset adapters and sync request validation use the same registry definition.
- Query Service exposes safe definitions to clients; clients do not invent partition keys.

### Partition model

```json
{
  "values": {
    "exchange": "hose",
    "code": "hpg"
  },
  "status": "READY",
  "path": "eod/hose/hpg.parquet",
  "dataVersion": "sha256:...",
  "schemaVersion": 1,
  "schemaHash": "sha256:...",
  "objectCount": 1,
  "totalBytes": 1234,
  "rowCount": 500,
  "columnCount": 8,
  "columns": [
    {
      "name": "date",
      "type": "DATE",
      "nullable": false
    }
  ],
  "minTimestamp": "2026-01-01",
  "maxTimestamp": "2026-09-01",
  "inputs": [],
  "generatedAt": "2026-09-01T13:00:00Z",
  "sourceExecutionId": "uuid-or-null"
}
```

`path` is an internal persisted field used only by trusted backend services. Query Service must remove it from browser-facing responses.

### Dataset examples

- `eod`: `exchange`, `code`.
- `indicators`: `source`, `timeframe`, `exchange`, `code`.
- `signals`: `strategy`, `timeframe`, `exchange`.
- Other supported datasets declare their own canonical keys in the registry.

## Deterministic Identity

`dataVersion` remains a SHA-256 fingerprint over:

- dataset name;
- normalized partition values;
- schema hash;
- sorted persisted object keys and exact-byte checksums;
- canonically sorted exact upstream `inputs[].dataVersion` references.

`generatedAt`, source execution identity, and global document ordering do not alter `dataVersion`.

## Synchronization Modes

### Full sync

Request target is absent.

- Scan every supported dataset through its registered adapter.
- Build a completely new global document in memory.
- Remove entries whose canonical physical data no longer exists.
- Fail without publication if no valid metadata can be produced or a required dataset cannot be represented truthfully.
- Publish one complete validated candidate.

### Dataset sync

Request contains `dataset` and no partition.

- Rebuild the complete selected dataset section.
- Preserve all unrelated dataset sections from the current document.
- Remove stale partitions within the selected dataset.
- Reject unknown or unsupported datasets.

### Exact-partition sync

Request contains `dataset` and a complete partition object.

- Validate keys and values against the registry.
- Rebuild, replace, or remove exactly one logical partition.
- Preserve all unrelated partitions and datasets.
- Reject partial partitions, extra keys, physical paths, bucket names, and arbitrary prefixes.

## Safe Publication Algorithm

1. Acquire the global metadata synchronization concurrency guard.
2. Load and validate the current global document when a scoped sync requires merge behavior.
3. Resolve physical objects only through the trusted dataset registry and path builders.
4. Read exact persisted bytes and decode through canonical Parquet/date contracts.
5. Validate non-empty data, logical identity, schema, statistics, and dataset-specific invariants.
6. Calculate checksums, schema hash, deterministic `dataVersion`, and truthful lineage.
7. Construct the complete candidate document in memory.
8. Validate duplicate identities, partition definitions, ordering, and all nested models.
9. Serialize deterministically.
10. Write `_metadata/metadata.json` once.
11. Read the persisted object back and validate it.
12. Emit terminal status and invalidate backend caches.

The existing writable storage abstraction cannot make a single S3 object transactionally rollback after a successful overwrite. Sole-writer scheduling prevents concurrent lost updates, while candidate validation prevents known-invalid writes. If stronger rollback is required later, add conditional writes plus retained previous-object recovery before claiming transactional guarantees.

## Dataset Adapter Registry

Create a reusable registry in `libs/py-common` containing, per dataset:

- logical name and label;
- canonical partition-key definitions;
- trusted data prefix and path matcher;
- physical object-to-partition parser;
- Parquet decoder/date normalization policy;
- dataset validation callback;
- schema/statistics extraction;
- lineage extraction policy;
- support flags for full, dataset, and exact synchronization.

Derived datasets must recover exact lineage from authoritative persisted evidence. If exact upstream versions cannot be reconstructed, synchronization fails for that partition rather than publishing invented or empty lineage.

## Backend Changes

### py-common

- Replace catalog, READY, and immutable metadata path helpers in `libs/py-common/py_common/storage/manifest.py`.
- Add global document, dataset, partition-key, and partition models.
- Add deterministic serialization and in-memory indexing by normalized logical identity.
- Replace `ManifestReader` and `ManifestWriter` behavior with global-document read and sole-writer replacement operations.
- Generalize `libs/py-common/py_common/storage/metadata_sync.py` from EOD-only behavior to the registry-driven synchronizer.
- Preserve data/schema version calculation APIs where semantics remain valid.

### Analyzer

- Keep Analyzer as the `topic-sync-metadata` consumer and metadata synchronization owner.
- Accept full, dataset, and exact-partition logical targets.
- Emit `SUCCESS`, `PARTIAL_SUCCESS`, or `ERROR` with bounded counts and sanitized errors.
- Remove direct metadata writes from indicator, signal, and other Analyzer data producers.

### Ingestor

- Remove direct metadata publication from EOD and other Ingestor data producers.
- Keep persisted-byte validation and truthful job status.
- Ensure completion status means the Parquet output is ready for the final metadata synchronization stage.

### Platform

- Extend `SYNC_METADATA` with optional logical dataset and partition parameters.
- Default to full sync when no target is supplied.
- Validate target shape against an allow-listed dataset definition boundary.
- Reject physical storage fields and unknown keys.
- Enforce one active metadata sync globally for scheduled and manual triggers.
- Schedule the default full sync after data-producing jobs and express ordering through scheduler dependencies, not cron timing alone.
- Replace MinIO per-READY-object reads with global-document loading and logical lookup.
- Cache the document as one unit and invalidate/expire it safely.

### Query Service

- Read and validate `_metadata/metadata.json`.
- Build an in-memory index for constant-time logical partition resolution after one object read.
- Replace S3 listing of `READY.json` objects.
- Map internal persistence models to safe versioned HTTP DTOs.
- Expose dynamic partition keys, bounded partition lists, dependent partition options, schema, statistics, versions, freshness, and lineage.
- Never expose `path`, bucket, endpoint, credentials, or unrestricted URLs.

## `libs/contracts` Changes

The global JSON model remains outside protobuf. Add only logical cross-service command changes:

- Add `JOB_TYPE_SYNC_METADATA = 11` to `JobType`.
- Import `omni/contracts/common/v1/dataset.proto` in `job_command.proto`.
- Add `SyncMetadataCommand` with optional `DatasetRef target`.
- Add `sync_metadata = 13` to the existing command payload `oneof`.

Semantics:

- no target: full sync;
- target name with empty partition: dataset sync;
- target name with complete partition: exact sync.

`DatasetOutput.manifest_key` becomes semantically obsolete. Preserve field number 2 for v1 compatibility and mark it deprecated. Add optional `metadata_key = 4` if status outputs need to identify `_metadata/metadata.json`. Do not place the complete persisted metadata model in protobuf.

## Frontend Changes

### Dataset Explorer

- Consume generated Query Service HTTP types.
- Render dataset and partition filters dynamically from `partitionKeys`.
- Load dependent bounded options; for example, `exchange=hose` narrows `code` choices.
- Display freshness, status, `dataVersion`, schema, statistics, and lineage.
- Distinguish missing, invalid, unsupported, stale, partial, synchronizing, and failed states.

### Synchronization actions

- Global action sends no target.
- Dataset action sends the logical dataset only.
- Exact action sends dataset plus complete partition values.
- Confirmation displays logical values and requires an operator reason.
- Use the existing authenticated Platform trigger/status boundary.
- Disable duplicate submissions, poll boundedly, preserve current data on failure, and reload metadata only after successful completion.

### Dashboard and query

- Use the same dynamic partition-key HTTP contract.
- Generate typed controls from definitions instead of hard-coded dataset fields.
- Submit logical partition values only.
- Query Service validates and resolves the exact current partition server-side.

## Migration Plan

1. Implement and test the global models, registry, synchronizer, and readers behind an explicit migration feature flag.
2. Add logical sync command support and sole-writer concurrency enforcement.
3. Add Query Service v1 metadata endpoints and generated Console client.
4. Stop producer-side metadata publication.
5. Deploy backend readers capable of reading the global document before switching consumers.
6. Pause data jobs, run one full `SYNC_METADATA`, and validate `_metadata/metadata.json` against physical data.
7. Switch Platform, Query Service, and Console to the global document.
8. Observe scheduled and manual full/dataset/exact synchronization.
9. Remove obsolete catalog/READY/version reader paths from code.
10. Delete old metadata objects only through a separately approved cleanup after rollback evidence is no longer required.

Legacy signal Parquet without authoritative `eod_data_version` and
`indicators_data_version` columns cannot be migrated truthfully. Before the first
full synchronization, run the Analyzer quarantine tool in dry-run mode, apply the
verified copy-before-delete quarantine, regenerate signals, and then synchronize:

```text
cd apps/analyzer
uv run python tools/quarantine_legacy_signals.py
uv run python tools/quarantine_legacy_signals.py --apply
```

Quarantined objects are retained under
`_quarantine/plan-014-legacy-signals/<timestamp>/signals/...`; the tool verifies
copied bytes before deleting each source object. It never stamps current versions
onto historical rows. Signal jobs must regenerate canonical objects before full
`SYNC_METADATA` can publish them.

Rollback before obsolete-object deletion may switch readers and producer publication back to the old contract. After deletion, rollback requires reconstructing the old layout and is not automatic.

## API and Security Rules

- Browser requests contain only dataset names, logical partition values, reason, and existing trigger metadata.
- Dataset names and keys are registry-validated.
- Value types, lengths, and patterns are bounded.
- Physical object fields are denied even if submitted as unknown JSON properties.
- Metadata endpoints are read-only and paginated.
- Partition option search is bounded and allow-listed.
- Errors are sanitized; storage credentials and paths are not logged or returned.

## Observability

Record bounded metrics and logs for:

- sync mode and logical target;
- objects seen, accepted, skipped, failed, and unchanged;
- partitions added, replaced, removed, and unchanged;
- candidate and persisted document byte size;
- read, decode, build, write, and validation duration;
- cache invalidation and document version;
- terminal result and sanitized reason codes.

Do not use dataset partition values or `dataVersion` as unbounded metric labels.

## Verification

### Shared and storage tests

- Model validation and round trips.
- Dynamic partition-key validation and typed values.
- Duplicate dataset/partition rejection.
- Deterministic ordering and identity.
- Full replacement behavior.
- Dataset replacement preserving unrelated datasets.
- Exact upsert/removal preserving unrelated partitions.
- Invalid candidate performs no write.
- Persisted read-back validation.
- MinIO/S3-compatible one-object integration test.

### Dataset adapter tests

- Path parsing for every supported dataset.
- Exact persisted-byte checksums and statistics.
- Canonical date/schema behavior.
- Empty, corrupt, noncanonical, and internal-version object behavior.
- Exact lineage for derived datasets.
- Failure when lineage cannot be proven.

### Platform tests

- Full, dataset, and exact trigger validation.
- Unknown, partial, extra, and physical-path parameter rejection.
- Scheduled final-stage ordering.
- Scheduled/manual global concurrency.
- Producer payload and terminal status handling.
- Global document dependency lookup and cache behavior.

### Query Service tests

- Global document parsing and indexing.
- Dynamic partition-key exposure.
- Typed filter and exact resolution validation.
- Bounded pagination and dependent options.
- Cache refresh after synchronization.
- No physical path, bucket, endpoint, or credential disclosure.

### Console tests

- Generated contract types compile.
- Dynamic typed controls and dependent options.
- Global, dataset, and exact synchronization actions.
- Confirmation, reason, duplicate prevention, and status polling.
- Failure preserves current UI state.
- Success reloads metadata.
- Dashboard/query sends logical values only.

### End-to-end test

1. Write representative EOD, indicators, and signals Parquet.
2. Run a full synchronization.
3. Discover and filter all datasets through Query Service and Console.
4. Modify one physical partition.
5. Run exact synchronization and verify unrelated entries remain byte-equivalent semantically.
6. Run dataset synchronization and verify stale entries in that dataset are removed.
7. Resolve and query selected logical partitions without exposing physical storage details.

## Acceptance Criteria

- Exactly one canonical metadata object is required for normal operation.
- `SYNC_METADATA` is the only metadata writer.
- Full sync is the default and executes after data-producing work.
- Dataset and exact-partition sync are supported through logical validated targets.
- Dynamic partition definitions are authoritative and reusable by Dashboard/query controls.
- `dataVersion`, schema identity, statistics, and exact lineage remain truthful.
- Platform and Query Service resolve logical partitions from the global object.
- Console supports dynamic discovery, filtering, and all synchronization modes.
- No browser response or request exposes physical object storage details.
- Targeted and affected Nx checks, contract checks, integration tests, and documentation updates pass.

## Documentation Updates

Update together:

- `docs/data/002-data-lake.md`
- `docs/flows/001-job-execution.md`
- `docs/flows/002-stock-sync.md`
- `docs/flows/003-indicator-signal.md`
- `plans/roadmap/phase-3-dataset-manifests.md`
- `plans/omni-metadata-console-dashboard-execution-plan.md`
- `configs/shared/s3-paths.yaml`
- relevant service READMEs
- repository agent guidance if storage workflow rules change
