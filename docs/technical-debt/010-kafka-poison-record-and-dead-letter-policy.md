# Kafka Poison Record and Dead-Letter Policy Technical Debt

Status: Deferred cross-service operational policy; not a P12-I3 completion gate and not
an active MVP prerequisite.

## Summary

Omni does not yet define one canonical cross-service policy for Kafka records that
remain undecodable, contract-invalid, or non-retryable after bounded attempts. Current
consumers can report and retry failures, but a durable dead-letter topic, redacted
envelope, retention policy, replay authorization, and operator workflow are not owned
by an approved roadmap increment.

P12-I3 still must identify, report, and isolate malformed job-status records so valid
records in a bounded batch are not silently discarded. It does not need to create the
repository-wide DLT and replay platform described here.

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

1. Keep P12-I3's immediate contract narrow: malformed records are identified, reported,
   isolated from valid records, and never silently acknowledged as successfully applied.
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

- Do not make a DLT a substitute for manual commits, contiguous-prefix safety, or
  idempotent processing.
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
