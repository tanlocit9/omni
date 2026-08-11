# Internal Tools — Simple Parquet Viewer Implementation Plan

## Goal

Create `apps/internal-tools` as the first Omni web application.

The first feature is a Simple Parquet Viewer. The browser reads Parquet directly; backend/core does **not** convert Parquet rows to JSON.

Frontend only needs:

- dataset/path identifier;
- readable URL for that path;
- known/allowed fields when available;
- optional dataset metadata.

## Architecture Decision

Use DuckDB-Wasm in the browser and query remote Parquet directly.

```text
Internal Tools React
       |
       | dataset path
       v
Dataset Resolver
       |
       | public URL or short-lived presigned GET URL
       v
DuckDB-Wasm
       |
       | Parquet metadata + HTTP Range requests
       | projection/filter pushdown
       v
Arrow record batches
       |
       v
TanStack Table
```

Do not fetch the complete Parquet file with `fetch(...).arrayBuffer()` for normal remote datasets.

DuckDB should query the URL directly so Parquet scans can use metadata and HTTP range reads for only the required columns/row groups.

## Tech Stack

- React
- TypeScript
- Vite
- Nx React/Vite plugin matching workspace Nx version
- shadcn/ui + Tailwind CSS
- TanStack Table
- DuckDB-Wasm
- TanStack Query only for metadata/path resolution APIs
- Vitest
- Playwright later for critical internal-tool flows

Use `apps/internal-tools`, not `apps/parquet-viewer`, because Parquet Viewer is only the first internal feature.

## Data Access Contract

### Preferred contract

Frontend uses a logical path:

```text
sector-features/1d/2/BANKS.parquet
```

A resolver returns access metadata, not rows:

```json
{
  "path": "sector-features/1d/2/BANKS.parquet",
  "url": "https://...short-lived-readable-url...",
  "fields": [
    "date",
    "sector_code",
    "breadth_above_ma20",
    "contributors"
  ],
  "expiresAt": "2026-08-11T10:00:00Z"
}
```

For local/dev public MinIO paths, `url` may be deterministic and no resolver API is required.

For private MinIO, core or a small data-access endpoint should issue a short-lived read-only presigned URL.

Backend responsibilities stop there. It must not parse Parquet or paginate rows for V1.

## CORS / Object Storage Requirements

The Parquet origin must allow browser reads from the Internal Tools origin.

Minimum expected methods/headers:

- `GET`
- `HEAD`
- HTTP Range requests
- CORS for the Internal Tools origin

Do not expose MinIO access key/secret key to the browser.

## Query Model

The UI builds controlled query state rather than arbitrary raw SQL in V1:

```ts
export interface ParquetQuery {
  path: string;
  columns: string[];
  filters: Filter[];
  sort?: Sort;
  limit: number;
  offset?: number;
}
```

Translate the state into DuckDB SQL internally.

Example:

```sql
SELECT date, sector_code, breadth_above_ma20
FROM read_parquet($url)
WHERE date >= $fromDate
ORDER BY date DESC
LIMIT 200
```

Rules:

- select only visible/required columns;
- default limit: 200;
- hard UI limit for V1: 5,000 rows per result window;
- filter/sort inside DuckDB, not with large JavaScript arrays;
- prefer prepared statements/escaped identifiers for user-controlled values.

## Streaming Result to UI

Use DuckDB-Wasm `connection.send()` for larger result sets so Arrow batches are consumed lazily.

```text
DuckDB query
   -> Arrow batch
   -> normalize visible rows
   -> append/update table window
```

Do not wait for a fully materialized result when streaming is useful.

For the first simple table, materialized `query()` is acceptable behind the same repository interface, but infrastructure must be designed so `send()` can replace it without changing components.

## Schema / Known Fields

Frontend can maintain expected fields per Omni dataset type:

```ts
export const datasetSchemas = {
  eod: [...],
  indicators: [...],
  signals: [...],
  symbolFeatures: [...],
  sectorFeatures: [...],
  sectorTransitionPredictions: [...],
};
```

Treat this as UI metadata, not the source of truth for physical Parquet schema.

At runtime also support:

```sql
DESCRIBE SELECT * FROM read_parquet($url)
```

or Parquet schema metadata inspection.

Behavior:

- known field => apply friendly label/formatter/filter type;
- unknown field => still render generically;
- missing expected field => show schema warning, do not crash.

This lets Parquet evolve without requiring a frontend release for every additive column.

## Folder Structure

```text
apps/internal-tools/src/
├── app/
│   ├── App.tsx
│   ├── router.tsx
│   └── providers.tsx
├── features/
│   └── parquet-viewer/
│       ├── domain/
│       │   ├── dataset.ts
│       │   ├── parquet-query.ts
│       │   └── parquet-data-source.ts
│       ├── application/
│       │   ├── use-dataset.ts
│       │   └── use-parquet-query.ts
│       ├── infrastructure/
│       │   ├── duckdb/
│       │   │   ├── duckdb-client.ts
│       │   │   ├── duckdb-parquet-data-source.ts
│       │   │   └── sql-builder.ts
│       │   └── dataset-resolver/
│       │       └── dataset-resolver-client.ts
│       └── components/
│           ├── ParquetViewer.tsx
│           ├── ParquetToolbar.tsx
│           ├── ParquetTable.tsx
│           └── SchemaPanel.tsx
└── shared/
    ├── components/
    ├── lib/
    └── types/
```

## Core Interface

```ts
export interface ParquetDataSource {
  describe(dataset: DatasetRef): Promise<DatasetSchema>;
  query(query: ParquetQuery): Promise<ParquetResult>;
  stream?(query: ParquetQuery): AsyncIterable<ParquetBatch>;
}
```

UI components depend only on this interface.

## V0 Implementation Steps

### Step 1 — Nx app

- [ ] Add `@nx/react` and `@nx/vite` using the same version as workspace Nx.
- [ ] Generate `apps/internal-tools` with Vite + TypeScript.
- [ ] Normalize npm workspace paths to portable forward-slash/glob form if required.
- [ ] Add app-level lint/test/build targets.

### Step 2 — UI shell

- [ ] Add shadcn/ui + Tailwind.
- [ ] Add simple left navigation.
- [ ] Add `/data/parquet` route.
- [ ] Add dataset path input/select.

### Step 3 — DuckDB-Wasm infrastructure

- [ ] Bundle DuckDB worker/WASM assets with Vite.
- [ ] Create singleton async DuckDB client.
- [ ] Query remote `.parquet` URL directly.
- [ ] Implement `describe()`.
- [ ] Implement projection, filters, sort, limit.
- [ ] Ensure connection/statement cleanup.

### Step 4 — Dataset resolution

- [ ] Start with configured development base URL if MinIO objects are directly readable.
- [ ] Otherwise add a core endpoint that accepts an allow-listed logical dataset path and returns a short-lived read-only URL.
- [ ] Never return object-storage credentials.
- [ ] Reject path traversal and paths outside `stock-data` allow-list.

### Step 5 — Table viewer

- [ ] Dynamic columns from physical schema.
- [ ] Friendly metadata from known Omni field definitions.
- [ ] Generic fallback for unknown/additive fields.
- [ ] Limit selector: 100/200/500/1000.
- [ ] Sorting and basic typed filters.
- [ ] JSON cell renderer for contributor maps and nested values.
- [ ] Loading, empty, schema mismatch, expired URL and CORS errors.

### Step 6 — Stream result batches

- [ ] Add `connection.send()` implementation.
- [ ] Consume Arrow batches without materializing the entire query result.
- [ ] Cancel active query when dataset/filter changes.
- [ ] Keep only the required table window in React state.

## V1 Internal Data Tool Expansion

After Parquet Viewer is stable:

- dataset catalog/browser;
- EOD viewer;
- indicators viewer;
- signal viewer;
- symbol/sector feature viewer;
- Sector Transition prediction/outcome viewer;
- job execution status/history;
- saved queries/views;
- export selected result to CSV.

## Acceptance Criteria

- Browser can open a logical Omni Parquet path without backend row conversion.
- Remote Parquet is queried directly by DuckDB-Wasm.
- Query projects only requested columns and uses limits/filters before rendering.
- Large query results can be consumed as Arrow batches.
- FE works with both known and additional unknown Parquet columns.
- Private MinIO credentials never reach the browser.
- Dataset URL/path access is read-only and allow-listed.
- Table remains responsive for the intended internal-tool result window.
