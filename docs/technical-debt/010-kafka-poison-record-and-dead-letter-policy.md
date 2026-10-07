# Kafka Poison Record and Dead-Letter Policy Technical Debt

## Review — 2026-10-07

| Field           | Assessment                                                                                                                                             |
| --------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Type            | correctness / recovery policy                                                                                                                          |
| Status          | OPEN / deferred durable DLT                                                                                                                            |
| Priority        | P1 if poison records strand work                                                                                                                       |
| Static evidence | Status consumer rethrows exceptions; JobService can ignore invalid/unknown fields. Shared durable quarantine/replay is not implemented by this review. |
| Activation      | Observed poison-record stalls/loss; immediate narrow handling is separate from full DLT infrastructure.                                                |

Refs: [apps/core/src/main/java/com/omni/platform/modules/scheduler/consumers/JobStatusConsumer.java](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/consumers/JobStatusConsumer.java), [apps/core/src/main/java/com/omni/platform/modules/scheduler/services/JobService.java](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/services/JobService.java).

Priority and review status: [technical-debt index](README.md). [Mermaid priority source](priority-order.md). This review adds no runtime verification or completion claim; preserved material below is historical unless reconciled here.

Status: Deferred cross-service operational policy; not a P12-I3 completion gate and not
an active MVP prerequisite.

## Summary

Omni does not yet define one canonical cross-service policy for Kafka records that
remain undecodable, contract-invalid, or non-retryable after bounded attempts. Current
consumers can report and retry failures, but a durable dead-letter topic, redacted
envelope, retention policy, replay authorization, and operator workflow are not owned
by an approved roadmap increment.

P12-I3 still must identify and report malformed job-status records without silently
acknowledging them. Its narrow fallback is partition-local: roll back the affected
partition sub-batch, stop admission at the failing offset, pause that partition, and
raise a deduplicated operator alert. No later offset from that partition is processed or
committed while unrelated partitions continue. Recovery either corrects compatibility or
data and retries, or uses authenticated, audited break-glass discard of exactly the
reviewed immutable record; source coordinates, safe error code, actor, reason, and
timestamp are recorded before committing its next offset and resuming. This procedure
does not retain the rejected payload and therefore does not create the repository-wide
durable quarantine, DLT, retention, or payload-preserving replay platform described here.

## Current Source Assessment

- Platform's job-status listener catches processing exceptions, publishes a processing-
  failed operational event, and rethrows for the Kafka error path.
- Valid but unknown, malformed, or mismatched job-status fields may be logged and
  ignored by `JobService`; behavior differs by validation stage.
- Python consumers also differ in malformed-payload handling; some cannot publish a
  terminal status when execution identity is absent.
- No canonical DLT topic, shared dead-letter envelope, retention contract, replay API,
  or authorization boundary is declared in shared topic configuration or canonical
  Kafka documentation.
- Static source does not prove that poison records currently stall a production topic;
  operational materiality remains evidence-dependent.

## Deferred Capability

A future owner-approved increment may define:

1. failure classification: transient/retryable, contract-invalid, unsupported legacy,
   and permanently non-retryable;
2. bounded retry and backoff ownership without bypassing consumer offset safety;
3. versioned dead-letter envelope containing source topic, partition, offset, timestamp,
   safe error code, payload schema/version, and traceability headers;
4. payload redaction or omission rules so secrets and uncontrolled payloads are not
   copied blindly;
5. dedicated topic naming, partitioning, retention, ACLs, monitoring, and capacity;
6. audited replay/quarantine tooling that validates current contracts before republish;
7. idempotency and compatibility rules so replay cannot duplicate business mutation;
8. producer/consumer tests and canonical operational documentation.

## Recommended Actions

1. Keep P12-I3's immediate contract narrow: typed non-retryable records roll back their
   partition sub-batch, stop admission at the failing offset, pause only that partition,
   and raise a deduplicated alert. Never process or acknowledge a later offset from the
   affected partition; allow unrelated partitions to continue independently. Recovery
   either corrects compatibility/data and retries or records an authenticated, audited
   decision before discarding exactly one reviewed failing offset; never skip a range or
   retain the payload under the narrow Phase 12 procedure.
2. Collect evidence of repeated poison records, retry exhaustion, or partition stalls
   before promoting durable DLT infrastructure.
3. When promoted, design the policy across Java and Python consumers rather than adding
   a Platform-only topic with incompatible envelopes.
4. Reuse Phase 11 `correlationId` and `requestId` only as diagnostic headers; never use
   them as replay idempotency or deduplication identities.
5. Require an explicit business idempotency identity for replayable mutations, including
   deterministic `intentId` for future dataset-writer intents.
6. Keep replay operator-controlled and audited; do not add automatic historical replay
   or direct execution-status rewrites.
7. Update [`docs/data/001-kafka-contracts.md`](../data/001-kafka-contracts.md),
   [`docs/flows/001-job-execution.md`](../flows/001-job-execution.md), topic configuration,
   producer/consumer fixtures, and repository guidance when implementation is approved.

## Contract Impact

| Contract area                     | Deferred impact                                                                                           |
| --------------------------------- | --------------------------------------------------------------------------------------------------------- |
| Kafka/service-to-service protobuf | New versioned dead-letter envelope and topic policy; producer and consumer ownership must be coordinated. |
| Object-storage JSON manifests     | Unchanged.                                                                                                |
| Storage paths/dataset ownership   | Unchanged.                                                                                                |
| Public Java/Python APIs           | Shared failure classification, envelope validation, and replay interfaces may be added.                   |
| Configuration/environment         | Topic names, retry limits, retention, ACLs, and replay controls would be additive with safe defaults.     |

## Non-Goals

- Do not make a DLT a substitute for manual commits, partition-scoped transactions,
  contiguous-prefix safety, or idempotent processing.
- Do not copy arbitrary raw payloads or secrets into a dead-letter record.
- Do not silently skip malformed records.
- Do not automatically replay historical records.
- Do not let browsers publish to Kafka or bypass Platform operator authorization.
- Do not treat diagnostic IDs as idempotency keys.

## Reactivation Triggers

Reassess this debt when repeated non-retryable records stall a partition, exhaust retry
budgets, require recurring manual broker intervention, or create an approved operator
requirement for audited quarantine and replay.

## Verification Status

Static source and documentation inspection only. No build, test, lint, format, Kafka
integration, runtime retry, or replay checks were run.
