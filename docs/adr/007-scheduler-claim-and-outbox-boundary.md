# ADR-007: Scheduler Claim and Outbox Boundary

## Status

Accepted

## Context

Phase 1A added the PostgreSQL-backed lease foundation required for multi-instance-safe scheduling. Phase 1B integrated execution creation, next-run advancement, transactional outbox writes, and Kafka dispatch.

Phase 4 extends this boundary because checking dataset dependencies before execution/outbox creation loses a stable operational identity for accepted work and allows restart catch-up jobs to become eligible together based on scheduler timing. Dependency eligibility therefore belongs at the durable scheduler-outbox dispatch boundary, after execution and message intent have been committed but before Kafka publication.

## Decision

- Lease state lives on `job_definitions` because the recurring job definition is the claimed resource.
- PostgreSQL `FOR UPDATE SKIP LOCKED` is the primary concurrency mechanism.
- Every acquisition receives a new UUID `claimToken` used as a fencing token.
- `claimedBy` identifies the process instance, while ownership checks require both `claimedBy` and `claimToken`.
- The claim transaction is short and never publishes Kafka.
- Phase 1A does not create executions, advance `nextRun`, or publish messages.
- Phase 1B atomically creates execution/outbox records, advances `nextRun`, and clears the matching claim.
- Kafka delivery is at-least-once through a transactional outbox, using stable execution/message identities for idempotency.
- Current Phase 0 due semantics remain unchanged: active jobs with `nextRun <= now` or `nextRun = NULL` are candidates.
- For a claim candidate whose `nextRun` is `NULL`, `scheduledFor` is set to the claim acquisition timestamp. Stable execution/message identity preserves that run identity through dispatch and retry.
- Scheduled and accepted manual work commit an execution and PENDING scheduler-outbox row before dependency evaluation.
- Dependency evaluation remains Platform-local and occurs before the outbox row is claimed for Kafka publication.
- The dispatcher evaluates unclaimed candidates and returns one of three decisions:
  - `READY`: atomically claim with a fresh lease/fencing token, increment the delivery attempt, and publish;
  - `WAITING`: retain PENDING state, persist a bounded reason and retry time, and do not increment delivery attempts;
  - `BLOCKED`: atomically make the outbox and execution terminal `BLOCKED`, without fabricating a worker `FAILED` result.
- Candidate over-fetch prevents waiting rows from consuming the ready publication batch.
- Manifest READY state and exact `dataVersion` lineage remain the hard data dependency contract.
- Terminal outbox rows are never reclaimable, while publish retry and acknowledgement remain conditional on the exact claim owner and token.
- Dependency policy is not shared with workers or the future dataset-writer service. Reusable safe-write mechanics are limited to immutable intent/output identity, fenced leases, bounded retries, conditional state transitions, idempotent completion, and acknowledgement only after durable output.

## Consequences

- Two Core instances racing for the same due job cannot both acquire a live lease.
- A live lease is not reclaimable; an expired lease is reclaimable with a new fencing token.
- Claim release is conditional on the exact owner and token, preventing stale owners from releasing newer claims.
- Claim queries stay bounded and deterministic with `ORDER BY next_run ASC NULLS FIRST, id ASC` and a configured batch size.
- PostgreSQL-specific lock behavior is covered by Testcontainers integration tests instead of relying on H2 semantics; passing evidence remains required before an increment is completed.
- `JobScheduler.scan()` claims due definitions and `JobProducer.prepareDispatch()` creates stable execution/outbox state without Kafka I/O or pre-enqueue dependency blocking.
- Manual acceptance uses the same durable preparation path and can return a stable execution identity while work is waiting.
- Outbox delivery uses a second lease/fencing token. A failed publish preserves the same message identity and payload for retry.
- Waiting for a dependency is operationally distinct from attempting and failing Kafka delivery.
- Terminal dependency incompatibility is visible as `BLOCKED` in execution/outbox state and cannot be reclaimed.
- Delivery is at-least-once: consumers remain responsible for deduplication by stable execution identity.
- The future dataset writer may implement the same safe-write invariants, but it does not inherit Platform dependency evaluation or access scheduler persistence.

## Related Work

- [Job execution flow](../flows/001-job-execution.md)
- [Dependency-aware outbox dispatch](../plans/023-dependency-aware-outbox-dispatch.md)
- [Concurrent workers and writer batching](../plans/027-concurrent-workers-and-writer-batching.md)
- [Phase 1 backend/core stabilization roadmap](../plans/roadmap/phase-1-backend-core-stabilization.md)
- [Phase 4 job dependency guard roadmap](../plans/roadmap/phase-4-job-dependency-guard.md)
