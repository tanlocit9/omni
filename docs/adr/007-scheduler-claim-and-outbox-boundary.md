# ADR-007: Scheduler Claim and Outbox Boundary

## Status

Accepted

## Context

Phase 1A added the PostgreSQL-backed lease foundation required for multi-instance-safe scheduling. Phase 1B now integrates execution creation, next-run advancement, transactional outbox writes, and Kafka dispatch.

## Decision

- Lease state lives on `job_definitions` because the recurring job definition is the claimed resource.
- PostgreSQL `FOR UPDATE SKIP LOCKED` is the primary concurrency mechanism.
- Every acquisition receives a new UUID `claimToken` used as a fencing token.
- `claimedBy` identifies the process instance, while ownership checks require both `claimedBy` and `claimToken`.
- The claim transaction is short and never publishes Kafka.
- Phase 1A does not create executions, advance `nextRun`, or publish messages.
- Phase 1B atomically creates execution/outbox records, advances `nextRun`, and clears the matching claim.
- Kafka delivery will be at-least-once through a transactional outbox, using stable execution/message identities for idempotency.
- Current Phase 0 due semantics remain unchanged: active jobs with `nextRun <= now` or `nextRun = NULL` are candidates.
- For a claim candidate whose `nextRun` is `NULL`, `scheduledFor` is set to the claim acquisition timestamp in Phase 1A. Phase 1B idempotency design must preserve or explicitly migrate that behavior when stable execution/message identities are introduced.
- Dependency metadata remains documentation-only until Phase 4.

## Consequences

- Two Core instances racing for the same due job cannot both acquire a live lease.
- A live lease is not reclaimable; an expired lease is reclaimable with a new fencing token.
- Claim release is conditional on the exact owner and token, preventing stale owners from releasing newer claims.
- Claim queries stay bounded and deterministic with `ORDER BY next_run ASC NULLS FIRST, id ASC` and a configured batch size.
- PostgreSQL-specific lock behavior is proven with Testcontainers integration tests instead of H2.
- `JobScheduler.scan()` claims due definitions and `JobProducer.prepareDispatch()` creates stable execution/outbox state without Kafka I/O.
- Outbox delivery uses a second lease/fencing token. A failed publish preserves the same message identity and payload for retry.
- Delivery is at-least-once: consumers remain responsible for deduplication by stable execution identity.

## Related Work

- [Job execution flow](../flows/001-job-execution.md)
- [Phase 1 backend/core stabilization roadmap](../../plans/roadmap/phase-1-backend-core-stabilization.md)
