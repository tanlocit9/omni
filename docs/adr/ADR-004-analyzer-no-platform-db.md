# ADR-004: Keep Analyzer Independent from Platform Transactional Database

## Status

Accepted

## Context

Analyzer performs analytical calculations from Parquet datasets. Direct coupling to Platform PostgreSQL tables would make analytical jobs depend on Platform transactional schema and migration cadence.

## Decision

Analyzer should not own direct Platform database reads/writes for stock prices, job state, symbols, sectors, or signal operational status. It should communicate operational outcomes through Kafka and use Parquet as its analytical storage boundary.

## Consequences

- Platform remains the owner of PostgreSQL migrations and operational state.
- Analyzer reads/writes analytical datasets through object storage abstractions.
- Status and notification outcomes flow through Kafka.
- If Analyzer needs metadata, prefer Parquet/shared datasets or explicit Kafka/API contracts rather than direct table access.
