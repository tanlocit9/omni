# Pre-Roadmap Capability Baseline

Status: Historical capability inventory  
Canonical scheduling/status owner: [`README.md`](README.md)

## Purpose

The numbered roadmap did not start from an empty repository. It starts from an already working single-node platform and organizes the hardening, contract, portability, operator-product, and higher-frequency work that follows.

This document records the capabilities inherited by Phase 0. It is a historical baseline, not a phase, increment queue, completion claim for later roadmap work, or substitute for current source and verification evidence.

## Baseline Capability Groups

### Workspace and Local Runtime

- Nx monorepo containing Java/Spring Boot and Python services.
- Shared project targets and repository-level development workflows.
- Dockerfiles and local Docker Compose infrastructure for PostgreSQL, Kafka, and MinIO/S3-compatible storage.
- Shared topic and object-path configuration under `configs/shared/`.

### Platform Control Plane

- Spring Boot Platform/Core API and PostgreSQL-backed operational state.
- Seeded job definitions, cron scheduling, and execution-history tracking.
- Parent/child execution aggregation for fan-out jobs.
- Kafka job producers and status/upsert consumers.
- Notification events, templates, and Telegram delivery through the original single-destination path.

The baseline scheduler worked but still needed the correctness, claim, outbox, identity, and dependency-safety work assigned to later roadmap phases.

### Market-Data Ingestion

- Kafka-driven symbol and EOD stock-price synchronization.
- External provider clients and provider-data normalization.
- Symbol and sector projection events back to Platform.
- Merge, deduplication, and Parquet persistence for `symbols` and `eod` datasets.

### Indicator and Signal Analytics

- Technical-indicator calculation from EOD data.
- Strategy signal calculation and signal-history persistence.
- Forward signal evaluation jobs.
- Signal-transition notification publication.
- Kafka worker lifecycle and job-status reporting from Analyzer.

### Sector Analytics and Research

- Symbol-level feature precomputation.
- Sector aggregation, breadth, contribution, relative-strength, and ranking calculations.
- Sector-rotation backtest outputs.
- Early Sector Transition research paths for predictions, probabilities, decisions, and outcome evaluation.

Sector Transition remains a research boundary unless a current roadmap increment explicitly promotes part of it. Its presence in source is not evidence of product readiness.

### Parquet Data Lake

- MinIO/S3-compatible storage for raw and analytical Parquet datasets.
- Established dataset families for symbols, EOD, indicators, signals, symbol features, sector features, and sector-rotation backtests.
- Shared logical path configuration and Python storage abstractions.

The baseline used working Parquet paths but did not yet provide the complete deterministic manifest, READY-last, lineage, and dependency-enforcement model assigned to Groups B and later work.

## Baseline System Flow

```text
Platform scheduler/API
  -> Kafka job commands
  -> Ingestor: symbols and EOD Parquet
  -> Analyzer: indicators, signals, evaluations, sector analytics
  -> Kafka status/upsert/notification events
  -> Platform execution state and Telegram delivery
```

## Roadmap Boundary

The numbered roadmap begins by hardening this baseline rather than rebuilding it:

1. Group A corrects and stabilizes the existing control plane.
2. Group B makes contracts, dataset readiness, lineage, and dependencies deterministic.
3. Group C makes deployment and operator-facing capabilities portable and safe.
4. Group D increases data frequency from EOD toward intraday and realtime processing.

Capabilities visible today may include work delivered after Phase 0. For current completion and verification status, use the phase files, [`implementation-increments.md`](implementation-increments.md), and [`execution-log.md`](execution-log.md).

## Canonical Detail

- [System boundaries](../../docs/architecture/001-system-overview.md)
- [Job execution](../../docs/flows/001-job-execution.md)
- [Stock sync](../../docs/flows/002-stock-sync.md)
- [Indicator and signal flow](../../docs/flows/003-indicator-signal.md)
- [Sector Wave flow](../../docs/flows/004-sector-wave.md)
- [Data lake](../../docs/data/002-data-lake.md)
- [Kafka contracts](../../docs/data/001-kafka-contracts.md)
