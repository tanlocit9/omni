# Python Kafka Worker Throughput and Offset Safety

Current P14 source delivery (2026-10-30): **I8/I9 verification_pending**.
I9.a/b/c adds opt-in JobType graph preparation, independent atomic JobRuns with
shared in-flight attempts and exact dependency barriers, plus reversible scheduled
preset consolidation in the existing definitions catalog. V17/V18 are forward-only;
existing V1 paths and I8 wire/worker semantics remain. New read-only authenticated
work diagnostics are additive; writer and broader output/version exclusion remain
deferred. Prior indicator approval/byte-binding evidence remains historical.
No verification ran for this continuation; defaults remain unactivated. See
[full contract, impact and review handoff](../plans/030-static-graph-dispatch-planner.md#i9-full-source-delivery-and-review-handoff--2026-10-30).


Historical P14 scheduling reconciliation (2026-10-29): I8 and I9 are now explicitly
registered pending after completed I7, with I9.a/b/c internal checkpoints.
Reuse atomic work/execution envelope, accepted parameter snapshots, validation
and storage/status helpers. Result + work identity reaches writer through a
future durable operation intent; output writeKey and deterministic intentId stay
separate. Writer does not resolve graph or expand; actual handoff/offset loops
remain deferred P12 work. This is no implementation or readiness/version-safety PASS.
See [current handoff](../plans/030-static-graph-dispatch-planner.md#current-i8i9-handoff--2026-10-29).

## I9 checkpoint and result-handoff alignment — 2026-10-28

P14-I9 is split into sequential internal checkpoints: I9.a graph-driven expansion,
I9.b shared ancestor work, I9.c definition consolidation with history/offset/
migration/diagnostics/rollback evidence. These are not new registry IDs and do not
change I9 status or activate P12.

Future consumers send computed result plus unchanged atomic work identity to the
writer: JobType, typed workKey, businessDate, canonical effective parameters and
execution correlation. Add operation, logical output/row identity, deterministic
intentId and result or a validated durable logical candidate reference. Keep
physical paths out of business routing and retain execution attribution.

Writer validates the intent, derives output writeKey and buffers only compatible
operations; it does not resolve static graph or expand work. One work can emit
multiple operations/outputs; several works or JobTypes can share a writeKey.
Conflicting results under one intentId require explicit rejection/conflict handling.
All required outputs must be durable before individual terminal SUCCESS.

Current terminal-status commit loop is not the future durable-intent handoff loop.
Preserve source-after-intent and writer-after-output/status commit boundaries.
No implementation, new test/coverage PASS, version-binding solution or default
activation is claimed. See [I9 checkpoints and handoff](../plans/030-static-graph-dispatch-planner.md#i9-checkpoints-and-writer-result-handoff--2026-10-28).

## P14 expansion reuse boundary — 2026-10-28

I8 common atomic expansion precedes I9 graph-driven ancestors/shared work and
definition consolidation. Definitions remain configuration; full cron/definition
removal is outside scope. I8/I9 pending and P12/writer deferred.

Reuse I8 execution/work envelope, businessDate, resolved parameter snapshots,
validation/canonicalization fixtures and logical output references. Reuse current
py-common storage/path builders, JobStatusPublisher and Kafka lifecycle.
Java SafeWriteIntent supplies mechanics semantics, not a Python writer service.

Work dedup, logical output writeKey and operation intentId remain distinct.
Symbols/dates and SYNC_SIGNALS/EVALUATE_SIGNALS can mutate the same strategy/
timeframe/exchange history. JobType selects policy but is not a sufficient buffer/
exclusion key. One work may emit several operations/outputs; equal keys do not
prove batch compatibility. Preserve ordering/transition/notification semantics.

Current consume_job_records requires acknowledged terminal status before commit;
do not reuse unchanged for compute-to-intent handoff. Preserve two offset boundaries:
source after durable intent acknowledgement (or authoritative error), writer after
outputs plus per-child terminal status. RAM admission is not durable handoff/SUCCESS.
intentId remains immutable execution/operation/logical row-output/schema-model
identity; exact envelope/hash still to freeze. No writer or concurrency activation.
Version binding remains unresolved separately; signal batching and ancestor dedup
do not solve EOD/indicator approved-version or metadata/bytes races.
See [Plan 030 reuse matrix](../plans/030-static-graph-dispatch-planner.md#writer-reuse-and-separation).

## Supervised recovery update — 2026-10-26

The owner-approved sequential recovery slice now restarts cleaned-up sessions
with 5–60 second exponential backoff. Analyzer recreates same-group Kafka clients;
Ingestor restarts routing/storage initialization. Failed commands stay uncommitted,
and poison commands recur without skipping. Shutdown cancellation prevents restart,
even if cleanup raises. Ingestor/Analyzer health reports 503 for failed/recovering
consumers; it checks lifecycle rather than broker responsiveness or forward progress.

The four-case real broker suite now verifies automatic recovery and HTTP 503 during
outage rather than requiring explicit restart. Unit tests cover client recreation,
startup/shutdown cleanup and healthy/failed consumers. See the
[current matrix and limits](../deployment/004-job-run-admission-rollout.md#worker-recovery-and-terminal-entries--2026-10-26).
TD-009 remains open for capacity/concurrency, operational deployment and remaining
failure/load scenarios; this does not activate P12. The observations below are
historical evidence of the repaired lifecycle defect.

## Recovery observation — 2026-10-26

The extended `py-common:test-integration` target passes four real-broker cases,
including pause during terminal-status publication and recovery after explicit
worker restart. The command offset remains uncommitted on publication timeout;
restart processes it and commits after acknowledged SUCCESS. The shared worker
HTTP wrapper still returns 200 when its background task has failed. This is a
reproduced readiness defect; no supervisor or automatic recovery was added.
See the [executed matrix and scope limits](../deployment/004-job-run-admission-rollout.md#disposable-cross-service-integration--2026-10-26).
The earlier three-case evidence below remains historical; throughput/concurrency,
rebalance handling and operational recovery are still open.

## Offset correctness update — 2026-10-25

The narrow sequential offset/startup slice is implemented and locally verified;
TD-009 remains open for capacity, recovery supervision and operational evidence.
The factory explicitly disables auto-commit, and both Ingestor and the shared
Analyzer lifecycle use partition-specific commits after acknowledged terminal
status. Ingestor no longer discards a startup batch. Failures, cancellation,
unidentifiable commands and commit/rebalance failures stop before later work can
advance the offset. This is fail-closed behavior, not a new poison/DLQ policy or
automatic retry/supervision facility. Existing HTTP health responses do not detect
every failed background consumer; alert/restart handling remains operational work.

`nx run py-common:test-integration` uses a unique disposable Kafka 7.6.0 container,
a loopback-only port and no retained volumes. Three broker cases exercise abrupt
process exit before status, exit after status acknowledgement but before commit,
and output/status acknowledgement followed by commit/restart. Commands replay in
both crash windows; acknowledged and committed work does not replay. These are
offset/publication-boundary tests, not live provider/storage/whole-JobRun E2E tests.
The image must already be available; the target uses `--pull=never` and removes its
own verified container. The normal test target skips these opt-in Docker cases.

Local `P14-offset-safety` evidence records 155 py-common, 46 Ingestor and 142
Analyzer passing tests, plus the three broker cases. Critical file line/branch
coverage: factory 86.67%/100%, shared consume loop 100%/100%, shared service
100%/87.5%, Ingestor consumer 95.92%/100%. The ignored
`.agent/check-results/P14-offset-safety/coverage-matrix.json` binds sources and
executed tests to reports. No retained offset reset, replay, deployment, provider,
concurrency increase or automatic P12 increment promotion is claimed.

See the [canonical offset contract](../data/001-kafka-contracts.md#python-command-offset-ownership)
and [JobRun rollout readiness](../deployment/004-job-run-admission-rollout.md#offset-safety-follow-up--2026-10-25).
The following October 7 assessment and September measurements are historical;
current source assessment below incorporates this narrow fix.

## Review — 2026-10-07 (historical)

| Field           | Assessment                                                                                                                                                   |
| --------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Type            | correctness + capacity (separate slices)                                                                                                                     |
| Status          | OPEN / static risks confirmed; throughput unmeasured                                                                                                         |
| Priority        | P1 offset safety; P2 measured capacity                                                                                                                       |
| Static evidence | Factory leaves offset policy implicit; worker loops are sequential. Ingestor startup discards getmany() records. No runtime loss or current rate is claimed. |
| Activation      | Correctness evidence can justify an immediate narrow fix; concurrency/writer follow baseline and owner promotion.                                            |

Refs: [libs/py-common/py_common/kafka/factory.py](../../libs/py-common/py_common/kafka/factory.py), [libs/py-common/py_common/kafka/job_status_service.py](../../libs/py-common/py_common/kafka/job_status_service.py), [apps/ingestor/app/messaging/consumer.py](../../apps/ingestor/app/messaging/consumer.py).

Priority and review status: [technical-debt index](README.md). [Mermaid priority source](priority-order.md). This review adds no runtime verification or completion claim; preserved material below is historical unless reconciled here.

Status: Scheduled as pending P12-I1 through P12-I4; P12-I1 begins only after P11-I5 and the completed P13-I1 truthful-stage measurement gate; not an active MVP prerequisite

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

## Startup record-discard risk

Before the October 25 fix, Ingestor `_start_kafka_clients()` awaited
`consumer.getmany(timeout_ms=1000)` and discarded its batches before routing.
The startup poll has now been removed and queued-record routing/commit ordering
has regression coverage. The historical source risk does not prove retained-data
loss and did not require concurrency or a standalone writer to fix.

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
- **Current:** the factory disables auto-commit. Sequential command loops own
  partition-specific commits after acknowledged status; see the October 25 update.
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

1. For capacity expansion, complete P13-I1 first so dispatch, worker wait, processing, status publication, and
   Platform application have truthful evidence and a representative baseline; do not
   use child creation-time `RUNNING` rows as active-processing measurements.
2. Reactivate the capacity scope only through a bounded increment; its retained Phase 12 dependencies are proposal context, not an active schedule. The sequential offset slice now has the October 25 local evidence above. Preserve
   its partition-specific commit/restart/shutdown guarantees; concurrency additionally
   requires contiguous-prefix ownership and live rebalance/revoke evidence.
3. Treat the recorded production cohort as directional history only; extend the P13-I1
   baseline with a fresh, complete, comparable offset/capacity cohort by topic, group,
   partition, job type, and stage.
4. Pilot bounded concurrency only on independent logical outputs and retain a default
   of one until provider/storage budgets are measured.
5. Preserve single-writer ownership for shared signal history until the independent
   writer cutover is complete and verified.
6. Keep P12-I3 focused on partition-scoped bounded status application, typed malformed-
   record failure, affected-partition pause, and authenticated audited recovery; do not
   make durable DLT infrastructure a Phase 12 gate.
7. Require deterministic write-intent idempotency separate from Phase 11 diagnostic
   `correlationId` and `requestId`.
8. Keep every Phase 12 capability labelled planned until approved tests, load evidence,
   exact-head CI, and synchronized contract/flow documentation are recorded.

## Owner-Selected Standalone Writer Direction (2026-09-25)

The current deployment uses one Ingestor instance and one Analyzer instance. Phase 12 plans one additional independent Python dataset-writer service instance built on `py-common`. Ingestor/Analyzer may process independent commands concurrently within each instance. For selected shared outputs, Analyzer publishes durable Kafka write intents; the writer groups compatible operations by the resolved logical `writeKey` and batches a bounded number or bounded wait time into one read/merge/write operation. Its two purposes are to avoid competing writes to one MinIO object and to let upstream workers progress concurrently. A child reaches terminal `SUCCESS` only after its output is durably written and its status is published.

Only outputs with compatible batch semantics use this path. Independent per-symbol objects may be written concurrently without waiting for a shared batch. A batch can contain children from different parents; `parentExecutionId` is for aggregation, not writer exclusion or batch identity. Do not infer one scheduler outbox row for every execution created downstream of a command (for example, per-symbol projection executions created while consuming a sync-symbols batch). Compare dispatch-created children and downstream-created executions separately.

Resolve stable `writeMode` (`BATCH` or `SINGLE`) and the `writeKey` derivation by job type in code, alongside the planned dependency policy but without conflating dependency readiness with writer ownership. Keep mutable job parameters in `configJson`; snapshot the resolved routing fields in commands so delayed records retain their intended handling. A writer validates the operation and output identity; equal keys alone do not make two operations batch-compatible. The existing signal transition and notification semantics must remain correct under batching; defer a change to `signalChanged` only with an explicit consumer/notification decision, never silently substitute `false`.

The standalone writer uses a bounded in-memory per-key batch only as working state; a dedicated Kafka write-intent topic is the durable cross-service handoff. No Redis or additional persistent buffer is needed while writer replicas remain at one. A source command offset can advance after acknowledged durable intent publication (or authoritative failure status). The writer intent offset advances only after output and terminal status are published, with contiguous-prefix commits per partition. Retries must be idempotent, and shutdown/rebalance must not commit unfinished work. See [Plan 027](../plans/027-concurrent-workers-and-writer-batching.md) for the two offset boundaries and staged rollout.

The Platform status consumer also needs bounded bulk application before writer rollout. Its current single-record `JobStatusConsumer` calls `JobService.applyStatus` per child; each call saves one row and scans children while locking the parent for aggregation. P12-I3 will retain individual child status messages, split polls into ordered partition sub-batches, apply each sub-batch in one database transaction, group changed rows by persisted parent, and aggregate a parent once per sub-batch. Preserve standalone statuses, duplicate-terminal idempotency, one terminal transition and notification, and acknowledgment only for each partition's contiguous committed prefix. A typed malformed or contract-invalid record rolls back and pauses only its partition; unrelated partitions continue. Recovery either corrects compatibility/data and retries or records an authenticated, audited decision before discarding exactly the reviewed failing offset. Durable DLT envelopes, payload retention, generalized replay authorization, and cross-service retry policy are deferred to the separate poison-record technical debt.

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
- [Phase 12](../plans/027-concurrent-workers-and-writer-batching.md)

## Reactivation

Historical P12-I1 through P12-I4 design is now deferred under TD-011. Dependency completion does not authorize implementation: explicit owner reactivation of a bounded slice is required. The retained full sequence depends on P11-I5 and P13-I1; a confirmed offset/data-loss correctness fix can be separately promoted without full logging or capacity rollout. Multi-instance writer deployment requires the separate High Availability promotion gate.
