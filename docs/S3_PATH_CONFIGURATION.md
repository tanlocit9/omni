# S3 Path Configuration

## Overview

The S3 path structure for the stock-data bucket is now centrally managed through `configs/shared/s3-paths.yaml`. This ensures consistent path naming across all services and makes the data lake structure explicit and maintainable.

## Configuration File

**Location:** `configs/shared/s3-paths.yaml`

This YAML file defines:

- The bucket name
- Path patterns for each data type
- Future expansion paths (documented but not yet implemented)

## Path Structure

### Currently Implemented

#### 1. Symbol Metadata (Exchange-level)

```
Pattern: symbols/{exchange}.parquet
Examples:
  - symbols/hose.parquet
  - symbols/hnx.parquet
  - symbols/upcom.parquet
```

#### 2. End-of-Day Price Data (Ticker-level)

```
Pattern: eod/{exchange}/{code}.parquet
Examples:
  - eod/hose/hpg.parquet
  - eod/hnx/shs.parquet
  - eod/upcom/vci.parquet
```

#### 3. Technical Indicators (Ticker-level, timeframe-scoped)

```
Pattern: indicators/{timeframe}/{exchange}/{code}.parquet
Examples:
  - indicators/1d/hose/hpg.parquet
  - indicators/1d/hnx/shs.parquet
  - indicators/1d/upcom/vci.parquet
```

Indicator paths are built from the shared path builder and are currently restricted to the enabled v1 timeframe set: `1d` only. The output file is replaced as a complete full-series calculation for the fixed v1 indicator set: `MA20`, `MA50`, `RSI`, and `MACD`.

### Future Expansion Paths

The configuration file includes placeholder patterns for future features:

- `intraday/` - Intraday price data
- `financials/` - Financial statements (income statement, balance sheet, cash flow)
- `fundamentals/` - Financial ratios and fundamental metrics
- `corporate-actions/` - Corporate events and announcements
- `ownership/` - Shareholder and ownership data
- `news/` - News articles and market commentary
- `macro/` - Macroeconomic indicators
- `derivatives/` - Derivatives market data
- `warrants/` - Covered warrants data
- `etf/` - ETF holdings and performance

## Naming Conventions

### Enforced Rules

1. **Lowercase normalization**: All exchange names and ticker codes are converted to lowercase in paths

   - Exchange: `HOSE` → `hose`, `HNX` → `hnx`, `UPCOM` → `upcom`
   - Ticker: `HPG` → `hpg`, `FPT` → `fpt`, `VCI` → `vci`

2. **Folder names**: Use kebab-case

   - `corporate-actions/`
   - `income-statement.parquet`

3. **No temporal partitioning**: Files are overwritten or merged in place

   - ❌ No `dt=2024-01-01/` folders
   - ❌ No `run_id=abc123/` folders
   - ✅ Direct file paths: `eod/hose/hpg.parquet`

4. **Indicator timeframe validation**: Indicator files use canonical timeframe values and reject unsupported intervals. In v1, only `1d` is enabled for `indicators/{timeframe}/{exchange}/{code}.parquet`.

5. **One ticker = one file**: Each ticker has a single Parquet file per data type and timeframe

6. **Metadata separation**: Sector, industry, and other classification metadata is stored in separate metadata files, not encoded in the path structure

## Usage in Code

### Python (Ingestor)

The `Settings` class in `apps/ingestor/app/settings.py` provides path builder methods:

```python
from app.settings import settings

# Get symbol metadata path
path = settings.get_symbols_path("HOSE")
# Returns: "symbols/hose.parquet"

# Get EOD price data path
path = settings.get_eod_path("HOSE", "HPG")
# Returns: "eod/hose/hpg.parquet"
```

**Key features:**

- Automatic lowercase normalization
- Rejects empty or whitespace-only exchange/code path parts
- Validates indicator timeframes through the shared canonical timeframe rule
- Reads patterns from `s3-paths.yaml`
- Falls back to sensible defaults if config is missing

### Handler Usage

#### Stock Prices Handler (`stock_prices.py`)

```python
# Old (hard-coded prefix)
object_name = f"{settings.eod_prefix}{symbol_key}.parquet"

# New (path builder with normalization)
exchange, code = symbol_key.split("-", 1)
object_name = settings.get_eod_path(exchange, code)
```

#### Symbols Handler (`symbols.py`)

```python
# Old (hard-coded prefix)
object_name = f"{settings.symbols_prefix}{exchange}.parquet"

# New (path builder with normalization)
object_name = settings.get_symbols_path(exchange)
```

#### Indicators Handler (`apps/analyzer/app/indicators/handler.py`)

```python
exchange, code = message.parse_symbol_key()
eod_path = settings.stock_data_paths.eod(exchange, code)
indicators_path = settings.stock_data_paths.indicators("1d", exchange, code)
```

Analyzer reads `eod/{exchange}/{code}.parquet`, calculates the full supported v1 indicator set, and writes `indicators/1d/{exchange}/{code}.parquet` through the shared `ParquetStorage` abstraction.

## Migration Notes

### Breaking Changes

**Path structure change:**

- Old: `EOD/HOSE-HPG.parquet` (uppercase, hyphenated)
- New: `eod/hose/hpg.parquet` (lowercase, folder structure)

**For existing deployments:**

1. The old `EOD/` and `SYMBOLS/` prefixes are no longer used
2. Data needs to be migrated to the new path structure
3. Or maintain backward compatibility by setting `objectName` override in Kafka messages

### Backward Compatibility

The handlers support the `metadata.objectName` override field in Kafka messages:

```json
{
  "symbolKey": "HOSE-HPG",
  "metadata": {
    "objectName": "EOD/HOSE-HPG.parquet"
  }
}
```

When `objectName` is provided, the path builder is bypassed, allowing gradual migration.

## Benefits

### 1. Single Source of Truth

All S3 paths defined in one location (`s3-paths.yaml`), not scattered across multiple files.

### 2. Convention Enforcement

Lowercase and kebab-case rules are baked into the configuration and enforced by the path builders.

### 3. Cloud Portability

Path patterns work identically across:

- MinIO (local development)
- AWS S3
- Google Cloud Storage
- Cloudflare R2
- Oracle Object Storage

### 4. Documentation

Developers can see the complete data lake structure at a glance without reading code.

### 5. Refactor Safety

Changing path structure only requires updating YAML, not hunting down string literals in code.

### 6. Future-Ready

Placeholder paths for upcoming features are already defined, reducing planning overhead.

## Architecture Decisions

### Why Pattern-Based Configuration?

Instead of hard-coding paths or using simple prefixes, we use configurable patterns with placeholders:

```yaml
eod:
  base: 'eod/'
  pattern: '{exchange}/{code}.parquet'
```

**Advantages:**

- Flexible: Can change structure without code changes
- Explicit: Pattern shows exactly what variables are needed
- Extensible: Easy to add new path types
- Testable: Path construction logic is centralized

### Why Lowercase Normalization?

Stock exchanges and vendors use uppercase ticker symbols (HPG, FPT, VCI), but filesystem conventions favor lowercase:

**Reasons for lowercase:**

1. **Case-sensitive storage**: S3 and most object stores are case-sensitive. Lowercase eliminates ambiguity.
2. **URL safety**: Lowercase paths work better in URLs and APIs
3. **Convention**: Most data lakes use lowercase (Hive, Iceberg, Delta Lake)
4. **Consistency**: One canonical representation reduces errors

**Trade-off:** We preserve uppercase in metadata and database while using lowercase in paths.

## Testing

Run the ingestor test suite to verify path construction:

```bash
nx test ingestor
```

The existing tests pass with the new path structure, confirming backward compatibility where overrides are used.

## Related Files

- `configs/shared/s3-paths.yaml` - Path configuration
- `configs/shared/topics.yaml` - Kafka topics and MinIO credentials
- `apps/ingestor/app/settings.py` - Settings and path builders
- `apps/ingestor/app/handlers/stock_prices.py` - EOD data handler
- `apps/ingestor/app/handlers/symbols.py` - Symbol metadata handler
- `apps/core/src/main/java/.../SyncStockPriceJobProducer.java` - Removed bucket/objectName metadata overrides
