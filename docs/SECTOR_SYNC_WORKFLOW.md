# Sector Sync Workflow

Sector sync is an optional enrichment path inside the existing `SYNC_SYMBOLS` workflow. It adds canonical sector codes and raw ICB classification metadata to symbol master data while preserving normal symbol synchronization when sector enrichment is disabled or unavailable.

This workflow is intentionally not a separate job. Sector data travels with the symbol snapshot because the authoritative symbol-sector relation is `symbol.sector_id`.

## 1. Components and Ownership

| Component | Responsibility |
| --- | --- |
| Platform / Java Core | Owns `JobDefinition` config, scheduler execution tracking, `SyncSymbolsJobProducer`, canonical sector mappings, `SymbolUpsertConsumer`, and symbol persistence. |
| Ingestor / Python | Consumes sync-symbols requests, fetches symbol data, optionally enriches sectors, writes symbol Parquet snapshots, publishes full symbol snapshots, and publishes status. |
| PostgreSQL | Stores Platform-owned sectors, symbols, job definitions, and execution history. |
| S3/MinIO | Stores `symbols/{exchange}.parquet`. |
| Kafka | Carries sync-symbols requests, symbol-upsert snapshots, and job-status events. |

## 2. Canonical Identifiers

| Field | Meaning | Scope |
| --- | --- | --- |
| `jobDefinitionId` | Scheduled job configuration ID | Shared by all executions of one job definition |
| `parentExecutionId` | Scheduler-run execution ID | Present when Platform creates a parent scheduler execution for exchange fan-out |
| `executionId` | Individual exchange-task execution ID | Unique per dispatched exchange task |

Legacy aliases are compatibility only:

| Legacy field | Canonical field |
| --- | --- |
| `jobId` | `jobDefinitionId` |
| `logId` | `executionId` |

## 3. Topics

| Topic | Direction | Kafka key | Payload |
| --- | --- | --- | --- |
| `topic-sync-symbols` | Platform -> Ingestor | `exchange` | Symbol-master sync request |
| `topic-upsert-symbols` | Ingestor -> Platform | `exchange` | Full symbol snapshot for one exchange |
| `topic-sync-job-status` | Ingestor -> Platform | `exchange` for symbol-sync tasks | Job status |

## 4. Job Configuration

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
- publish successful status if processing succeeds.

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
- publish enriched symbol upsert records;
- publish status with the identifiers echoed from the request.

## 5. Sync-Symbols Request Contract

Topic: `topic-sync-symbols`

Producer: Platform `SyncSymbolsJobProducer`

Consumer: Ingestor `process_symbols_message`

Kafka key: `exchange`

The Java producer sends one message per exchange.

```json
{
  "jobDefinitionId": "11111111-1111-4111-8111-111111111111",
  "executionId": "44444444-4444-4444-8444-444444444444",
  "parentExecutionId": "33333333-3333-4333-8333-333333333333",
  "source": "VND",
  "exchange": "HOSE",
  "timestamp": "2026-07-12T12:00:00Z",
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

Field notes:

| Field | Required | Owner | Description |
| --- | --- | --- | --- |
| `jobDefinitionId` | Yes | Platform | Scheduled job definition ID. |
| `executionId` | Yes | Platform | Exchange task execution ID. |
| `parentExecutionId` | Optional but expected for scheduled fan-out | Platform | Parent scheduler-run execution ID shared by exchange tasks. |
| `source` | Yes | Platform | Symbol source identifier. |
| `exchange` | Yes | Platform | Exchange to synchronize. |
| `timestamp` | Yes | Platform | Scheduler timestamp. This is the implemented field name; do not use `detectedAt` for the request. |
| `metadata.symbolCount` | No | Platform | Current active symbol count used for reporting. |
| `metadata.includeSectorClassification` | No | Platform | Enables VCI sector enrichment when `true`. Defaults to `false`. |
| `metadata.sectorTaxonomy` | Required when enrichment enabled | Platform | Source taxonomy. Current default is `ICB`. |
| `metadata.sectorLevel` | Required when enrichment enabled | Platform | Source taxonomy level used for canonical mapping. Current default is `3`. |
| `metadata.sectorMappings` | Required when enrichment enabled | Platform | Active source-code to canonical-code mapping. |

## 6. Mapping Rules

- Java is the canonical mapping owner.
- Python applies the mapping provided in the message.
- Python must not maintain a separate canonical sector mapping table.
- Source sector names are metadata only; source sector codes are used for matching.

## 7. Java Producer Behavior

`SyncSymbolsJobProducer`:

1. extracts configured exchanges, defaulting to `HOSE`, `HNX`, and `UPCOM`;
2. creates exchange-level execution tracking and includes `executionId`;
3. includes `parentExecutionId` when the scheduler created a parent execution for the run;
4. counts active symbols by exchange;
5. copies `job_definition.config_json` into message metadata;
6. sets `includeSectorClassification=false` when enrichment is disabled or absent;
7. when enrichment is enabled:
   - normalizes taxonomy to uppercase;
   - parses `sectorLevel`;
   - loads active mappings from PostgreSQL;
   - falls back to seeded sector config only if the `sector` table is empty;
   - writes mappings to `metadata.sectorMappings`;
8. publishes a sync-symbols message keyed by exchange.

## 8. Ingestor Processing Flow

```text
process_symbols_message
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
  ├─ publish full symbol upsert batch
  └─ publish status
```

## 9. Validation

Before replacing the Parquet snapshot, Ingestor validates:

- required fields exist:
  - `code`
  - `floor`
  - `status`
- `(floor, code)` is unique;
- fetched floor values include the requested exchange.

When enrichment is enabled and VCI data is available, Ingestor calculates:

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

If coverage is below `90%`, processing fails and the status becomes `ERROR`.

## 10. Canonical Parquet Schema

Ingestor writes one symbol snapshot per exchange:

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

## 11. Symbol-Upsert Event Contract

Topic: `topic-upsert-symbols`

Producer: Ingestor `process_symbols_message`

Consumer: Platform `SymbolUpsertConsumer`

Kafka key: `exchange`

The event is a full exchange snapshot. Do not publish sector-filtered or partial subsets to this topic.

```json
{
  "jobDefinitionId": "11111111-1111-4111-8111-111111111111",
  "executionId": "44444444-4444-4444-8444-444444444444",
  "parentExecutionId": "33333333-3333-4333-8333-333333333333",
  "exchange": "HOSE",
  "expectedCount": 500,
  "actualCount": 500,
  "timestamp": "2026-07-12T12:00:00Z",
  "symbols": [
    {
      "code": "VCB",
      "exchange": "HOSE",
      "type": "STOCK",
      "status": "LISTED",
      "isin": "VN000000VCB4",
      "companyId": "VCB",
      "companyName": "Joint Stock Commercial Bank for Foreign Trade of Vietnam",
      "listedDate": "2009-06-30",
      "sectorCode": "BANKING",
      "sectorTaxonomy": "ICB",
      "sectorLevel": 3,
      "sourceSectorCode": "8350",
      "sourceSectorNameVi": "Ngân hàng",
      "sourceSectorNameEn": "Banks",
      "icbLv1Code": "8000",
      "icbLv1NameVi": "Tài chính",
      "icbLv1NameEn": "Financials",
      "icbLv2Code": "8300",
      "icbLv2NameVi": "Ngân hàng",
      "icbLv2NameEn": "Banks",
      "icbLv3Code": "8350",
      "icbLv3NameVi": "Ngân hàng",
      "icbLv3NameEn": "Banks",
      "icbLv4Code": "8357",
      "icbLv4NameVi": "Ngân hàng thương mại",
      "icbLv4NameEn": "Commercial Banks",
      "classificationUpdatedAt": "2026-07-12T12:00:00Z",
      "meta": {}
    }
  ]
}
```

## 12. Java Consumer Behavior

`SymbolUpsertConsumer` processes each record as follows:

1. If `sectorCode` is blank, use `null` sector ID.
2. If `sectorCode` is present:
   - resolve `sector.id` by canonical `sector.code`;
   - update the canonical sector row with source metadata when blank/present fields allow it.
3. Upsert the symbol row.
4. `SymbolRepository.upsertOne` updates `symbol.sector_id` only when the incoming sector ID is non-null.
5. Existing `symbol.sector_id` is preserved when incoming classification is missing or unknown.
6. After processing a non-empty batch, missing active symbols for the exchange are deactivated.

## 13. Full Snapshot Safety Rule

Current Java consumer behavior assumes `topic-upsert-symbols` contains a full exchange symbol snapshot.

This is important because the consumer calls:

```text
deactivateMissing(exchange, incomingCodes)
```

Therefore:

- publish only full exchange symbol snapshots to `topic-upsert-symbols`;
- do not publish sector-filtered or partial subsets to this topic;
- before introducing partial upsert batches, add an explicit `fullSnapshot` or `snapshotScope` field and guard deactivation logic.

## 14. Status Contract

Topic: `topic-sync-job-status`

Producer: Ingestor `build_status`

Consumer: Platform `JobStatusConsumer`

Kafka key: exchange identifier for symbol-sync tasks.

### Success

```json
{
  "symbolKey": "HOSE",
  "jobDefinitionId": "11111111-1111-4111-8111-111111111111",
  "executionId": "44444444-4444-4444-8444-444444444444",
  "parentExecutionId": "33333333-3333-4333-8333-333333333333",
  "status": "SUCCESS",
  "recordsInserted": 500,
  "totalRecords": 500,
  "newOffset": "2026-07-12T12:00:00Z",
  "startedAt": "2026-07-12T12:00:01.000000+00:00",
  "finishedAt": "2026-07-12T12:00:08.000000+00:00",
  "durationMs": 7000,
  "errorMessage": null,
  "warnings": []
}
```

### Partial success with stale classification

If the current implementation emits partial success, document it as a non-canonical status extension. Platform status handling should be checked before relying on any value beyond `SUCCESS` and `ERROR`.

```json
{
  "symbolKey": "HOSE",
  "jobDefinitionId": "11111111-1111-4111-8111-111111111111",
  "executionId": "44444444-4444-4444-8444-444444444444",
  "parentExecutionId": "33333333-3333-4333-8333-333333333333",
  "status": "SUCCESS",
  "recordsInserted": 500,
  "totalRecords": 500,
  "newOffset": "2026-07-12T12:00:00Z",
  "startedAt": "2026-07-12T12:00:01.000000+00:00",
  "finishedAt": "2026-07-12T12:00:08.000000+00:00",
  "durationMs": 7000,
  "errorMessage": null,
  "warnings": ["VCI classification unavailable; reused previous snapshot"]
}
```

### Error

```json
{
  "symbolKey": "HOSE",
  "jobDefinitionId": "11111111-1111-4111-8111-111111111111",
  "executionId": "44444444-4444-4444-8444-444444444444",
  "parentExecutionId": "33333333-3333-4333-8333-333333333333",
  "status": "ERROR",
  "recordsInserted": 0,
  "totalRecords": 0,
  "newOffset": null,
  "startedAt": "2026-07-12T12:00:01.000000+00:00",
  "finishedAt": "2026-07-12T12:00:03.000000+00:00",
  "durationMs": 2000,
  "errorMessage": "VCI classification unavailable and no previous symbol snapshot exists"
}
```

## 15. Failure Matrix

| Scenario | Previous snapshot exists | Result |
| --- | --- | --- |
| Enrichment disabled | No | Sync symbols, no sector classification, `SUCCESS`. |
| Enrichment disabled | Yes | Sync symbols, preserve previous classification, `SUCCESS`. |
| Enrichment enabled, VCI succeeds | Either | Sync symbols, write fresh classification, `SUCCESS`; include warnings if coverage is degraded but accepted. |
| Enrichment enabled, VCI fails | Yes | Sync symbols, preserve stale classification, `SUCCESS` with warning if implemented by the handler. |
| Enrichment enabled, VCI fails | No | Do not write new snapshot, publish `ERROR`. |
| Classification coverage below 98% | Either | Write snapshot if at least 90%, publish warning when implemented. |
| Classification coverage below 90% | Either | Fail processing, publish `ERROR`. |

## 16. Backward Compatibility

Current Ingestor status-building code still accepts legacy request aliases:

| Accepted alias | Canonical field |
| --- | --- |
| `jobId` | `jobDefinitionId` |
| `logId` | `executionId` |

Do not use these aliases in new examples or new producers. They are compatibility only, not the canonical contract.

## 17. Operational Notes

- Use source codes, not names, for matching.
- Keep official ticker casing in metadata and user-facing surfaces.
- S3 object paths are lowercase-normalized by path builders.
- Do not hard-code `symbols/{exchange}.parquet`; use `settings.get_symbols_path(exchange)`.
- Do not add sector or industry folders to symbol or EOD paths.
- `topic-upsert-symbols` remains a full-snapshot topic until the Java consumer deactivation behavior is explicitly guarded for partial batches.