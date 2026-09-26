# Phase 12 — Python Worker Throughput and Dataset Writer

## Goal

Harden Python Kafka offset handling, add bounded concurrent processing, and move shared signal-history writes to an independent Python dataset-writer service with durable Kafka handoff. The canonical implementation detail is [Plan 027](../../docs/plans/027-concurrent-workers-and-writer-batching.md).

## Eligibility and execution order

Phase 12 begins only after **P11-I5 is completed**, so the full Phase 11 traceability sequence (including persistent job/outbox identity and Kafka header propagation) precedes new cross-service writer boundaries. No Phase 12 increment is active today.

```text
P11-I5 -> P12-I1 offset safety and baseline
        -> P12-I2 policy and independent-output concurrency
        -> P12-I3 Platform bulk child-status application
        -> P12-I4 independent signal writer service
```

All Phase 12 increments are `pending` and must follow normal ownership and readiness rules. A new branch and draft PR are required per increment; this document schedules future work and does not assert implementation or CI evidence.

## P12-I1 — Worker offset safety and baseline

| Field         | Value                                                |
| ------------- | ---------------------------------------------------- |
| id            | P12-I1                                               |
| status        | pending                                              |
| depends_on    | [P11-I5]                                             |
| blocks        | [P12-I2]                                             |
| owned_modules | [libs/py-common, apps/analyzer, apps/ingestor, docs] |

- Measure representative published/consumed/terminal counts, lag, processing stage timings, failures, provider/storage utilization and parent aggregation latency by job type.
- Make offset policy explicit, commit only after output plus terminal-status publication, and establish failure/retry, restart, rebalance and bounded shutdown rules.
- Prove at-least-once replay safety under write/status/commit failure injection. This increment does not increase provider concurrency or create a writer service.
- Gate: exact source/consumer coverage and baseline evidence, approved Nx checks and CI; no affected command can be committed before a durable completion boundary.

## P12-I2 — Job policy and independent-output concurrency

| Field         | Value                                                                                     |
| ------------- | ----------------------------------------------------------------------------------------- |
| id            | P12-I2                                                                                    |
| status        | pending                                                                                   |
| depends_on    | [P12-I1]                                                                                  |
| blocks        | [P12-I3]                                                                                  |
| owned_modules | [apps/core, apps/analyzer, apps/ingestor, libs/py-common, configs, docs/data, docs/flows] |

- Define code-owned job-type write policy: `writeMode`, logical `writeKey` resolution, operation compatibility and concurrency defaults. Retain variable job inputs in `configJson`; dependency readiness remains a separate policy.
- Update active JSON command producer/consumer contracts together, preserving Phase 11 transport headers. No physical MinIO path belongs in Kafka business fields.
- Introduce bounded in-flight processing first for independent logical output objects. Preserve one writer per object, per-provider limits, manual commit and drain semantics.
- Gate: representative before/after throughput, lag and correctness; producer/consumer contract fixtures, restart and same-object exclusion tests, approved Nx checks and CI.

## P12-I3 — Platform bulk child-status application

| Field         | Value                              |
| ------------- | ---------------------------------- |
| id            | P12-I3                             |
| status        | pending                            |
| depends_on    | [P12-I2]                           |
| blocks        | [P12-I4]                           |
| owned_modules | [apps/core, docs/flows, docs/data] |

- Consume bounded batches of individual child status records from `topic-sync-job-status` and validate persisted child execution and work identity independently for each record. Preserve the existing Kafka status payload.
- Apply changed child rows, group by persisted parent ID, acquire parent locks in deterministic order and aggregate each affected parent once per batch. Preserve standalone execution status, idempotent replay and one terminal parent notification.
- Acknowledge only after database application succeeds. Identify and report invalid records, isolate them from valid records for processing, and never silently acknowledge them as successfully applied. A non-retryable record blocks the contiguous offset prefix of its partition: pause that partition and alert for operator intervention; do not commit past it or let it block unrelated partitions. If a partially applied batch replays, completed child transitions remain idempotent. Durable dead-letter envelopes, retention, replay authorization, and cross-service poison-record policy are deferred to [`technical-debt/010`](../../docs/technical-debt/010-kafka-poison-record-and-dead-letter-policy.md) and are not a P12-I3 completion gate.
- Gate: cross-parent and same-parent batches, duplicates, malformed identity, partial failure/replay, terminal notification equivalence, parent lock/write counts, status lag, approved Nx checks and exact-head CI.

## P12-I4 — Independent Python dataset-writer service

| Field         | Value                                                                                                            |
| ------------- | ---------------------------------------------------------------------------------------------------------------- |
| id            | P12-I4                                                                                                           |
| status        | pending                                                                                                          |
| depends_on    | [P12-I3]                                                                                                         |
| blocks        | []                                                                                                               |
| owned_modules | [apps/core, apps/analyzer, apps/dataset-writer, libs/py-common, configs, docs/data, docs/flows, docs/deployment] |

- Build a standalone Python writer Nx project that reuses `py-common`. Use one durable Kafka write-intent topic keyed by logical output; start with exactly one writer service instance.
- Analyzer computes compatible signal candidates concurrently and publishes validated intents with a deterministic `intentId` derived from immutable operation/output identity. Phase 11 `correlationId` and `requestId` remain diagnostic only and are never idempotency or deduplication keys. Source command offset advances only after Kafka acknowledges the durable handoff (or an authoritative compute failure status). Writer buffers by `writeKey`, batches compatible upserts, serializes outcome updates under the same key, writes MinIO and then publishes each child status before committing the corresponding intent offsets.
- Preserve existing signal state transition, current projection, notification, manifest and retry semantics. The writer is the only mutator of selected signal-history paths; no direct Analyzer bypass.
- Gate: exact signal behavior and output equivalence under burst, replay and restart; write-count/throughput evidence; two-boundary offset tests; approved Nx checks and exact-head CI.

## Exclusions and future promotion

This phase does not scale the writer to multiple instances, introduce Redis locks, add a Rust writer, change unrelated dataset outputs, bypass provider limits, or make Phase 11 centralized logs a runtime dependency. [High Availability Notes](../../docs/deployment/003-high-availability-notes.md) cover the separate multi-instance lock, stale-writer and rebalance gate. A batch may contain different parent executions; parent IDs never define a write lock.

## Completion

Each increment requires its own reviewable PR, objective tests, exact-head CI and synchronized canonical Kafka, data, flow and repository guidance. The [increment registry](implementation-increments.md) remains authoritative for status and execution order.
