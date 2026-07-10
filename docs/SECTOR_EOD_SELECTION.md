# Sector EOD Selection

## Purpose

Sector EOD selection lets stock-price sync jobs choose their symbol universe by canonical sector code while keeping the data lake layout stable.

Sector selection is a scheduling/filtering concern only. It must not change object paths, Kafka storage metadata, or Parquet file organization.

## Core Rule

```text
Sector metadata controls which symbols are selected.
Sector metadata never controls where EOD files are stored.
```

EOD files remain one file per ticker under the configured EOD path pattern:

```text
eod/{exchange}/{code}.parquet
```

Examples:

```text
eod/hose/hpg.parquet
eod/hose/vcb.parquet
eod/hnx/shs.parquet
```

Use the path builder:

```python
settings.get_eod_path(exchange, code)
```

Do not construct EOD paths with string concatenation.

## Dependencies

Sector EOD selection depends on the symbol-sector relation produced by the symbol sync workflow.

```text
SYNC_SYMBOLS with sector enrichment
  └─ writes canonical sectorCode and raw ICB metadata
       └─ Java upsert resolves sectorCode to sector.id
            └─ symbol.sector_id becomes available for EOD filtering
```

Authoritative tables:

```text
sector.id
sector.code
sector.parent_id
symbol.sector_id
symbol.code
symbol.exchange
symbol.is_active
```

The authoritative field for filtering is `symbol.sector_id`, resolved through canonical `sector.code`.

## Job Configuration

EOD job definitions should reference canonical sector codes.

```json
{
  "sectorCodes": ["BANKING", "SECURITIES"],
  "includeDescendants": true
}
```

### Fields

| Field | Required | Description |
| --- | --- | --- |
| `sectorCodes` | No | Canonical Java-owned sector codes used to select symbols. |
| `includeDescendants` | No | When `true`, child sectors under selected sectors should also be included. |

If `sectorCodes` is absent or empty, the job should use the normal non-sector-filtered EOD universe.

## Selection Flow

```text
EOD scheduler / producer
  ├─ reads job_definition.config_json
  ├─ extracts sectorCodes
  ├─ validates selected sector codes against active sector records
  ├─ optionally resolves descendants
  ├─ queries active symbols by sector code
  └─ publishes ordinary stock-price sync messages
       │
       ▼
Ingestor stock price handler
  ├─ receives normal symbolKey/exchange/code payload
  ├─ fetches EOD prices
  ├─ writes eod/{exchange}/{code}.parquet
  └─ publishes normal job status
```

Sector selection does not require a new EOD Kafka topic.

## Current Repository Support

`SymbolRepository.findBySectors` supports active symbol lookup by canonical sector code.

Current behavior:

```sql
SELECT s.code, s.exchange
FROM symbol s
LEFT JOIN sector sec ON sec.id = s.sector_id
WHERE s.is_active = true
  AND (:sectors IS NULL OR sec.code = ANY(:sectors))
```

Implications:

- filtering is based on canonical `sector.code`;
- only active symbols are selected;
- descendant traversal is not implemented in this query yet;
- when `sectors` is null, all active symbols are returned.

## Descendant Selection

The sector table supports hierarchy through:

```text
sector.parent_id
```

If `includeDescendants=true`, Java should expand selected sector codes before querying symbols.

Example:

```text
FINANCIALS
  ├─ BANKING
  └─ SECURITIES
```

Config:

```json
{
  "sectorCodes": ["FINANCIALS"],
  "includeDescendants": true
}
```

Resolved query sector codes:

```json
["FINANCIALS", "BANKING", "SECURITIES"]
```

Current gap:

- descendant expansion is a planned improvement;
- current `findBySectors` only matches the provided sector codes.

Recommended implementation options:

1. recursive SQL CTE over `sector.parent_id`;
2. Java-side tree expansion from active sector records;
3. repository method dedicated to descendant expansion.

## Kafka Message Rule

After symbols are selected, stock-price sync messages remain ordinary EOD messages.

They should identify the symbol/exchange and let the ingestor derive S3 paths from path builders.

Do not add sector path metadata to Kafka messages.

Do not add:

```json
{
  "bucket": "...",
  "objectName": "eod/banking/hose/vcb.parquet"
}
```

Correct behavior:

```json
{
  "symbol": "VCB",
  "exchange": "HOSE",
  "metadata": {
    "fromDate": "2020-01-01",
    "toDate": "2026-07-10"
  }
}
```

The ingestor derives:

```text
settings.get_eod_path("HOSE", "VCB")
```

## Storage Rules

Allowed:

```text
eod/hose/vcb.parquet
eod/hnx/shs.parquet
eod/upcom/abc.parquet
```

Forbidden:

```text
eod/banking/hose/vcb.parquet
eod/ICB/BANKING/VCB.parquet
eod/hose/banking/vcb.parquet
eod/sector=BANKING/exchange=HOSE/code=VCB.parquet
eod/hose/vcb/dt=2026-07-10.parquet
```

Reasons:

- sector, industry, and exchange are metadata;
- one ticker owns one complete EOD file;
- paths must remain stable even if sector classifications change;
- sector reclassification must not move historical EOD objects.

## Snapshot Safety

Sector-filtered EOD jobs must not publish filtered symbol batches to `topic-upsert-symbols`.

`topic-upsert-symbols` is currently treated as a full exchange symbol snapshot by Java. The consumer deactivates active symbols missing from the incoming batch.

Therefore:

- use sector filtering only for EOD price sync message generation;
- do not reuse symbol upsert events for sector-filtered subsets;
- do not send selected EOD symbols through the symbol upsert consumer;
- add a `fullSnapshot` or `snapshotScope` guard before introducing partial symbol upsert batches.

## Validation Rules

Before publishing sector-filtered EOD messages, Java should validate:

1. each configured `sectorCode` exists;
2. each selected sector is active;
3. descendant resolution, if requested, returns active sectors only;
4. the resolved symbol universe is non-empty unless empty jobs are allowed;
5. selected symbols are active;
6. selected symbols have valid `code` and `exchange`.

Recommended behavior for invalid sector codes:

```text
Fail the job definition execution before publishing EOD messages.
```

Recommended behavior for empty symbol universe:

```text
Return success with zero published messages only if explicitly allowed;
otherwise return a warning or failed validation status.
```

## Example

### Input job config

```json
{
  "source": "VND",
  "jobType": "SYNC_STOCK_PRICE",
  "config": {
    "sectorCodes": ["BANKING"],
    "includeDescendants": false,
    "fromDate": "2024-01-01"
  }
}
```

### Symbol resolution

```text
sector.code = BANKING
  └─ symbol.sector_id = sector.id
       ├─ VCB / HOSE
       ├─ CTG / HOSE
       ├─ BID / HOSE
       └─ MBB / HOSE
```

### Published EOD messages

```json
[
  {
    "symbol": "VCB",
    "exchange": "HOSE",
    "metadata": {
      "fromDate": "2024-01-01"
    }
  },
  {
    "symbol": "CTG",
    "exchange": "HOSE",
    "metadata": {
      "fromDate": "2024-01-01"
    }
  }
]
```

### Resulting EOD paths

```text
eod/hose/vcb.parquet
eod/hose/ctg.parquet
```

## Operational Notes

- Run a sector-enriched `SYNC_SYMBOLS` job before relying on sector-filtered EOD jobs.
- If a symbol has no `sector_id`, it will not be selected by sector-specific jobs.
- If enrichment is disabled for later symbol syncs, existing sector relations are preserved.
- If VCI fails and a previous snapshot exists, stale classification may still drive EOD selection.
- Reclassifications update metadata and database relations but must not move EOD Parquet files.

## Remaining Work

- Add tests for sector-code based symbol selection.
- Add tests proving EOD paths do not include sector metadata.
- Implement descendant expansion if `includeDescendants=true` is required.
- Add validation for configured sector codes in stock-price job definitions.
- Add integration coverage from sector-enriched symbol sync to sector-filtered EOD message publication.