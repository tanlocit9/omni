# Sector Implementation Plan

## Goal

Add sector classification as an **optional enrichment step inside the existing `SYNC_SYMBOLS` job**. There is no standalone `SYNC_SECTORS` job and no cross-job dependency to orchestrate.

The implementation must support sector-first EOD synchronization and future job-definition UI while keeping VNDirect symbol master synchronization functional when the external ICB source is disabled or temporarily unavailable.

## Decisions

1. `SYNC_SYMBOLS` remains the only symbol-master job.
2. Sector enrichment is enabled per job using `includeSectorClassification`.
3. Java core owns canonical sector codes, seed data, PostgreSQL relations, and validation.
4. Ingestor owns retrieval of external ICB data, response normalization, Parquet enrichment, and Kafka result publication.
5. Raw ICB values and canonical sector codes are both retained.
6. Sector selection controls the EOD sync universe; it does not change S3 paths.
7. Existing sector classifications must never be cleared because optional enrichment is disabled or an upstream call fails.

## Target Flow

```text
Java SYNC_SYMBOLS producer
  ├─ loads canonical sector mapping from PostgreSQL
  ├─ falls back to Java seed/default mapping only during bootstrap
  └─ publishes symbol job
       │
       ▼
Python ingestor
  ├─ fetches listed symbols from VNDirect
  ├─ if includeSectorClassification=false:
  │    └─ preserves last-known sector columns from existing Parquet
  ├─ if includeSectorClassification=true:
  │    ├─ fetches Vietcap ICB data (vi/en)
  │    ├─ merges by symbol
  │    ├─ preserves raw ICB level/code/names
  │    └─ applies canonical mapping supplied by Java
  ├─ validates the snapshot
  ├─ writes symbols/{exchange}.parquet
  └─ publishes symbol upsert batch
       │
       ▼
Java consumer
  ├─ upserts sector source metadata by taxonomy + level + sourceCode
  ├─ preserves the canonical sector code owned by Java
  ├─ upserts symbols
  ├─ updates symbol.sector_id only when classification is present
  └─ records success/warnings without erasing last-known-good data
```

## Job Configuration

Example with sector enrichment enabled:

```json
{
  "exchange": "HOSE",
  "includeSectorClassification": true,
  "sectorTaxonomy": "ICB",
  "sectorLevel": 3
}
```

Example without enrichment:

```json
{
  "exchange": "HOSE",
  "includeSectorClassification": false
}
```

Default:

```text
includeSectorClassification = false
```

This keeps symbol master sync independent from Vietcap availability. Jobs that need fresh sector classification must enable the option explicitly.

## Kafka Request Extension

Extend the existing symbol job message; do not create a new topic.

```json
{
  "jobId": "uuid",
  "logId": "uuid",
  "source": "VND",
  "exchange": "HOSE",
  "metadata": {
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

Java should build `sectorMappings` from active PostgreSQL sector data. During initial bootstrap only, it may use the existing Java constants/seed when the table is empty.

Do not maintain an independent mapping list in Python.

## Canonical Symbol Representation

The Parquet record and Kafka upsert record must use the same normalized representation.

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

Use source codes for matching. Names are display metadata and may change.

## PostgreSQL Model

### sector

Minimum fields:

```text
id
code                       canonical Java-owned code, unique
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

Recommended uniqueness:

```text
UNIQUE(code)
UNIQUE(taxonomy, taxonomy_level, source_code)
```

### symbol

Add:

```text
sector_id UUID NULL REFERENCES sector(id)
```

Keep raw ICB data in `symbol.meta_json` for traceability. The relation `symbol.sector_id` is authoritative for job filtering.

## Java Responsibilities

### Seed

- Reuse the existing Java constants to seed canonical sector records.
- Make seeding idempotent.
- Database becomes the runtime source of truth after bootstrap.
- Do not silently replace a non-empty database mapping with defaults.

### Producer

- Read `includeSectorClassification` from `job_definition.config_json`.
- When enabled, load active sector mappings from PostgreSQL.
- If the sector table is empty during bootstrap, use the existing seed/default values.
- Include the selected taxonomy, level, and mappings in the existing symbol job payload.

### Consumer

For every incoming symbol:

1. Upsert source classification using `(taxonomy, level, sourceCode)`.
2. Resolve the canonical sector using the Java-owned mapping.
3. Upsert the symbol master fields.
4. Update `symbol.sector_id` only when a valid classification exists.
5. If classification is absent, preserve the existing `sector_id`.
6. Never deactivate symbols merely because a sector-filtered or partially enriched payload omitted them.

## Ingestor Responsibilities

1. Fetch VNDirect symbol master data.
2. If enrichment is enabled, fetch Vietcap ICB data in Vietnamese and English.
3. Merge by symbol and retain all available ICB levels.
4. Apply the canonical mapping included in the job message.
5. If enrichment is disabled, preserve classification columns from the previous Parquet snapshot by `(exchange, code)`.
6. Normalize one canonical DataFrame before writing Parquet or creating Kafka records.
7. Write `symbols/{exchange}.parquet`.
8. Publish the same canonical records to Java.

## Validation

Before replacing the Parquet snapshot:

- Required VNDirect fields exist: `code`, `floor`, `status`.
- `(exchange, code)` is unique.
- The response count has not dropped beyond a configured safety threshold.
- When enrichment is enabled, calculate:
  - number of VND symbols;
  - number of ICB symbols;
  - matched count;
  - unmatched count;
  - match percentage;
  - mapped canonical count.
- Do not erase classification fields for unmatched symbols.
- Reject or warn when match coverage falls below the configured threshold.

Suggested initial threshold:

```text
warning < 98%
failure < 90%
```

Tune after observing real responses.

## Failure Semantics

### Enrichment disabled

- Sync VNDirect symbols normally.
- Preserve sector data from existing Parquet/PostgreSQL.
- Return `SUCCESS`.

### Enrichment enabled and Vietcap succeeds

- Persist fresh raw ICB and canonical sector data.
- Return `SUCCESS`.

### Enrichment enabled and Vietcap fails

- Continue only if last-known-good classification exists.
- Write the new symbol master combined with preserved classification.
- Return `PARTIAL_SUCCESS` or `SUCCESS_WITH_WARNINGS`.
- If no previous classification exists and the job explicitly requires sectors, return `ERROR`.

The status payload should include warning details:

```json
{
  "status": "partial_success",
  "warnings": ["VCI classification unavailable; reused previous snapshot"],
  "classificationSource": "STALE",
  "classificationUpdatedAt": "2026-07-01T00:00:00Z"
}
```

## EOD Job Selection

EOD job definitions reference canonical sector codes:

```json
{
  "sectorCodes": ["BANKING", "SECURITIES"],
  "includeDescendants": true
}
```

Java resolves sector codes and descendants to symbols, then publishes ordinary `topic-sync-stock-prices` messages.

The canonical storage layout remains:

```text
eod/{exchange}/{code}.parquet
```

Sector names or codes must not appear in the EOD object path.

## Implementation Steps

### Step 1 — Contract and database

- [ ] Add `includeSectorClassification`, `sectorTaxonomy`, and `sectorLevel` config keys.
- [ ] Add Flyway migration for `sector` and nullable `symbol.sector_id`.
- [ ] Implement idempotent Java sector seed using current constants.
- [ ] Define request/status models and canonical symbol fields.

### Step 2 — Java producer

- [ ] Load active mappings from PostgreSQL.
- [ ] Use seed/default mapping only when the sector table is empty.
- [ ] Add mapping metadata to the existing `SYNC_SYMBOLS` message.
- [ ] Add tests for enabled, disabled, and bootstrap configurations.

### Step 3 — Ingestor

- [ ] Make the Vietcap call conditional.
- [ ] Preserve raw ICB level 1–4 fields.
- [ ] Apply the Java-provided mapping.
- [ ] Preserve previous classification when enrichment is disabled or unavailable.
- [ ] Normalize once, then reuse the same DataFrame for Parquet and Kafka.
- [ ] Add response fixtures and merge/mapping tests.

### Step 4 — Java consumer

- [ ] Upsert source sector metadata.
- [ ] Preserve canonical codes and hierarchy.
- [ ] Update `symbol.sector_id` only with valid classification.
- [ ] Preserve existing sector relations on missing classification.
- [ ] Make snapshot upsert idempotent.

### Step 5 — EOD sector selection

- [ ] Query active symbols by canonical `sector.code`.
- [ ] Resolve child sectors when configured.
- [ ] Ensure sector selection affects scheduling only, not S3 paths.
- [ ] Add an integration test from symbol enrichment to sector-filtered EOD messages.

### Step 6 — Future UI

- [ ] Add `GET /api/sectors?active=true` with symbol counts.
- [ ] Use the endpoint to populate the job-definition sector selector.
- [ ] Store selected canonical sector codes in `job_definition.config_json`.
- [ ] Validate submitted codes against active database records.

## Acceptance Criteria

- A normal `SYNC_SYMBOLS` job works without calling Vietcap.
- Enabling enrichment adds raw ICB and canonical `sectorCode` to Parquet.
- The Kafka upsert payload and Parquet contain the same sector representation.
- PostgreSQL sector records originate from Java seed and can be updated with synced ICB metadata.
- Missing or failed optional enrichment never clears an existing symbol-sector relationship.
- EOD jobs can select symbols by canonical sector code.
- EOD paths remain `eod/{exchange}/{code}.parquet`.
- Tests cover bootstrap, enabled, disabled, partial failure, and idempotent reprocessing.
