# Stock Analyzer Service (`apps/analyzer`)

The **Stock Analyzer Service** is a FastAPI application and Kafka worker for analytical APIs, technical-indicator calculation, and Market Signal V1 calculation.

Analyzer no longer owns stock-price persistence. It does **not** connect directly to PostgreSQL, does **not** read stock prices from PostgreSQL, and does **not** write synchronized stock prices back to PostgreSQL. Stock synchronization is owned by the Platform scheduler and downstream Ingestor flow.

Analyzer does own indicator calculation jobs from `topic-sync-indicators`: it reads EOD Parquet data from MinIO/S3, computes the complete supported indicator set, writes indicator Parquet output, and publishes job status back to Platform. Analyzer also owns Market Signal V1 jobs from `topic-sync-signals`: it reads EOD and indicator Parquet data, writes signal-state Parquet files next to indicators, and publishes only transition metadata back to Platform. It also exposes direct synchronous APIs for callers that need to invoke the same calculation contracts without Kafka.

---

## Current Responsibilities

- **FastAPI API surface**: Provides versioned HTTP endpoints under `/v1`.
- **Compatibility stock endpoints**: Existing stock endpoints remain available, but they report that direct PostgreSQL access has been removed.
- **No direct database access**: Analyzer has no SQLAlchemy engine, PostgreSQL session, repository, generated DB models, or database migration coupling.
- **Indicator Kafka worker**: Consumes Platform-owned `topic-sync-indicators` jobs and publishes status to `topic-sync-job-status`.
- **Signal Kafka worker**: Consumes Platform-owned `topic-sync-signals` jobs, writes signal-state Parquet files, and publishes transition metadata to `topic-sync-job-status`.
- **MinIO/S3 analytical storage**: Reads EOD files and writes indicator and signal files through shared `ParquetStorage`.
- **Infrastructure foundations**:
  - `KafkaEventPublisher` can publish JSON events to Kafka.
  - Shared storage adapters can read and write object-storage data.
- **Sync delegation**: On-demand stock sync should be triggered through the Platform scheduler API instead of Analyzer.

---

## Package Layout

```text
apps/analyzer/
├── app/
│   ├── adapters/
│   │   ├── kafka_publisher.py         # Kafka JSON publisher foundation
│   │   └── minio_storage.py           # MinIO read-only object storage foundation
│   ├── controllers/
│   │   └── v1/
│   │       └── stock.py               # API router for stock compatibility endpoints
│   ├── core/
│   │   └── __init__.py
│   ├── dtos/
│   │   └── stock/
│   ├── ports/
│   │   ├── event_publisher.py         # Publisher boundary
│   │   └── object_storage.py          # Object-storage boundary
│   ├── providers/
│   │   └── stock_provider.py          # FastAPI dependency provider
│   ├── calculations/
│   │   └── indicators.py              # MA20, MA50, RSI, MACD calculations
│   ├── indicators/
│   │   ├── handler.py                 # Reads EOD Parquet and writes indicator Parquet
│   │   ├── kafka.py                   # Kafka consumer/producer lifecycle
│   │   └── messages.py                # Indicator job/status contracts
│   ├── signals/
│   │   ├── handler.py                 # Reads EOD/indicator Parquet and writes signal state
│   │   ├── kafka.py                   # Signal Kafka consumer/status publisher lifecycle
│   │   ├── messages.py                # Signal job contract
│   │   ├── storage.py                 # Baseline and transition persistence
│   │   └── strategy.py                # TREND_MOMENTUM_V1 scoring rules
│   ├── services/
│   │   └── stock_service.py           # Stock compatibility behavior
│   └── settings.py                    # Shared topic/S3 config loader and runtime settings
├── tests/
├── main.py                            # FastAPI application entry point
├── project.json                       # Nx targets
├── pyproject.toml                     # Python dependencies and tooling config
└── uv.lock                            # Locked exact package versions
```

---

## API Endpoints

All endpoints are prefixed with `/v1`.

### 1. Stock Price History Compatibility Endpoint

Reports that Analyzer no longer reads stock prices directly from PostgreSQL.

- **URL**: `GET /v1/stocks/`
- **Query Parameters**:
  - `symbol` (string, required): Ticker symbol, for example `STB`.

```bash
curl "http://localhost:8000/v1/stocks/?symbol=STB"
```

### 2. Stock Sync Compatibility Endpoint

Reports that Analyzer no longer writes stock prices directly to PostgreSQL and points callers to the Platform scheduler API.

- **URL**: `POST /v1/stocks/sync`
- **Query Parameters**:
  - `symbol` (string, required): Ticker symbol, for example `STB`.

```bash
curl -X POST "http://localhost:8000/v1/stocks/sync?symbol=STB"
```

Current response semantics:

- `accepted: false`
- message explains that stock sync must be triggered through Platform-owned scheduling.

### 3. Direct Indicator Sync Endpoint

Synchronously calculates indicators for one symbol using the same JSON payload contract as `topic-sync-indicators` Kafka jobs. The endpoint reads EOD Parquet data from MinIO/S3, writes indicator Parquet output, and returns `recordsProcessed` directly. It does not publish a job-status Kafka message.

- **URL**: `POST /v1/indicators/sync`
- **Body**: `IndicatorJobMessage` JSON payload, including `jobDefinitionId`, `executionId`, `source`, `indicatorSource`, `symbolKey`, `timeframe`, and the complete supported `indicators` set.

```bash
curl -X POST "http://localhost:8000/v1/indicators/sync" \
  -H "Content-Type: application/json" \
  -d '{
    "jobDefinitionId": "job-definition-id",
    "executionId": "execution-id",
    "parentExecutionId": null,
    "source": "ANALYZER",
    "indicatorSource": "ad_close",
    "symbolKey": "HOSE-HPG",
    "timeframe": "1d",
    "indicators": ["MA20", "MA50", "RSI14", "MACD"],
    "metadata": {}
  }'
```

Example response:

```json
{
  "accepted": true,
  "symbolKey": "HOSE-HPG",
  "indicatorSource": "ad_close",
  "timeframe": "1d",
  "recordsProcessed": 60
}
```

### 4. Direct Market Signal Sync Endpoint

Synchronously calculates Market Signal V1 for one symbol using the same JSON payload contract as `topic-sync-signals` Kafka jobs. The endpoint reads EOD and indicator Parquet data from MinIO/S3, writes a signal-state Parquet file, and returns transition details directly. It does not publish a job-status Kafka message.

- **URL**: `POST /v1/signals/sync`
- **Body**: `SignalJobMessage` JSON payload, including `jobDefinitionId`, `executionId`, optional `parentExecutionId`, `source`, `symbolKey`, `timeframe`, `strategy`, and `metadata`.

```bash
curl -X POST "http://localhost:8000/v1/signals/sync" \
  -H "Content-Type: application/json" \
  -d '{
    "jobDefinitionId": "job-definition-id",
    "executionId": "execution-id",
    "parentExecutionId": null,
    "source": "ANALYZER",
    "symbolKey": "HOSE-HPG",
    "timeframe": "1d",
    "strategy": "TREND_MOMENTUM_V1",
    "metadata": {}
  }'
```

Example response:

```json
{
  "accepted": true,
  "symbolKey": "HOSE-HPG",
  "strategy": "TREND_MOMENTUM_V1",
  "timeframe": "1d",
  "signalChanged": true,
  "previousSignal": "NEUTRAL",
  "newSignal": "BULLISH"
}
```

---

## Shared Configuration

`app/settings.py` loads defaults from shared repository configuration without adding a YAML runtime dependency:

- `configs/shared/topics.yaml`
- `configs/shared/s3-paths.yaml`

### Environment and shared settings

Analyzer loads stable defaults from shared YAML config and runtime overrides from the root `.env` file. For service-only overrides, copy `apps/analyzer/.env.example` to `apps/analyzer/.env`; Docker Compose loads the root `.env` first and the app-specific file second.

Use flat env names only. They are shared by Java, Python, and Docker Compose, and Python maps them into typed `settings.kafka` and `settings.minio` objects.

### Kafka settings

| Settings field | Environment variable | Default source |
| -------------- | -------------------- | -------------- |
| `settings.kafka.bootstrap_servers` | `KAFKA_BOOTSTRAP_SERVERS` | `configs/shared/topics.yaml` |
| `settings.topic_sync_stock_prices` | `SYNC_STOCK_PRICES_TOPIC` | `kafka.topics.topic-sync-stock-prices` |
| `settings.topic_sync_symbols` | `SYNC_SYMBOLS_TOPIC` | `kafka.topics.topic-sync-symbols` |
| `settings.topic_sync_indicators` | `SYNC_INDICATORS_TOPIC` | `kafka.topics.topic-sync-indicators` |
| `settings.topic_sync_signals` | `SYNC_SIGNALS_TOPIC` | `kafka.topics.topic-sync-signals` |
| `settings.topic_upsert_symbols` | `UPSERT_SYMBOLS_TOPIC` | `kafka.topics.topic-upsert-symbols` |
| `settings.topic_sync_job_status` | `JOB_STATUS_TOPIC` | `kafka.topics.topic-sync-job-status` |

### MinIO settings

| Settings field | Environment variable | Default source |
| -------------- | -------------------- | -------------- |
| `settings.minio.endpoint` | `MINIO_ENDPOINT` | shared S3 defaults |
| `settings.minio.access_key` | `MINIO_ACCESS_KEY` | shared S3 defaults |
| `settings.minio.secret_key` | `MINIO_SECRET_KEY` | shared S3 defaults |
| `settings.minio.bucket` | `MINIO_BUCKET` | `stock-data.bucket` |
| `settings.minio.secure` | `MINIO_SECURE` | `false` |

### S3 path builders

Analyzer uses the same path-builder convention as Ingestor:

```python
settings.get_symbols_path("HOSE")                              # symbols/hose.parquet
settings.get_eod_path("HOSE", "HPG")                           # eod/hose/hpg.parquet
settings.get_indicators_path("ad_close", "1d", "HOSE", "HPG") # indicators/ad_close/1d/hose/hpg.parquet
settings.get_signals_path("TREND_MOMENTUM_V1", "1d", "HOSE", "HPG") # signals/trend_momentum_v1/1d/hose/hpg.parquet
```

Exchange names, ticker codes, and signal strategy names are lowercased in object names. Keep official uppercase symbols and strategy values in API responses and metadata. Indicator and signal path construction validates canonical timeframe values; v1 currently uses indicator source `ad_close`, timeframe `1d`, and signal strategy `TREND_MOMENTUM_V1`.

### Indicator and signal Kafka runtime

Analyzer starts the indicator and signal Kafka workers during FastAPI startup by default. Disable them for tests or local API-only runs with:

```bash
INDICATOR_KAFKA_ENABLED=false SIGNAL_KAFKA_ENABLED=false nx serve analyzer
```

The direct `POST /v1/indicators/sync` and `POST /v1/signals/sync` endpoints reuse their Kafka job-message contracts and write the same object-storage outputs synchronously, but return HTTP responses instead of publishing to `topic-sync-job-status`.

Contract summary:

| Topic                   | Direction | Purpose                                                                                 |
| ----------------------- | --------- | --------------------------------------------------------------------------------------- |
| `topic-sync-indicators` | Consume   | Platform requests full-series indicator calculation for one `symbolKey`.                |
| `topic-sync-signals`    | Consume   | Platform requests Market Signal V1 calculation for one `symbolKey`.                     |
| `topic-sync-job-status` | Publish   | Analyzer reports `SUCCESS` or `ERROR` with `recordsProcessed` and optional signal meta. |

Signal status metadata contains only transition metadata, not signal-state file contents or object paths. On success, `metaJson` may include `signalChanged`, `previousSignal`, `newSignal`, `price`, `signalDate`, `reasonCodes`, `score`, `strategy`, and `timeframe`. The first successful write creates a baseline with `previousSignal: null` and `signalChanged: false`, so Platform does not notify on initial state creation.

Market Signal V1 uses strategy `TREND_MOMENTUM_V1` over EOD `ad_close` plus `MA20`, `MA50`, `RSI14`, `MACD`, and `MACD_SIGNAL` indicator columns. It emits `BULLISH`, `NEUTRAL`, `BEARISH`, or `NO_DECISION`. Scoring is deterministic: price above/below `MA50` is `+2/-2`, `MA20` above/below `MA50` is `+1/-1`, `RSI14` above `55` or below `45` is `+1/-1`, and `MACD` above/below signal is `+1/-1`. Scores `>= 3` are `BULLISH`; scores `<= -3` are `BEARISH`; otherwise `NEUTRAL`. Missing required inputs produce `NO_DECISION` with structured reason codes.

---

## Infrastructure Foundations

### `KafkaEventPublisher`

`app/adapters/kafka_publisher.py` provides `publish_json(topic, payload)` using `aiokafka.AIOKafkaProducer`.

Current maturity:

- available as an adapter;
- not currently called by the stock compatibility endpoints;
- should only be used after an explicit Platform-owned event contract is agreed.

### `MinioObjectStorage`

`app/adapters/minio_storage.py` provides `read_bytes(object_name)` using the configured MinIO bucket.

Current maturity:

- available as a read-only adapter;
- not currently called by `StockService`;
- future analytical reads should go through this port or another agreed analytical boundary.

---

## Database Boundary

Analyzer must remain independent from PostgreSQL persistence concerns.

Do not add:

- SQLAlchemy engines or sessions.
- PostgreSQL drivers such as `asyncpg` or `psycopg`.
- Database repositories for Platform-owned tables.
- Generated ORM models from Flyway migrations.
- Direct writes to stock-price tables.

If Analyzer needs persisted market data in the future, prefer one of these patterns:

1. Read analytical data from MinIO/data-lake paths through an object-storage port.
2. Call a Platform-owned API that exposes the required read model.
3. Add a clearly owned analytical query boundary after agreeing on the service responsibility split.

Stock sync commands should continue to flow through the Platform scheduler and Ingestor contracts rather than direct Analyzer database writes.

---

## Indicator Calculation Boundary

Indicator jobs must keep producer and consumer contracts synchronized:

- Platform produces `IndicatorJobMessage` to `topic-sync-indicators`.
- Analyzer consumes that message and validates `indicatorSource`, `timeframe`, `symbolKey`, and the complete v1 indicator set.
- Analyzer reads `eod/{exchange}/{code}.parquet`, writes `indicators/{source}/{timeframe}/{exchange}/{code}.parquet`, and publishes `recordsProcessed` status.
- Platform consumes `topic-sync-job-status`, maps `ERROR` to failed execution state, and aggregates parent executions.

For the current v1 contract, `{source}` is `ad_close` and `{timeframe}` is `1d`.

Do not add bucket or object-name routing fields to indicator Kafka messages; object paths are derived from shared path builders.

---

## Development Commands

All development tasks should be run through **Nx** from the workspace root:

```bash
nx serve analyzer
nx test analyzer
nx lint analyzer
nx format analyzer
nx debug analyzer
```

Do not run `pip`, `uv`, `pytest`, or `uvicorn` directly. Use Nx targets such as:

```bash
nx run analyzer:add --name="pandas>=2.2.0"
nx run analyzer:remove --name="pandas"
nx run analyzer:lock
nx run analyzer:sync
```
