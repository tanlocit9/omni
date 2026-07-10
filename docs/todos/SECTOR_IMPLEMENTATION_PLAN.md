# Sector Implementation Plan

## Status

This document is the verified implementation status and remaining roadmap for sector classification in the Omni stock sync pipeline.

Sector classification is implemented as an **optional enrichment step inside the existing `SYNC_SYMBOLS` job**. There is no standalone `SYNC_SECTORS` job and no cross-job dependency to orchestrate.

The current design supports:

- normal VNDirect symbol-master synchronization without sector enrichment;
- optional Vietcap/VCI ICB enrichment when enabled per job;
- canonical Java-owned sector codes in PostgreSQL;
- raw ICB metadata retention in Parquet and symbol metadata;
- sector-first EOD job selection without changing S3 object paths.

## Verified Decisions

1. `SYNC_SYMBOLS` remains the only symbol-master job.
2. Sector enrichment is enabled per job using `includeSectorClassification`.
3. Java core owns canonical sector codes, seed data, PostgreSQL relations, and validation.
4. Ingestor owns external ICB retrieval, response normalization, Parquet enrichment, and Kafka upsert publication.
5. Raw ICB values and canonical sector codes are both retained.
6. Sector selection controls the EOD sync universe; it does not change S3 paths.
7. Existing `symbol.sector_id` values are preserved when classification is absent or invalid.
8. `topic-upsert-symbols` is currently treated as a full exchange snapshot. It must not be reused for sector-filtered partial symbol batches unless deactivation semantics are changed.

## Current Target Flow

```text
Java SYNC_SYMBOLS producer
  ├─ reads job_definition.config_json
  ├─ counts active symbols by exchange
  ├─ when includeSectorClassification=false:
  │    └─ publishes symbol job with enrichment disabled
  ├─ when includeSectorClassification=true:
  │    ├─ loads active canonical mappings from PostgreSQL
  │    ├─ falls back to Java seed/default mapping only if sector table is empty
  │    └─ publishes mappings in metadata.sectorMappings
  └─ publishes one Kafka message per exchange
       │
       ▼
Python ingestor
  ├─ fetches listed symbols from VNDirect
  ├─ reads previous symbols/{exchange}.parquet if present
  ├─ if includeSectorClassification=false:
  │    └─ preserves previous classification columns
  ├─ if includeSectorClassification=true:
  │    ├─ fetches Vietcap/VCI ICB data
  │    ├─ merges ICB rows by symbol
  │    ├─ preserves raw ICB level/code/name fields
  │    └─ applies Java-provided canonical mapping
  ├─ validates snapshot and classification coverage
  ├─ writes symbols/{exchange}.parquet
  └─ publishes canonical symbol upsert batch
       │
       ▼
Java symbol upsert consumer
  ├─ resolves incoming sectorCode to canonical sector.id
  ├─ enriches existing canonical sector metadata from source classification fields
  ├─ upserts symbols
  ├─ updates symbol.sector_id only when a valid sector is present
  ├─ preserves existing sector_id when classification is missing/unknown
  └─ deactivates symbols missing from the full exchange snapshot
```

## Implemented Contract

### Job Configuration

Sector enrichment is controlled by `job_definition.config_json`.

Enrichment enabled:

```json
{
  "exchanges": ["HOSE"],
  "includeSectorClassification": true,
  "sectorTaxonomy": "ICB",
  "sectorLevel": 3
}
```

Enrichment disabled:

```json
{
  "exchanges": ["HOSE"],
  "includeSectorClassification": false
}
```

Default behavior:

```text
includeSectorClassification = false
```

This keeps symbol master sync independent from VCI availability.

### Kafka Request Extension

The existing sync-symbols message is extended through `metadata`; no new topic is required.

```json
{
  "jobId": "uuid",
  "logId": "uuid",
  "source": "VND",
  "exchange": "HOSE",
  "detectedAt": "2026-07-10T00:00:00Z",
  "metadata": {
    "symbolCount": 500,
    "includeSectorClassification": true,
    "sectorTaxonomy": "ICB",
    "sectorLevel": 3,
    "sectorMappings": [
      {
        "taxonomy": "ICB",
        "level": 3,
        "sourceCode": "8350",
        "canonicalCode": "BANKING"
      }
    ]
  }
}
```

Rules:

- Java builds `sectorMappings` from active PostgreSQL sector data.
- Java may use `SectorSeedConfig` only during bootstrap when the `sector` table is empty.
- Python must not maintain an independent canonical mapping list.
- Do not add S3 `bucket` or `objectName` to new Kafka messages. Existing override handling is backward compatibility only.

### Canonical Symbol Representation

The Parquet record and Kafka upsert record use the same normalized sector representation.

```json
{
  "code": "VCB",
  "exchange": "HOSE",
  "type": "STOCK",
  "status": "LISTED",
  "isin": "...",
  "companyId": "...",
  "companyName": "...",
  "listedDate": "2009-06-30",

  "sectorCode": "BANKING",
  "sectorTaxonomy": "ICB",
  "sectorLevel": 3,
  "sourceSectorCode": "8350",
  "sourceSectorNameVi": "Ngân hàng",
  "sourceSectorNameEn": "Banks",

  "icbLv1Code": "...",
  "icbLv1NameVi": "...",
  "icbLv1NameEn": "...",
  "icbLv2Code": "...",
  "icbLv2NameVi": "...",
  "icbLv2NameEn": "...",
  "icbLv3Code": "8350",
  "icbLv3NameVi": "Ngân hàng",
  "icbLv3NameEn": "Banks",
  "icbLv4Code": "...",
  "icbLv4NameVi": "...",
  "icbLv4NameEn": "...",

  "classificationUpdatedAt": "2026-07-10T00:00:00Z"
}
```

Source codes are used for matching. Names are display metadata and may change.

## PostgreSQL Model

### `sector`

Implemented in `database/migrations/V4__create_sector_table.sql`.

Minimum fields:

```text
id
code
name_vi
name_en
taxonomy
taxonomy_level
source_code
parent_id
is_active
meta_json
created_at
updated_at
```

Uniqueness:

```text
UNIQUE(code)
UNIQUE(taxonomy, taxonomy_level, source_code)
```

Current modeling note:

- `sector` combines canonical sector identity and source mapping metadata.
- The current consumer resolves by canonical `sectorCode`, then enriches the existing canonical sector row with source metadata.
- It does not create separate source-classification rows keyed only by `(taxonomy, taxonomy_level, source_code)`.

### `symbol`

Implemented field:

```text
sector_id UUID NULL REFERENCES sector(id)
```

Raw ICB fields are retained in `symbol.meta_json`. The relation `symbol.sector_id` is authoritative for sector filtering.

## Implemented Components

### Java Core

Implemented:

- `SectorSeedConfig` defines Java-owned canonical mappings.
- `SectorSeeder` seeds sector rows idempotently.
- `JobDefinitionConfig` defines:
  - `includeSectorClassification`
  - `sectorTaxonomy`
  - `sectorLevel`
  - `sectorMappings`
- `SyncSymbolsJobProducer`:
  - reads symbol job config;
  - defaults enrichment to disabled;
  - loads mappings from `SectorRepository.findActiveMappings`;
  - falls back to `SectorSeedConfig` only when `sectorRepository.count() == 0`;
  - publishes mapping metadata in the existing sync-symbols message.
- `SymbolUpsertConsumer`:
  - resolves sector by canonical `sectorCode`;
  - updates sector metadata when source fields are present;
  - passes nullable `sectorId` to `SymbolRepository.upsertOne`;
  - preserves existing `symbol.sector_id` when incoming sector is null or unknown.
- `SymbolRepository.findBySectors` supports active symbol lookup by canonical sector code.

### Python Ingestor

Implemented in `apps/ingestor/app/handlers/symbols.py`:

- Reads `includeSectorClassification` from message metadata.
- Fetches VNDirect symbol data.
- Fetches VCI sector data only when enrichment is enabled.
- Uses previous Parquet snapshot to preserve classification when enrichment is disabled or VCI fails.
- Normalizes raw ICB columns to canonical camelCase fields.
- Applies Java-provided `sectorMappings`.
- Calculates classification metrics.
- Enforces warning/failure thresholds:
  - warning below `98%`;
  - failure below `90%`.
- Writes `symbols/{exchange}.parquet` via `settings.get_symbols_path(exchange)`.
- Publishes canonical upsert records to `topic-upsert-symbols`.
- Publishes `success`, `partial_success`, or `error` status.

## Failure Semantics

### Enrichment disabled

- VNDirect symbols sync normally.
- Previous classification fields are preserved from the existing Parquet snapshot when available.
- Status is `success`.

### Enrichment enabled and VCI succeeds

- Fresh raw ICB and canonical sector fields are written to Parquet.
- Canonical records are published to Java.
- Status is `success` unless validation emits warnings.

### Enrichment enabled and VCI fails

- If a previous symbol snapshot exists:
  - new symbol master data is combined with preserved classification fields;
  - status is `partial_success`;
  - status contains warnings and `classificationSource = "STALE"`.
- If no previous snapshot exists:
  - processing fails;
  - status is `error`.

Current implementation does not have a separate `requireSectorClassification` flag. `includeSectorClassification=true` plus no fallback snapshot behaves as required classification.

## EOD Sector Selection

EOD job definitions reference canonical sector codes:

```json
{
  "sectorCodes": ["BANKING", "SECURITIES"],
  "includeDescendants": true
}
```

Java resolves matching active symbols and publishes ordinary stock-price sync messages.

Storage remains path-builder driven:

```text
eod/{exchange}/{code}.parquet
```

Sector names or codes must not appear in EOD object paths.

## Remaining Work

### Documentation and contract hardening

- [x] Verify implementation plan against current code.
- [x] Document implemented symbol-sector sync workflow.
- [x] Document EOD sector selection rules.
- [ ] Decide whether `topic-upsert-symbols` needs an explicit `fullSnapshot` or `snapshotScope` field before any partial symbol upsert use case is introduced.
- [ ] Decide whether a separate `requireSectorClassification` config is needed.

### Java improvements

- [ ] Add producer tests for enabled, disabled, and bootstrap mapping configurations.
- [ ] Add consumer tests proving missing/unknown sector classification preserves existing `sector_id`.
- [ ] Add an integration test from symbol enrichment to sector-filtered EOD messages.
- [ ] If partial symbol upserts are introduced, guard `deactivateMissing` behind an explicit full-snapshot flag.
- [ ] If descendant selection is required, extend repository queries to traverse `sector.parent_id`.

### Ingestor improvements

- [ ] Add fixtures for VNDirect symbols and VCI sector responses.
- [ ] Add merge/mapping tests for ICB level 1-4 fields.
- [ ] Add tests for disabled enrichment preserving previous classification.
- [ ] Add tests for VCI failure with and without previous snapshot.
- [ ] Consider making coverage thresholds configurable.

### Future UI

- [ ] Add `GET /api/sectors?active=true` with symbol counts.
- [ ] Use the endpoint to populate the job-definition sector selector.
- [ ] Store selected canonical sector codes in `job_definition.config_json`.
- [ ] Validate submitted codes against active database records.

## Acceptance Criteria

- A normal `SYNC_SYMBOLS` job works without calling VCI.
- Enabling enrichment adds raw ICB and canonical `sectorCode` to Parquet.
- The Kafka upsert payload and Parquet contain the same sector representation.
- PostgreSQL sector records originate from Java seed and can be enriched with synced ICB metadata.
- Missing or failed optional enrichment never clears an existing symbol-sector relationship.
- EOD jobs can select symbols by canonical sector code.
- EOD paths remain `eod/{exchange}/{code}.parquet`.
- Tests should cover bootstrap, enabled, disabled, partial failure, idempotent reprocessing, and deactivation safety.