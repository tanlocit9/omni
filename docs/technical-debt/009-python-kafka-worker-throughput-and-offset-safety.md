# Python Kafka Worker Throughput and Offset Safety

Status: Scheduled as pending P12-I1 through P12-I4 after P11-I5; not an active MVP prerequisite

## Goal

Harden the Python Kafka worker execution boundary so large fan-out daily/EOD jobs drain at a bounded, observable rate without losing work when a worker restarts between consuming a command and publishing its terminal job status.

## Outcome

After the pending Phase 12 implementation, Ingestor and Analyzer workers use bounded concurrency appropriate to each workload and explicit offset commits tied either to successful output plus terminal-status publication or to an acknowledged durable write-intent handoff to the independent Python writer. Operators can distinguish scheduler dispatch completion, downstream consumer backlog, active processing, terminal-status publication, and permanently stranded executions without rewriting execution history manually.

## Observed Evidence

Runtime investigation on 2026-09-25 found:

- `8,137` scheduler outbox messages for affected parent executions were `PUBLISHED`;
- `0` scheduler outbox messages were `PENDING`;
- `8,132` child executions remained `RUNNING` and had never been updated after creation at the first observation;
- Analyzer and Platform logs subsequently showed successful signal calculation, `topic-sync-job-status` consumption, `RUNNING -> SUCCESS` child updates, and parent aggregation;
- the negative apparent `missing_outbox_count` was caused by comparing all outbox rows with only the still-running child subset, not by missing scheduler outbox messages.

### Observed message volume and end-to-end throughput

A cohort-correct PostgreSQL aggregation selected child executions created from `2026-09-24 00:00:45.580 +07:00` through, but excluding, `2026-09-25 00:00:45.580 +07:00`. It measured elapsed time from the first child creation to the database observation timestamp. This is observed runtime evidence, not a controlled load test or Kafka consumer-lag measurement.

| Measure                         |                               Observed value |
| ------------------------------- | -------------------------------------------: |
| Total child executions          |                                        8,422 |
| Still `RUNNING`                 |                                        8,132 |
| `SUCCESS`                       |                                          290 |
| `FAILED` or `ERROR`             |                                            0 |
| Completion ratio                |                                        3.44% |
| Cohort started (+07:00)         |                      2026-09-24 19:01:14.622 |
| Latest terminal update (+07:00) |                      2026-09-25 00:54:35.336 |
| Observation time (+07:00)       |                      2026-09-25 01:17:23.791 |
| Elapsed observation time        |    approximately 376.15 minutes / 6.27 hours |
| End-to-end terminal throughput  |                     0.77 children per minute |
| Linear remaining-time estimate  | 10,547.84 minutes / 175.80 hours / 7.32 days |

Observed terminal completions by database `updated_at` hour were:

| Hour (+07:00)      | SUCCESS | FAILED/ERROR | Terminal total |
| ------------------ | ------: | -----------: | -------------: |
| 2026-09-24 20:00   |      16 |            0 |             16 |
| 2026-09-24 21:00   |       7 |            0 |              7 |
| 2026-09-24 22:00   |       3 |            0 |              3 |
| 2026-09-24 23:00   |      16 |            0 |             16 |
| 2026-09-25 00:00   |     248 |            0 |            248 |
| **Observed total** | **290** |        **0** |        **290** |

The end-to-end rate is calculated as `290 / 376.15 = 0.77` terminal children per minute. The linear remaining-time estimate is `8,132 / 0.77 = 10,547.84` minutes. The estimate is directional only: completions were highly bursty, workload cost differs across stock-price, indicator, signal, Sector Wave, and Sector Transition jobs, and the database aggregation does not reveal Kafka partition lag or worker utilization.

The latest terminal update preceded the observation by approximately 22 minutes and 48 seconds. Combined with the concentration of 248 of 290 completions in the 00:00 hour, this indicates burst-and-stall behavior rather than a stable drain rate. Runtime logs proved the status path could work, but they do not prove that all remaining messages were still reachable or actively processing.

A future capacity decision must use a complete representative cohort and separate measurements by topic, consumer group, job type, provider, partition, success/failure outcome, and processing-duration percentile.

This evidence clears the Platform scheduler outbox as the immediate bottleneck. The active issue is downstream worker throughput or reachability, with an additional correctness risk around implicit Kafka offset commits during worker restart or failure.

## Problem

### Sequential processing

[`IngestorKafkaRoutingService.run()`](../../apps/ingestor/app/messaging/consumer.py) iterates one Kafka record at a time and awaits the complete provider/storage/status workflow before reading the next record. Shared Analyzer Kafka services use the same sequential pattern in [`JobStatusKafkaService._consume_loop()`](../../libs/py-common/py_common/kafka/job_status_service.py).

Large fan-out jobs can therefore create thousands of child executions immediately while downstream workers drain them serially. The database represents children as `RUNNING` from dispatch preparation, so operator views can show a large running population even when many commands are only waiting in Kafka.

### Implicit offset semantics

[`KafkaClientFactory.create_consumer()`](../../libs/py-common/py_common/kafka/factory.py) does not explicitly configure manual commits. The effective aiokafka defaults may commit offsets independently of successful dataset publication and terminal-status publication.

A worker restart or process failure after an offset becomes committed but before [`JobStatusPublisher.publish()`](../../libs/py-common/py_common/messaging/publisher.py) succeeds can leave a child execution permanently `RUNNING` without an automatic replay path.

### Missing operational distinction

Current execution visibility does not clearly separate:

1. scheduler command prepared;
2. scheduler outbox published;
3. command waiting in a worker consumer group;
4. command actively processing;
5. terminal status published;
6. terminal status applied by Platform;
7. command stranded beyond a bounded age.

## Scope

A future owner-approved increment should:

1. establish a capacity baseline per topic and consumer group, including published message count, consumed count, terminal count, lag, children per minute, failure share, processing-duration p50/p95/p99, and estimated drain time before choosing concurrency;
2. introduce bounded, configurable concurrency rather than unbounded task creation;
3. preserve ordering where a topic/key or dataset writer requires it;
4. define explicit offset commit behavior after successful work and terminal-status publication;
5. define failure behavior when work succeeds but status publication fails;
6. provide shutdown draining and rebalance-safe cancellation semantics;
7. prevent concurrent writes to the same logical dataset partition or object;
8. add bounded retry/recovery for stranded executions without direct ad hoc status rewrites;
9. expose throughput, in-flight count, processing duration, consumer lag, status-publication failures, and stale-running counts without payloads or secrets;
10. document safe per-service defaults and deployment overrides.

## Non-Goals

- Do not bypass or replace `scheduler_outbox_messages`.
- Do not mark existing `RUNNING` rows terminal without authoritative worker evidence.
- Do not replay every historical command automatically.
- Do not add provider concurrency intended to evade rate limits.
- Do not permit multiple workers to write the same dataset partition concurrently.
- Do not change analytical calculations, signal semantics, or dataset readiness rules.
- Do not make this debt a prerequisite for P8-I1, P8-I2, P8-I4, P8-I5, P9-I1, or P9-I4 without a separate owner decision.

## Dataset Outputs

No analytical dataset output.

Existing writers must preserve their current Parquet validation, immutable publication where applicable, and READY-last behavior. Concurrency must not create competing writers for one logical partition.

## Metadata Outputs

No dataset metadata output.

Existing manifest readiness, `dataVersion`, and lineage semantics remain unchanged.

## Algorithm Feature Outputs

No direct algorithm feature output.

## Algorithms Unlocked

No new algorithm is unlocked. The work makes existing daily/EOD ingestion, indicator, signal, Sector Wave, and Sector Transition workloads safer and more predictable under large fan-out.

## Contract Impact

| Contract area                        | Impact                                                                                                                                                                                          |
| ------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Kafka or service-to-service protobuf | Kafka business payload schemas remain unchanged. Consumer offset, retry, ordering, and status-publication semantics change operationally and require producer/consumer review.                  |
| Object-storage JSON manifests        | Unchanged. READY-last and immutable-version safety must be preserved under concurrency.                                                                                                         |
| Storage paths or dataset ownership   | Unchanged. Existing owners and shared path builders remain authoritative; concurrent same-partition writers are forbidden.                                                                      |
| Public Java or Python APIs           | Shared Python Kafka factory/service APIs may change to require explicit commit and bounded-concurrency policies. Callers in Ingestor and Analyzer must migrate together.                        |
| Configuration or environment         | Expected additive settings for concurrency, in-flight limits, processing timeout, shutdown drain, and commit policy. Defaults must preserve safe single-worker behavior until explicitly tuned. |

## Design Constraints

- Prefer partition/key-aware bounded concurrency over one global unbounded semaphore.
- Commit an offset only when all earlier offsets required by Kafka partition ordering are safe to advance.
- Treat terminal-status publication as part of successful command handling unless an explicit durable local/outbox handoff is introduced.
- If status publication fails after dataset publication, retries must be idempotent and must not corrupt or duplicate dataset output.
- Rebalance and shutdown must stop admission, await or cancel bounded in-flight work deliberately, and avoid committing unfinished offsets.
- Capacity must be measured separately for provider-bound ingestion and CPU/storage-bound analysis.
- Provider limits and dataset writer exclusivity override throughput goals.

## Current Source Assessment

- **Current:** Ingestor and shared Analyzer consumers process one record at a time and
  await the full workflow.
- **Current:** the shared aiokafka factory does not explicitly disable auto-commit or
  define manual commit ownership.
- **Current:** Platform applies one child status and aggregates its parent per consumed
  status record.
- **Planned, not implemented:** bounded concurrency, two-boundary offset ownership,
  bulk Platform status application, and the standalone writer service.
- **Historical owner-supplied evidence:** the observed cohort and drain calculation are
  not a current benchmark and cannot be reproduced from source inspection alone.
- **Deferred separately:** a repository-wide poison-record/DLT and audited replay policy
  belongs to
  [`010-kafka-poison-record-and-dead-letter-policy.md`](010-kafka-poison-record-and-dead-letter-policy.md),
  not to the Python worker offset-safety core.

## Recommended Actions

1. Implement P12-I1 first: inventory every affected consumer, disable implicit
   auto-commit, and prove contiguous-prefix commit, restart, revoke, and shutdown safety
   before adding concurrency.
2. Treat the recorded production cohort as directional history only; capture a fresh,
   complete, comparable baseline by topic, group, partition, job type, and stage.
3. Pilot bounded concurrency only on independent logical outputs and retain a default
   of one until provider/storage budgets are measured.
4. Preserve single-writer ownership for shared signal history until the independent
   writer cutover is complete and verified.
5. Keep P12-I3 focused on bounded status application, malformed-record isolation and
   explicit failure reporting; do not make durable DLT infrastructure a Phase 12 gate.
6. Require deterministic write-intent idempotency separate from Phase 11 diagnostic
   `correlationId` and `requestId`.
7. Keep every Phase 12 capability labelled planned until approved tests, load evidence,
   exact-head CI, and synchronized contract/flow documentation are recorded.

## Owner-Selected Standalone Writer Direction (2026-09-25)

The current deployment uses one Ingestor instance and one Analyzer instance. Phase 12 plans one additional independent Python dataset-writer service instance built on `py-common`. Ingestor/Analyzer may process independent commands concurrently within each instance. For selected shared outputs, Analyzer publishes durable Kafka write intents; the writer groups compatible operations by the resolved logical `writeKey` and batches a bounded number or bounded wait time into one read/merge/write operation. Its two purposes are to avoid competing writes to one MinIO object and to let upstream workers progress concurrently. A child reaches terminal `SUCCESS` only after its output is durably written and its status is published.

Only outputs with compatible batch semantics use this path. Independent per-symbol objects may be written concurrently without waiting for a shared batch. A batch can contain children from different parents; `parentExecutionId` is for aggregation, not writer exclusion or batch identity. Do not infer one scheduler outbox row for every execution created downstream of a command (for example, per-symbol projection executions created while consuming a sync-symbols batch). Compare dispatch-created children and downstream-created executions separately.

Resolve stable `writeMode` (`BATCH` or `SINGLE`) and the `writeKey` derivation by job type in code, alongside the planned dependency policy but without conflating dependency readiness with writer ownership. Keep mutable job parameters in `configJson`; snapshot the resolved routing fields in commands so delayed records retain their intended handling. A writer validates the operation and output identity; equal keys alone do not make two operations batch-compatible. The existing signal transition and notification semantics must remain correct under batching; defer a change to `signalChanged` only with an explicit consumer/notification decision, never silently substitute `false`.

The standalone writer uses a bounded in-memory per-key batch only as working state; a dedicated Kafka write-intent topic is the durable cross-service handoff. No Redis or additional persistent buffer is needed while writer replicas remain at one. A source command offset can advance after acknowledged durable intent publication (or authoritative failure status). The writer intent offset advances only after output and terminal status are published, with contiguous-prefix commits per partition. Retries must be idempotent, and shutdown/rebalance must not commit unfinished work. See [Plan 027](../plans/027-concurrent-workers-and-writer-batching.md) for the two offset boundaries and staged rollout.

The Platform status consumer also needs bounded bulk application before writer rollout. Its current single-record `JobStatusConsumer` calls `JobService.applyStatus` per child; each call saves one row and scans children while locking the parent for aggregation. P12-I3 will retain individual child status messages, validate and apply each child, group changed rows by persisted parent, and aggregate a parent once per batch. Preserve standalone statuses, duplicate/replay safety, one terminal transition and notification, and acknowledgment only after durable database application. Failed or malformed records must be identified, reported, and isolated from valid records rather than silently dropped. Durable DLT envelopes, retention, replay authorization, and cross-service retry policy are deferred to the separate poison-record technical debt.

This is an approved, pending Phase 12 design, not implemented behavior or a capacity result. See [High Availability Notes](../deployment/003-high-availability-notes.md) for the deferred multi-instance boundary.

## Proposed Investigation and Delivery Sequence

1. Capture at least one complete representative daily window of published messages, consumed messages, terminal statuses, Kafka lag, children-per-minute throughput, failure share, processing-duration p50/p95/p99, restart behavior, and dataset-write contention separately for representative Ingestor and Analyzer topics.
2. Add focused logging/metrics that correlate command topic/partition/offset with execution identity and terminal-status publication.
3. Define and test manual commit semantics in `libs/py-common`.
4. Pilot bounded concurrency on a non-provider Analyzer workload with partition-safe ordering.
5. Add Ingestor concurrency only after provider-rate and same-partition write constraints are proven.
6. Add stale-running detection and an audited recovery procedure.
7. Roll out incrementally with concurrency defaulting to one and explicit per-service overrides.

## Verification

No build, test, lint, format, load test, Kafka integration test, or production verification was run for this documentation-only debt record.

A future implementation must define and obtain approval for exact Nx commands after inspecting affected project targets. Required verification should include:

- shared Kafka factory unit tests for explicit commit configuration;
- worker tests proving offsets do not advance before processing and terminal-status publication complete;
- failure injection between dataset write, status publish, and offset commit;
- restart and consumer-rebalance integration tests;
- ordering tests for multiple records in one partition;
- same-logical-partition writer exclusion tests;
- bounded in-flight and graceful-shutdown tests;
- representative Kafka integration/load tests for Ingestor and Analyzer;
- Platform bounded bulk status-consumer tests for cross-parent groups, duplicates, invalid-record isolation/reporting, replay, one aggregation per parent, and terminal notification idempotency;
- post-edit graph impact and change detection;
- applicable Nx lint, test, and build targets for `py-common`, `ingestor`, `analyzer`, and `platform`.

## Acceptance Criteria

- Every affected consumer declares its offset-commit policy explicitly.
- A successfully committed command has either a successfully published terminal status or an acknowledged durable successor write intent whose writer owns final output and terminal status.
- Restart and rebalance tests prove unfinished work is replayed safely.
- Concurrency is bounded, configurable, observable, and defaults to a safe value.
- Same-partition or same-logical-dataset writes cannot race.
- Provider-bound workloads respect configured provider limits and do not add bypass/fallback behavior.
- Metrics distinguish published message volume, consumed message volume, consumer lag, in-flight work, terminal throughput per minute, success/failure share, processing-duration p50/p95/p99, status-publication failures, stale-running executions, and estimated drain time.
- Before/after evidence uses the same representative job mix and reports absolute message counts, observation duration, throughput, lag, failures, and resource utilization; no concurrency improvement is accepted from an unbounded or non-comparable benchmark.
- Parent aggregation remains driven by authoritative child terminal statuses; Platform applies bounded status batches with one aggregation per affected parent and safe offset acknowledgment.
- Kafka payload schemas, storage paths, dataset ownership, READY-last publication, and lineage contracts remain unchanged unless separately approved.
- Canonical flow/data documentation and repository guidance are synchronized if implementation changes runtime workflow or configuration.
- Required local checks and CI pass with evidence recorded before any future roadmap increment is completed.

## Repository Guidance Updates

This debt record adds no current implementation workflow, architecture decision, contract, or tool requirement. No update to `AGENTS.md`, `CLAUDE.md`, or `.roo/rules` is required now. A future implementation must review and synchronize those files plus the job execution and Kafka contract documentation if commit semantics, retry ownership, configuration, or operational workflow changes.

## Reusable Investigation Prompt

> Investigate Omni Python Kafka worker backlog and offset safety without assuming the Platform scheduler outbox is at fault. First use code-review-graph and inspect the exact Nx projects. Establish whether affected parent executions have one scheduler outbox row per child and whether those rows are `PUBLISHED`; do not compare all outbox rows only with the still-running child subset. Quantify the exact published, pending, consumed, running, successful, failed, and missing counts. Measure terminal children per minute over a stated observation window, processing-duration p50/p95/p99, failure share, Kafka lag by topic/partition/consumer group, and linear drain ETA with formulas and caveats. Separate measurements by job type and service so cheap signal work is not averaged with provider-bound stock ingestion. Verify terminal throughput with fresh PostgreSQL transactions and correlate runtime logs across worker command consumption, dataset processing, `topic-sync-job-status` publication, Platform `JobStatusConsumer`, `JobService.applyStatus`, and parent aggregation. Inspect worker restarts, provider/storage constraints, resource utilization, and aiokafka offset-commit behavior. Consider at least these causes: scheduler outbox pending/missing rows, downstream consumer lag, sequential worker throughput, disabled or unhealthy consumers, terminal-status publication failure, Platform status rejection due to execution/work identity mismatch, and offsets committed before work/status completion. Distill the evidence to the one or two most likely causes and add targeted logs/metrics before proposing a fix. Do not rewrite `RUNNING` execution rows, replay production commands, increase provider concurrency, or change Kafka/storage contracts without owner approval. If a fix is approved, design bounded key/partition-safe concurrency, explicit commit-after-work-and-status semantics, rebalance/shutdown safety, idempotent retry, same-dataset writer exclusion, stale-running detection, focused tests, and an incremental rollout with concurrency defaulting to one. Require comparable before/after counts, duration, throughput, lag, failure rate, and resource evidence. Follow the verification approval gate and use only defined Nx targets.

## Related Work

- [Job Execution Flow](../flows/001-job-execution.md)
- [Kafka Contracts](../data/001-kafka-contracts.md)
- [Scheduler Claim and Outbox Boundary](../adr/007-scheduler-claim-and-outbox-boundary.md)
- [Async Dependency Evaluation](007-async-dependency-evaluation.md)
- [Post-MVP Roadmap Work](004-post-mvp-roadmap-work.md)
- [High Availability Notes](../deployment/003-high-availability-notes.md)
- [Plan 027 — Concurrent Workers and Writer Batching](../plans/027-concurrent-workers-and-writer-batching.md)
- [Phase 12](../../plans/roadmap/phase-12-worker-throughput-and-writer-batching.md)

## Reactivation

The owner scheduled this debt as pending P12-I1 through P12-I4 after P11-I5. Implementation may begin only when the prerequisite increments are completed and normal ownership/readiness checks pass. Multi-instance writer deployment remains outside Phase 12 and requires the separate High Availability promotion gate.
