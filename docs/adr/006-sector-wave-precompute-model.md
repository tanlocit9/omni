# ADR-006: Use Precomputed Sector Wave Model

## Status

Accepted

## Context

Sector Wave analysis requires symbol features, sector aggregates, ranking, breadth, coverage, contribution metrics, and forward-return backtests. Computing all of this on demand would be expensive and inconsistent across consumers.

## Decision

Precompute Sector Wave datasets in Analyzer and store outputs in the Parquet data lake using configured paths for symbol features, sector features, and sector rotation backtests.

## Consequences

- Analyzer owns Sector Wave calculations and datasets.
- Platform schedules precompute/backtest jobs through Kafka.
- Downstream consumers read stable Parquet outputs.
- Sector Wave flow details live in [Sector wave](../flows/004-sector-wave.md).
