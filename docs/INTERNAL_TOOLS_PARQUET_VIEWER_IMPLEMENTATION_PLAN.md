# Internal Tools — Simple Parquet Viewer Implementation Plan

## Goal

Create `apps/internal-tools` as the first Omni web application.

The first feature is a Simple Parquet Viewer plus Dataset Browser. The browser reads Parquet directly; backend/core does **not** convert Parquet rows to JSON.

Frontend needs only:

- logical dataset/path;
- readable Parquet URL or short-lived presigned URL;
- known/allowed fields when available;
- dataset metadata manifest stored in MinIO.

## Outcome

After this phase developers can:

- browse Omni datasets and partitions;
- see object count, total size, row count, schema, date/timestamp range and freshness without scanning every Parquet object;
- open a dataset directly in DuckDB-Wasm;
- filter/project/sort Parquet in the browser;
- inspect additive/unknown columns without a backend row API.

## Dataset Outputs

No analytical dataset output.

Internal Tools consumes metadata stored in MinIO under:

```text
stock-data/_metadata/
  catalog.json
  datasets/...
```

See `DATASET_METADATA_MANIFEST_IMPLEMENTATION_PLAN.md`.

## Algorithm Feature Outputs

No direct algorithm feature output.

The tool validates feature datasets before they are consumed by signal/backtest/model logic.

## Algorithms Unlocked

No trading algorithm directly.

It materially improves:

- feature QA;
- backtest input inspection;
- intraday/realtime reconciliation debugging;
- schema/freshness verification.

## Architecture Decision

Use DuckDB-Wasm in the browser for Parquet data and MinIO manifests for dataset statistics.

```text
Internal Tools React
       |
       +---- metadata request ----> MinIO _metadata manifests
       |                                |
       |                                v
       |                        Dataset Browser cards
       |
       +---- logical data path --> URL resolver
                                        |
                                        v
                                   DuckDB-Wasm
                                        |
                              HTTP Range / Parquet
                                        |
                                        v
                                  TanStack Table
```

Do not use PostgreSQL/Redis as dataset metadata storage in V1.

Do not calculate `objectCount/totalBytes/rowCount` by scanning the whole data prefix on every UI request.

## Tech Stack

- React
- TypeScript
- Vite
- Nx React/Vite plugin matching workspace Nx version
- shadcn/ui + Tailwind
- TanStack Table
- DuckDB-Wasm
- TanStack Query for metadata/path-resolution calls
- Vitest
- Playwright later

Use `apps/internal-tools`, not `apps/parquet-viewer`, because Parquet Viewer is only the first internal feature.

## Dataset Metadata Contract

Example manifest consumed by the UI:

```json
{
  "version": 1,
  "dataset": "intraday-bars",
  "path": "intraday/bars/timeframe=1m/date=2026-08-11/exchange=HOSE/*.parquet",
  "status": "READY",
  "objectCount": 2,
  "totalBytes": 18342112,
  "rowCount": 184210,
  "columnCount": 12,
  "minTimestamp": "2026-08-11T09:00:00+07:00",
  "maxTimestamp": "2026-08-11T14:45:00+07:00",
  "generatedAt": "2026-08-11T15:30:00+07:00"
}
```

UI should treat the manifest as the fast summary/readiness source.

When the user opens the dataset, DuckDB may still inspect the physical Parquet schema to verify it matches the manifest/known UI schema.

## Data Access Contract

For private MinIO:

```text
logical path
   -> allow-listed resolver
   -> short-lived GET URL
   -> DuckDB-Wasm read_parquet(url)
```

Backend responsibilities:

- authorize/allow-list logical paths;
- return presigned read-only URLs where required;
- optionally proxy/read small metadata JSON manifests.

Backend must not:

- return MinIO credentials;
- parse full Parquet rows for V1;
- paginate analytical rows as JSON;
- maintain a duplicate metadata database.

## Query Model

```ts
export interface ParquetQuery {
  path: string;
  columns: string[];
  filters: Filter[];
  sort?: Sort;
  limit: number;
}
```

Example SQL:

```sql
SELECT date, sector_code, breadth_above_ma20
FROM read_parquet($url)
WHERE date >= $fromDate
ORDER BY date DESC
LIMIT 200
```

Rules:

- project only requested columns;
- default limit around 200;
- hard UI limit around 5,000 rows per result window;
- filter/sort in DuckDB, not large JS arrays;
- stream Arrow batches with `connection.send()` when useful.

## Known Fields vs Physical Schema

Frontend may keep UI metadata for known Omni fields:

```ts
export const datasetSchemas = {
  eod: [...],
  indicators: [...],
  signals: [...],
  symbolFeatures: [...],
  sectorFeatures: [...],
  intradayBars: [...],
  intradayFeatures: [...],
};
```

Rules:

- known field => friendly formatter/filter;
- unknown field => generic renderer;
- missing expected field => warning, not crash;
- manifest schema is a fast summary;
- physical Parquet schema remains verifiable at runtime.

## Folder Structure

```text
apps/internal-tools/src/
├── app/
├── features/
│   ├── dataset-browser/
│   │   ├── domain/
│   │   ├── application/
│   │   ├── infrastructure/
│   │   │   └── metadata-manifest-client.ts
│   │   └── components/
│   └── parquet-viewer/
│       ├── domain/
│       ├── application/
│       ├── infrastructure/
│       │   ├── duckdb/
│       │   └── dataset-resolver/
│       └── components/
└── shared/
```

## Core Interfaces

```ts
export interface DatasetMetadataSource {
  listDatasets(): Promise<DatasetSummary[]>;
  listPartitions(dataset: string): Promise<DatasetManifest[]>;
}

export interface ParquetDataSource {
  describe(dataset: DatasetRef): Promise<DatasetSchema>;
  query(query: ParquetQuery): Promise<ParquetResult>;
  stream?(query: ParquetQuery): AsyncIterable<ParquetBatch>;
}
```

## Implementation Steps

### Step 1 — Nx app/UI shell

- [ ] Generate `apps/internal-tools` with React + Vite + TypeScript.
- [ ] Add shadcn/Tailwind and routing.
- [ ] Add `/data` Dataset Browser and `/data/parquet` viewer.

### Step 2 — Metadata browser

- [ ] Read `_metadata/catalog.json`.
- [ ] List dataset/partition manifests.
- [ ] Display objects, bytes, rows, columns, range, freshness and status.
- [ ] Do not scan data prefixes to build summaries.

### Step 3 — DuckDB-Wasm

- [ ] Bundle worker/WASM assets.
- [ ] Query remote `.parquet` directly.
- [ ] Implement projection/filter/sort/limit.
- [ ] Add streamed Arrow batches.

### Step 4 — Path resolution

- [ ] Use direct readable URL in local/dev when safe.
- [ ] Otherwise resolve allow-listed logical paths to short-lived presigned URLs.
- [ ] Keep `_metadata` reads read-only and credential-free for the browser.

### Step 5 — Dataset drill-down

- [ ] Click manifest -> open Parquet Viewer using manifest `path`.
- [ ] Compare physical schema with manifest/known fields.
- [ ] Show schema/freshness mismatch warnings.

## Acceptance Criteria

- UI can load dataset statistics from MinIO metadata manifests without scanning all data objects.
- No PostgreSQL/Redis metadata cache is required.
- Browser can query remote Parquet directly with DuckDB-Wasm.
- Private object-storage credentials never reach the browser.
- Unknown additive Parquet fields remain inspectable.
- A READY manifest can be drilled directly into its physical Parquet data.
