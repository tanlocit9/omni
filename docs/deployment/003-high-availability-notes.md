# High Availability Notes — Python Workers and Dataset Writers

**Status:** Future deployment guidance; Omni currently runs a single instance of each Python worker service.  
**Scope:** Multi-instance Ingestor/Analyzer and independent Python dataset-writer safety.  
**Source design:** [Python Kafka Worker Throughput and Offset Safety](../technical-debt/009-python-kafka-worker-throughput-and-offset-safety.md).

## Current single-instance boundary

Bounded concurrent commands in Ingestor/Analyzer may write independent objects in parallel. For selected shared signal outputs, the proposed independent Python dataset-writer service consumes a durable Kafka write-intent topic, groups compatible results by logical `writeKey`, and flushes a bounded batch on count or elapsed time. Its one-instance per-key batch is working memory; Kafka retains uncommitted write intents for replay. An Analyzer source command offset can advance after an acknowledged durable intent handoff; a writer intent offset advances only after output and terminal status succeed. Different parent execution IDs may share one batch; parent identity does not grant exclusive access to a dataset.

This local per-key exclusion protects only one process. The Phase 12 single-writer-instance rollout does not require Redis or a distributed lock. Producer/consumer concurrency, partition ordering, idempotent retry, bounded buffers, shutdown, and rebalance behavior still require verification.

## Multi-instance promotion gate

Before a second dataset-writer instance can write an output already owned by the first:

1. Route **every** producer capable of mutating the same logical dataset through the same writer boundary. Define and validate a stable `writeKey` for the logical output, plus a compatible write operation. No direct write path may bypass ownership.
2. Preserve the Phase 12 durable write-intent handoff. Use a Kafka key derived from `writeKey` and one writer consumer group; verify topic partitions and assignment. Kafka partition ownership helps route work but does not, by itself, fence a writer still completing a MinIO operation after rebalance.
3. Add shared per-key writer ownership, such as a Redis lease or equivalent, across the full read/merge/write and publication boundary. Specify renewal, loss of ownership, stale-writer fencing or equivalent conditional publication, and recovery. A time-to-live lock without stale-writer protection is insufficient.
4. Verify idempotent replay when a write succeeded but status publication or offset commit failed. Preserve READY-last and immutable-version rules where they apply; prevent stale writers from replacing a newer mutable pointer or object.
5. Test concurrent instances, rebalance during MinIO write, process crash, lease expiry, duplicate delivery, and recovery with real storage/Kafka behavior before raising replica count.

Per-thread or per-key in-process workers are an implementation choice for local serialization. They do not provide cross-instance safety. Global provider limits and MinIO connection/pool capacity must also be measured before scaling.

## Related boundaries

- [Job Execution Flow](../flows/001-job-execution.md)
- [Kafka Contracts](../data/001-kafka-contracts.md)
- [Data Lake](../data/002-data-lake.md)
- [Phase 12 Worker and Writer Plan](../../plans/roadmap/phase-12-worker-throughput-and-writer-batching.md)
