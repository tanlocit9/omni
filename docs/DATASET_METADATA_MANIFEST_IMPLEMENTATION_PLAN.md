# Dataset Metadata Manifest Implementation Plan

## Goal

Store dataset statistics, readiness and version lineage in the same S3-compatible object storage as Omni analytical data.

Do not introduce PostgreSQL/Redis as the source of truth for dataset statistics in V1.

## Outcome

After this phase Internal Tools and downstream jobs can answer, using small JSON object reads:

- object count / total bytes / total rows;
- schema/column information;
- min/max date or timestamp;
- READY state and last successful generation;
- deterministic dataset `dataVersion`;
- which upstream dataset versions produced the current output;
- physical data path/glob for authorized data access.

The manifest becomes both a fast UI metadata source and the primary dataset dependency/readiness contract.

## Dataset Outputs

No new market dataset is required by the metadata layer itself.

Metadata objects:

```text
_metadata/catalog.json
_metadata/datasets/<dataset>/<partition>.json
```

Keep `_metadata/` outside Parquet data prefixes so DuckDB globs remain clean.

## Manifest Schema

Recommended V1 shape:

```json
{
  "version": 1,
  "dataset": "intraday-bars",
  "partition": {
    "timeframe": "1m",
    "date": "2026-08-11",
    "exchange": "HOSE"
  },
  "status": "READY",
  "path": "intraday/bars/timeframe=1m/date=2026-08-11/exchange=HOSE/*.parquet",
  "dataVersion": "sha256:...",
  "objectCount": 2,
  "totalBytes": 18342112,
  "rowCount": 184210,
  "columnCount": 12,
  "columns": [
    {"name": "bar_time", "type": "TIMESTAMP"},
    {"name": "symbol", "type": "VARCHAR"},
    {"name": "close", "type": "DOUBLE"}
  ],
  "schemaVersion": 1,
  "schemaHash": "sha256:...",
  "minTimestamp": "2026-08-11T09:00:00+07:00",
  "maxTimestamp": "2026-08-11T14:45:00+07:00",
  "inputs": [
    {
      "dataset": "intraday-trades",
      "partition": {
        "date": "2026-08-11",
        "exchange": "HOSE"
      },
      "dataVersion": "sha256:..."
    }
  ],
  "sourceExecutionId": "...",
  "generatedAt": "2026-08-11T15:30:00+07:00"
}
```

Nullable fields are allowed where not applicable.

## `dataVersion` Semantics

`generatedAt` is not a sufficient data version because an idempotent retry with unchanged contents should not necessarily invalidate every downstream dataset.

Prefer a deterministic fingerprint based on canonical output identity/content metadata, for example:

```text
sha256(
  dataset
  + normalized partition
  + schemaHash
  + ordered object keys/checksums-or-ETags
)
```

The exact canonicalization must be defined and tested once in shared storage code.

Downstream manifests copy each upstream `dataVersion` into `inputs[]`.

This enables `CURRENT_INPUTS` checks without reading Parquet.

## Write / Commit Semantics

Manifest is written **last**:

```text
write data objects
   -> validate
   -> calculate stats/schema/dataVersion
   -> PUT READY manifest
   -> consumers may use partition
```

Rules:

1. Never publish READY before data validation succeeds.
2. Failed rewrites leave the last successful manifest untouched when possible.
3. Reprocessing a partition replaces its manifest idempotently.
4. `generatedAt` is operational time; trading/evaluation time belongs in the partition/data.
5. Readiness checks use manifest reads, not full prefix scans.
6. `inputs[]` must contain the exact upstream data versions actually consumed.

## Catalog

`_metadata/catalog.json` stores stable dataset definitions:

```json
{
  "version": 1,
  "datasets": [
    {
      "name": "intraday-bars",
      "metadataPrefix": "_metadata/datasets/intraday-bars/",
      "dataPrefix": "intraday/bars/"
    }
  ]
}
```

Avoid one globally-mutated counter/statistics object updated by every data writer.

## Shared Contract Placement

Persisted manifest contract remains JSON.

Shared Python writer/read/path logic belongs in `py_common`.

Platform may have a small read-only Java manifest client/adapter for scheduler/Internal Tools APIs.

Cross-service Kafka references to datasets use protobuf `DatasetRef`/`DatasetOutput`; protobuf does not replace the persisted JSON manifest.

See `CROSS_SERVICE_PROTOBUF_CONTRACTS_IMPLEMENTATION_PLAN.md`.

## Scheduler / Dependency Integration

Dependency guard can validate:

```text
EXISTS
READY
PARTITION_MATCH
MIN_ROW_COUNT
SUPPORTED_SCHEMA_VERSION
MAX_FRESHNESS_LAG
CURRENT_INPUTS
```

`CURRENT_INPUTS` compares downstream `inputs[].dataVersion` with current upstream manifests.

See `JOB_DEPENDENCY_GUARD_IMPLEMENTATION_PLAN.md`.

## Internal Tools

Dataset Browser reads manifests for fast cards/tables:

```text
Dataset
Status
Objects
Size
Rows
Columns
Range
Generated At
Data Version
Input Versions / lineage
```

Only when the user drills into data should DuckDB-Wasm query the Parquet objects.

## Intraday / Realtime

Intraday EOD publishes one READY manifest per completed partition.

Realtime micro-batches do not rewrite the canonical manifest for every flush. Publish final session READY metadata after compaction/reconciliation.

## Algorithm Feature Outputs

No direct market algorithm feature output.

Operational/data-quality values such as row count, freshness and versions are not market features unless a later research model explicitly chooses to use them.

## Algorithms Unlocked

No trading algorithm directly. The layer enables safer backtests, dependency-aware pipelines, realtime/batch reconciliation and feature input validation.

## Implementation Steps

1. Define JSON DatasetManifest schema including `dataVersion` and `inputs[]`.
2. Add shared deterministic manifest/data-version builder in `py_common`.
3. Reserve `_metadata/` paths in shared storage config.
4. Integrate writer-last manifest publishing into important existing/new datasets.
5. Add Platform read client for dependency checks.
6. Add Internal Tools manifest/lineage display.
7. Add dependency guard conditions, especially `CURRENT_INPUTS`.
8. Keep realtime micro-batch writes off the canonical manifest hot path.

## Repository Guidance Updates

Implementation must review/update:

```text
AGENTS.md
CLAUDE.md when storage/tool workflow guidance changes
.roo/rules/
docs/data/data-lake.md
docs/flows/job-execution.md
```

Guidance must describe READY-last semantics, centralized S3/R2 metadata, `dataVersion` lineage and the rule against scanning Parquet merely to test readiness.

## Verification

- deterministic `dataVersion` tests;
- manifest serialization/schema tests;
- failed-write preserves old READY manifest test;
- CURRENT_INPUTS lineage tests;
- S3/R2/MinIO compatibility tests where supported;
- targeted/affected Nx checks.

## Acceptance Criteria

- common dataset stats/readiness are available from JSON manifests;
- `dataVersion` is deterministic for equivalent canonical output;
- downstream manifests record exact upstream versions consumed;
- dependency checks require no normal Parquet prefix scan;
- metadata works with MinIO/S3/R2-compatible storage;
- no PostgreSQL/Redis metadata cache is required;
- repository guidance and Zoo Code rules are synchronized.
