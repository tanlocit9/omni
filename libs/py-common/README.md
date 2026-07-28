# Omni Python Common Library

Shared Python library for Omni platform services (ingestor, analyzer).

## Features

- **Configuration Management**: YAML-based configuration loading with typed settings
- **Storage Abstractions**: Provider-agnostic storage ports (MinIO, AWS S3)
- **Parquet Processing**: High-level API for reading/writing DataFrames to object storage
- **Kafka Factories**: Reusable consumer/producer creation with retry configuration
- **Path Management**: Centralized S3 path construction with validation

## Installation

This is an internal library managed via local path dependencies:

```toml
# In service pyproject.toml
dependencies = [
    "omni-py-common @ file://../../libs/py-common",
]
```

## Usage

### Configuration

`BaseAppSettings` is the shared Python entry point for repository-level config. It loads stable defaults from `configs/shared/topics.yaml` and `configs/shared/s3-paths.yaml`, reads root `.env` / `.env.local`, and applies flat runtime env overrides into typed settings models.

```python
from py_common.config import BaseAppSettings

settings = BaseAppSettings()

settings.kafka.bootstrap_servers  # KAFKA_BOOTSTRAP_SERVERS override
settings.minio.endpoint           # MINIO_ENDPOINT override
settings.topic_sync_stock_prices  # topic-sync-stock-prices by default
settings.get_symbols_path("HOSE")  # symbols/hose.parquet
settings.get_eod_path("HOSE", "HPG")  # eod/hose/hpg.parquet
```

Use flat env variables as the canonical cross-language contract:

```dotenv
KAFKA_BOOTSTRAP_SERVERS=localhost:9092
MINIO_ENDPOINT=http://localhost:9000
MINIO_ACCESS_KEY=minioadmin
MINIO_SECRET_KEY=minioadmin
MINIO_BUCKET=stock-data
```

Do not use duplicate nested names such as `KAFKA__BOOTSTRAP_SERVERS` or `MINIO__ENDPOINT`. Java, Python, and Docker Compose all share the flat names above.

For lower-level utilities, load a shared YAML file directly:

```python
from pathlib import Path
from py_common.config import load_yaml, StockDataPaths, Timeframe

config = load_yaml(Path("configs/shared/s3-paths.yaml"))
paths = StockDataPaths.from_config(config["stock-data"])

paths.symbols("HOSE")  # → symbols/hose.parquet
paths.eod("HOSE", "HPG")  # → eod/hose/hpg.parquet
paths.indicators(Timeframe.ONE_DAY, "HOSE", "HPG")  # → indicators/1d/hose/hpg.parquet
```

### Storage

```python
from py_common.storage import (
    StorageProvider,
    StorageProviderRegistry,
    ParquetStorage,
    create_storage_registry,
)

# Create registry
registry = create_storage_registry(settings)
await registry.validate_all()

# Create Parquet storage
parquet = ParquetStorage(
    registry=registry,
    provider=StorageProvider.MINIO,
    bucket="stock-data",
)

# Read/write DataFrames
df = await parquet.read_dataframe("eod/hose/hpg.parquet")
await parquet.write_dataframe("eod/hose/hpg.parquet", df)
```

### Kafka

```python
from py_common.kafka import create_consumer, create_producer
from py_common.config import ConsumerGroup

# Create consumer
consumer = await create_consumer(
    bootstrap_servers="localhost:9092",
    topics=["symbols-sync"],
    group_id=ConsumerGroup.INGESTOR.for_topic("symbols-sync"),
)

# Create producer
producer = await create_producer(
    bootstrap_servers="localhost:9092",
)
```

## Development

```bash
# Install dependencies
nx run py-common:sync

# Run tests
nx run py-common:test

# Lint
nx run py-common:lint

# Format
nx run py-common:format
```

## Architecture

```
py_common/
├── config/           # Configuration management
│   ├── constants.py  # Timeframe, ConsumerGroup enums
│   ├── loader.py     # YAML configuration loader
│   ├── models.py     # Pydantic settings models
│   └── paths.py      # S3 path construction
│
├── storage/          # Storage abstractions
│   ├── capabilities.py
│   ├── exceptions.py
│   ├── ports.py
│   ├── registry.py
│   ├── parquet.py
│   └── adapters/
│       └── minio.py
│
└── kafka/            # Kafka client factories
    └── factory.py
```

## Design Principles

1. **Port-based architecture**: Storage operations defined as protocol interfaces
2. **Provider registry**: Runtime adapter selection without hardcoded dependencies
3. **Async-first**: All I/O operations use asyncio
4. **Type safety**: Full type hints with Pydantic validation
5. **Path centralization**: Single source of truth for S3 paths
6. **Fail-fast validation**: Explicit validation with clear error messages

## License

Internal use only.