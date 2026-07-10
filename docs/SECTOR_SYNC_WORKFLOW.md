# Sector Sync Workflow

## Purpose

Sector sync is an optional enrichment path inside the existing `SYNC_SYMBOLS` workflow. It adds canonical sector codes and raw ICB classification metadata to symbol master data while preserving normal symbol synchronization when sector enrichment is disabled or unavailable.

This workflow is intentionally not a separate job. Sector data travels with the symbol snapshot because the authoritative symbol-sector relation is `symbol.sector_id`.

## Components

```text
Platform / Java Core
  ├─ JobDefinition config
  ├─ SyncSymbolsJobProducer
  ├─ SectorRepository
  ├─ SymbolUpsertConsumer
  └─ SymbolRepository

Ingestor / Python
  ├─ app.handlers.symbols
  ├─ stock client factory
  ├─ VNDirect symbol client
  ├─ Vietcap/VCI sector client
  ├─ MinIO Parquet storage helpers
  └─ Kafka producer

Storage
  ├─ PostgreSQL sector table
  ├─ PostgreSQL symbol table
  └─ S3/MinIO symbols/{exchange}.parquet
```

## Topics

### Inbound to ingestor

The Java platform publishes symbol jobs to the configured sync-symbols topic.

Typical local/default topic name:

```text
topic-sync-symbols
```

Message key:

```text
{exchange}
```

### Outbound to Java platform

The ingestor publishes canonical symbol upsert batches.

Typical local/default topic name:

```text
topic-upsert-symbols
```

Message key:

```text
{exchange}
```

### Status topic

The ingestor publishes job status messages to the configured job-status topic.

Typical local/default topic name:

```text
topic-sync-job-status
```

## Job Configuration

Sector enrichment is opt-in.

### Enrichment disabled

```json
{
  "exchanges": ["HOSE"],
  "includeSectorClassification": false
}
```

Behavior:

- fetch VNDirect symbols;
- skip VCI sector call;
- preserve sector columns from existing Parquet snapshot when present;
- publish normal symbol upsert records;
- return `success`.

### Enrichment enabled

```json
{
  "exchanges": ["HOSE"],
  "includeSectorClassification": true,
  "sectorTaxonomy": "ICB",
  "sectorLevel": 3
}
```

Behavior:

- fetch VNDirect symbols;
- fetch VCI ICB classification;
- merge by symbol code;
- map source ICB code to Java-owned canonical sector code;
- write enriched Parquet;
- publish enriched symbol upsert records.

## Sync-Symbols Request Contract

The Java producer sends one message per exchange.

```json
{
  "jobId": "fece86d5-2a86-4a54-a9da-3b44f905ef87",
  "logId": "ff96a85f-91fb-42c8-a235-c923a730f20d",
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

### Metadata fields

| Field | Required | Owner | Description |
| --- | --- | --- | --- |
| `symbolCount` | No | Java | Current active symbol count used for reporting. |
| `includeSectorClassification` | No | Java | Enables VCI sector enrichment when `true`. Defaults to `false`. |
| `sectorTaxonomy` | Required when enrichment enabled | Java | Source taxonomy. Current default is `ICB`. |
| `sectorLevel` | Required when enrichment enabled | Java | Source taxonomy level used for canonical mapping. Current default is `3`. |
| `sectorMappings` | Required when enrichment enabled | Java | Active source-code to canonical-code mapping. |

### Mapping rules

- Java is the canonical mapping owner.
- Python applies the mapping provided in the message.
- Python must not maintain a separate canonical sector mapping table.
- Source sector names are metadata only; source sector codes are used for matching.

## Java Producer Behavior

`SyncSymbolsJobProducer`:

1. extracts configured exchanges, defaulting to `HOSE`, `HNX`, and `UPCOM`;
2. counts active symbols by exchange;
3. copies `job_definition.config_json` into message metadata;
4. sets `includeSectorClassification=false` when enrichment is disabled or absent;
5. when enrichment is enabled:
   - normalizes taxonomy to uppercase;
   - parses `sectorLevel`;
   - loads active mappings from PostgreSQL;
   - falls back to `SectorSeedConfig` only if the `sector` table is empty;
   - writes mappings to `metadata.sectorMappings`;
6. publishes a sync-symbols message keyed by exchange.

## Ingestor Processing Flow

```text
process_sync_symbols_message
  ├─ parse payload
  ├─ resolve stock client from payload.source
  ├─ fetch symbols from VNDirect
  ├─ validate required symbol fields
  ├─ read previous symbols/{exchange}.parquet
  ├─ optionally fetch VCI sectors
  ├─ normalize symbol DataFrame
  ├─ apply canonical sector mapping
  ├─ preserve previous classification when needed
  ├─ validate classification coverage
  ├─ write symbols/{exchange}.parquet
  ├─ publish symbol upsert batch
  └─ publish status
```

## Validation

Before replacing the Parquet snapshot, the ingestor validates:

- required fields exist:
  - `code`
  - `floor`
  - `status`
- `(floor, code)` is unique;
- fetched floor values include the requested exchange.

When enrichment is enabled and VCI data is available, the ingestor calculates:

| Metric | Meaning |
| --- | --- |
| `vndSymbols` | Number of VNDirect symbol rows. |
| `icbSymbols` | Number of VCI sector rows. |
| `matchedCount` | Number of symbol rows with source sector code. |
| `unmatchedCount` | Number of symbols without source sector code. |
| `matchPercentage` | `matchedCount / vndSymbols * 100`. |
| `mappedCanonicalCount` | Number of symbols mapped to canonical sector code. |

Current thresholds:

```text
warning < 98%
failure < 90%
```

If coverage is below `90%`, processing fails and the status becomes `error`.

## Canonical Parquet Schema

The ingestor writes one symbol snapshot per exchange:

```text
symbols/{exchange}.parquet
```

The path must be produced with:

```python
settings.get_symbols_path(exchange)
```

Example:

```text
symbols/hose.parquet
```

Sector-related columns:

```text
sectorCode
sectorTaxonomy
sectorLevel
sourceSectorCode
sourceSectorNameVi
sourceSectorNameEn
icbLv1Code
icbLv1NameVi
icbLv1NameEn
icbLv2Code
icbLv2NameVi
icbLv2NameEn
icbLv3Code
icbLv3NameVi
icbLv3NameEn
icbLv4Code
icbLv4NameVi
icbLv4NameEn
classificationUpdatedAt
```

Rules:

- Existing sector columns are preserved when enrichment is disabled.
- Existing sector columns are preserved when VCI fails and a previous snapshot exists.
- Missing ICB matches do not erase previous classifications for fallback paths.
- New paths must not include sector names, sector codes, dates, or run IDs.

## Symbol Upsert Event Contract

The ingestor publishes a canonical event to Java after writing Parquet.

```json
{
  "jobId": "fece86d5-2a86-4a54-a9da-3b44f905ef87",
  "logId": "ff96a85f-91fb-42c8-a235-c923a730f20d",
  "exchange": "HOSE",
  "expectedCount": 500,
  "actualCount": 500,
  "detectedAt": "2026-07-10T00:00:00Z",
  "symbols": [
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
      "classificationUpdatedAt": "2026-07-10T00:00:00Z",
      "meta": {}
    }
  ]
}
```

## Java Consumer Behavior

`SymbolUpsertConsumer` processes each record as follows:

1. If `sectorCode` is blank, use `null` sector ID.
2. If `sectorCode` is present:
   - resolve `sector.id` by canonical `sector.code`;
   - update the canonical sector row with source metadata when blank/present fields allow it.
3. Upsert the symbol row.
4. `SymbolRepository.upsertOne` updates `symbol.sector_id` only when the incoming sector ID is non-null.
5. Existing `symbol.sector_id` is preserved when incoming classification is missing or unknown.
6. After processing a non-empty batch, missing active symbols for the exchange are deactivated.

## Full Snapshot Safety Rule

Current Java consumer behavior assumes `topic-upsert-symbols` contains a full exchange symbol snapshot.

This is important because the consumer calls:

```text
deactivateMissing(exchange, incomingCodes)
```

Therefore:

- publish only full exchange symbol snapshots to `topic-upsert-symbols`;
- do not publish sector-filtered or partial subsets to this topic;
- before introducing partial upsert batches, add an explicit `fullSnapshot` or `snapshotScope` field and guard deactivation logic.

## Status Contract

### Success

```json
{
  "exchange": "HOSE",
  "status": "success",
  "recordsInserted": 500,
  "totalRecords": 500,
  "classificationSource": "FRESH"
}
```

### Partial success with stale classification

```json
{
  "exchange": "HOSE",
  "status": "partial_success",
  "recordsInserted": 500,
  "totalRecords": 500,
  "warnings": ["VCI classification unavailable; reused previous snapshot"],
  "classificationSource": "STALE"
}
```

### Error

```json
{
  "exchange": "HOSE",
  "status": "error",
  "errorMessage": "VCI classification unavailable and no previous symbol snapshot exists"
}
```

## Failure Matrix

| Scenario | Previous snapshot exists | Result |
| --- | --- | --- |
| Enrichment disabled | No | Sync symbols, no sector classification, `success`. |
| Enrichment disabled | Yes | Sync symbols, preserve previous classification, `success`. |
| Enrichment enabled, VCI succeeds | Either | Sync symbols, write fresh classification, `success` or `partial_success` if warnings exist. |
| Enrichment enabled, VCI fails | Yes | Sync symbols, preserve stale classification, `partial_success`. |
| Enrichment enabled, VCI fails | No | Do not write new snapshot, publish `error`. |
| Classification coverage below 98% | Either | Write snapshot if at least 90%, publish warning/`partial_success`. |
| Classification coverage below 90% | Either | Fail processing, publish `error`. |

## Operational Notes

- Use source codes, not names, for matching.
- Keep official ticker casing in metadata and user-facing surfaces.
- S3 object paths are lowercase-normalized by path builders.
- Do not hard-code `symbols/{exchange}.parquet`; use `settings.get_symbols_path(exchange)`.
- Do not add sector or industry folders to symbol or EOD paths.