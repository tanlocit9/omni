# ADR-003: Use Parquet Object Storage as Analytical Data Lake

## Status

Accepted

## Context

Omni needs append/merge-friendly analytical datasets for EOD prices, indicators, signals, sector features, and backtest outputs. These datasets are read by Python analytical jobs and do not require Platform transactional semantics.

## Decision

Store analytical datasets as Parquet files in MinIO/S3-compatible object storage. Use centralized path patterns from [`configs/shared/s3-paths.yaml`](../../configs/shared/s3-paths.yaml).

Calendar business/trading dates use Arrow/Parquet `date32`; event instants use
Arrow timestamps at microsecond precision with UTC timezone. Shared encode and
legacy decode behavior lives in `libs/py-common`, and Query Service exposes these
as DuckDB `DATE` and `TIMESTAMPTZ`. Versioned schema rewrites publish READY last
and must not overwrite the object referenced by the current READY manifest.

## Consequences

- Ingestor and Analyzer read/write Parquet through shared storage abstractions.
- Platform database remains operational/control-plane storage.
- Kafka messages should not include object path routing fields.
- Dataset ownership is documented in [Data lake](../data/002-data-lake.md).
- Semantic date field names are preserved across producers and consumers.
