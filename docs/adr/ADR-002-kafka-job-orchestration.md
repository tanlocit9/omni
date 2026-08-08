# ADR-002: Use Kafka for Asynchronous Job Orchestration

## Status

Accepted

## Context

Stock ingestion and analytical calculations can be long-running and I/O-heavy. Platform needs to schedule and track jobs without blocking API or scheduler threads.

## Decision

Use Kafka as the asynchronous boundary between Platform and worker services. Platform publishes job messages, workers consume them, and workers publish status events back to Platform.

## Consequences

- Platform owns job state and aggregation.
- Workers own data-plane execution.
- Kafka contracts must be updated on both producer and consumer sides.
- Topic names are centralized in [`configs/shared/topics.yaml`](../../configs/shared/topics.yaml).
- Job flow details are documented in [Job execution](../flows/job-execution.md).
