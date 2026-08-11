# Dataset Metadata Manifest Implementation Plan

## Goal

Store dataset metadata in the same MinIO/S3-compatible object storage as Omni analytical data.

Do not introduce PostgreSQL or Redis as the source of truth for dataset statistics in V1.

The metadata layer must let Internal Tools and downstream jobs answer common questions without scanning every Parquet object:

- how many objects exist;
- total byte size;
- total row count;
- schema/column count;
- min/max trading date or timestamp;
- last successful update;
- dataset readiness/freshness;
- physical data paths/globs.

## Outcome

After this phase Omni has an object-storage-native dataset catalog where metadata travels with the data lifecycle.

The same manifests can be used by:

- Internal Tools dataset browser;
- scheduler/data dependency readiness checks;
- data-quality validation;
- intraday/realtime reconciliation;
- future feature registry/catalog tooling.

No cache database is required for V1.

## Dataset Outputs

Metadata is stored inside the `stock-data` bucket under a reserved prefix:

```text
stock-data/
  _metadata/
    catalog.json
    datasets/
      eod/...
      indicators/...
      sector-features/...
      intraday-trades/...
      intraday-bars/...
      intraday-features/...
      realtime-ticks/...
```

Example partition manifest:

```text
stock-data/_metadata/datasets/
  intraday-bars/
    timeframe=1m/
      date=2026-08-11/
        exchange=HOSE.json
```

Corresponding data:

```text
stock-data/intraday/bars/
  timeframe=1m/
    date=2026-08-11/
      exchange=HOSE/
        part-000.parquet
        part-001.parquet
```

Keep metadata outside the data prefix so DuckDB Parquet globs remain clean.

## Manifest Schema

Recommended V1 JSON:

```json
{
  "version": 1,
  "dataset": "intraday-bars",
  "path": "intraday/bars/timeframe=1m/date=2026-08-11/exchange=HOSE/*.parquet",
  "partition": {
    "timeframe": "1m",
    "date": "2026-08-11",
    "exchange": "HOSE"
  },
  "status": "READY",
  "objectCount": 2,
  "totalBytes": 18342112,
  "rowCount": 184210,
  "columnCount": 12,
  "columns": [
    {"name": "bar_time", "type": "TIMESTAMP"},
    {"name": "symbol", "type": "VARCHAR"},
    {"name": "close", "type": "DOUBLE"}
  ],
  "minTimestamp": "2026-08-11T09:00:00+07:00",
  "maxTimestamp": "2026-08-11T14:45:00+07:00",
  "schemaHash": "...",
  "sourceExecutionId": "...",
  "generatedAt": "2026-08-11T15:30:00+07:00"
}
```

Fields can remain nullable when they do not apply to a dataset.

## Write / Commit Semantics

The manifest is written **last** and acts as the dataset READY marker.

```text
write data objects
      |
      v
validate output
      |
      v
calculate metadata
      |
      v
PUT metadata manifest
      |
      v
consumer sees READY dataset
```

Rules:

1. Never publish a new READY manifest before the corresponding data is valid.
2. Failed writers must leave the previous successful manifest untouched when possible.
3. Reprocessing the same partition replaces its manifest idempotently.
4. `generatedAt` describes manifest generation time; trading/evaluation time remains separate.
5. Downstream freshness checks should read the manifest rather than list the full data prefix.

This gives Omni a lightweight commit marker without introducing a transaction database.

## Catalog

`stock-data/_metadata/catalog.json` contains stable dataset definitions, not frequently changing aggregate counters.

Example:

```json
{
  "version": 1,
  "datasets": [
    {
      "name": "intraday-bars",
      "metadataPrefix": "_metadata/datasets/intraday-bars/",
      "dataPrefix": "intraday/bars/"
    },
    {
      "name": "sector-features",
      "metadataPrefix": "_metadata/datasets/sector-features/",
      "dataPrefix": "sector-features/"
    }
  ]
}
```

Internal Tools can list the much smaller metadata prefix and aggregate manifests instead of scanning all Parquet objects.

Avoid one global mutable statistics file updated by every writer; that would create a read-modify-write race as data volume and parallelism increase.

## Metadata Writer Abstraction

Put reusable object-storage metadata logic in `py_common` when Python writers own the output.

Suggested abstraction:

```python
class DatasetManifestWriter:
    async def write_manifest(self, manifest: DatasetManifest) -> None: ...
```

Shared responsibilities:

- stable manifest path generation;
- schema serialization;
- byte/object/row aggregation;
- validation of required metadata fields;
- JSON serialization;
- idempotent object PUT.

Do not duplicate manifest JSON construction across analyzer/ingestor handlers.

Java/core may have a small read-only manifest client for readiness/UI access when needed.

## Internal Tools Access

Preferred V1:

```text
UI
  -> metadata resolver/read endpoint
  -> MinIO _metadata manifests
```

For trusted/local development, manifests may also be read directly through short-lived presigned URLs.

Backend must not calculate stats by scanning all data objects on every UI request.

UI dataset summary can display:

```text
Dataset          intraday-bars / 1m
Objects          2
Size             17.5 MB
Rows             184,210
Columns          12
Range            09:00 - 14:45
Updated          15:30
Status           READY
```

## Scheduler / Dependency Integration

A job declaring:

```json
{
  "dependsOnDatasets": ["intraday-bars:1m"]
}
```

can validate:

- manifest exists;
- `status == READY`;
- required partition/date exists;
- schema version is supported;
- `generatedAt`/evaluation date satisfies freshness rules.

This should replace repeated object-prefix scans for dependency checks.

## Intraday / Realtime Integration

### Intraday EOD

Each completed `date + exchange + timeframe` partition writes one manifest.

### Realtime tick archive

Micro-batch part files do not need a global manifest update for every tiny flush.

Preferred flow:

```text
micro-batch parts
  -> temporary/session metadata if needed
  -> EOD compaction
  -> validated canonical parts
  -> final READY manifest
```

This avoids turning the manifest itself into a high-frequency hot object.

## Algorithm Feature Outputs

No direct market algorithm feature output.

Metadata fields may become data-quality inputs, not trading features:

- `rowCount`;
- `objectCount`;
- `totalBytes`;
- `minTimestamp` / `maxTimestamp`;
- freshness lag;
- schema/version identifiers.

Do not mix these operational/data-quality values into price/market feature datasets unless a later algorithm explicitly models data quality.

## Algorithms Unlocked

No trading algorithm is directly unlocked.

The metadata layer indirectly enables safer:

- backtests with dataset completeness checks;
- sector/intraday pipelines with freshness validation;
- realtime-vs-batch reconciliation;
- automated feature input validation.

## Implementation Steps

### Step 1 — Shared contract

- [ ] Add `DatasetManifest` model in `py_common`.
- [ ] Add stable manifest path builder.
- [ ] Reserve `_metadata/` prefix.
- [ ] Add `catalog.json` schema.

### Step 2 — Writer integration

- [ ] Write manifest after successful Parquet validation.
- [ ] Start with new intraday datasets and important existing analytical datasets.
- [ ] Preserve previous READY manifest on failed rewrite.

### Step 3 — Internal Tools

- [ ] Read catalog/manifest objects.
- [ ] Show count, size, rows, schema, date range and freshness.
- [ ] Drill from manifest into direct Parquet query.

### Step 4 — Dependency guard

- [ ] Resolve required dataset manifest by evaluation date/timeframe.
- [ ] Fail/skip clearly when manifest is missing or stale.
- [ ] Include readiness reason in job metadata/operations notification.

### Step 5 — Realtime compatibility

- [ ] Do not update canonical manifest per tick/micro-batch.
- [ ] Publish final session manifest after compaction/reconciliation.

## Acceptance Criteria

- Dataset metadata is persisted in MinIO, not PostgreSQL/Redis.
- UI can obtain common dataset stats without scanning every Parquet object.
- Manifest is written only after corresponding data validates successfully.
- Manifest paths are deterministic and idempotent.
- Downstream jobs can use manifests for readiness/freshness checks.
- Realtime micro-batching does not create a metadata hot-write bottleneck.
- Metadata storage remains compatible with both MinIO and AWS S3.
