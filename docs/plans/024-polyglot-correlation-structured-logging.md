# Plan 024 — MVP Polyglot Correlation and Sync Failure Logging

Plan ID: `024`  
Status: Owner-approved MVP direction; supporting plan; not roadmap-scheduled  
Primary outcome: know which sync failed, when it failed, where it failed, and why  
Scope: Java Platform, Python workers, Kafka, jobs, scheduler outbox, HTTP support lookup, Fluent Bit, and VictoriaLogs  
Delivery rule: implement four independently reviewable increments; production hardening remains technical debt

Selected stack:

```text
structured JSON stdout -> Fluent Bit -> VictoriaLogs
```

Loki is not required. Grafana, Prometheus, VictoriaMetrics, OpenTelemetry, S3/MinIO log archive, Kubernetes, and HA are not MVP dependencies.

## Goal

Make a failed sync diagnosable without searching unrelated console output across services.

Starting from a job, symbol/work item, timestamp, or support ID, the operator must be able to answer:

1. Which sync operation failed?
2. When did it start and fail?
3. Which service and processing stage failed?
4. Which job execution, work item, Kafka record, and retry attempt were involved?
5. What normalized error category/code was reported?
6. What exception and safe diagnostic message explain the failure?
7. Is the failure retryable, and did a later attempt succeed?
8. Which Java -> Kafka -> Python -> Kafka -> Java events belong to the same flow?

This is a debugging MVP, not a complete observability platform.

## Outcome

After the MVP:

- Java and Python emit compatible single-line JSON logs;
- one `correlationId` follows the complete sync flow and survives outbox delay/retry;
- HTTP-triggered work also carries a `requestId`;
- every sync boundary emits a small set of stable lifecycle events;
- failures contain normalized, searchable diagnostic fields;
- Java MDC and Python `ContextVar` scopes are cleared after each request/record/task;
- Fluent Bit collects service stdout and sends it to one VictoriaLogs instance;
- an operator can query one `correlationId` and see the ordered failure trail;
- logging/collector/backend failure never changes the sync business result.

## Dataset Outputs

No analytical dataset output.

Operational logs are not market datasets and are not consumed by trading algorithms.

## Metadata Outputs

No dataset metadata output.

DatasetManifest, READY publication, and `dataVersion` lineage are unchanged.

## Algorithm Feature Outputs

No direct algorithm feature output.

## Algorithms Unlocked

No trading algorithm is unlocked. The MVP makes ingestion and analysis failures easier to diagnose.

## MVP Boundary

### Included

- shared `correlationId`, `requestId`, and existing job/execution identifiers;
- Java scoped MDC and Python scoped `ContextVar`;
- structured JSON for Platform, Analyzer, Ingestor, and Query Service;
- sync lifecycle events and a small error taxonomy;
- HTTP ingress/response header propagation needed for support lookup;
- Kafka producer/consumer header propagation;
- durable correlation on new job execution and scheduler-outbox rows;
- retry/failure/status propagation;
- minimal browser TypeScript header propagation and support-ID display;
- optional local/VM Compose profile with Fluent Bit and VictoriaLogs;
- saved debug queries and a short failure-investigation runbook.

### Explicitly excluded from MVP

- Kubernetes and Helm deployment;
- `VLCluster`, replicated VictoriaLogs, or multi-zone HA;
- VictoriaLogs snapshot backup/restore and disaster-recovery objectives;
- production SLO, RPO, RTO, capacity certification, and long outage testing;
- `vmauth`, multitenancy, mTLS, and enterprise RBAC;
- S3/R2/MinIO raw log archive;
- Grafana dashboards and a new alerting subsystem;
- OpenTelemetry traces, `traceId`, and `spanId`;
- dynamic sampling and advanced cardinality analysis;
- historical database backfill and immediate `NOT NULL` enforcement;
- Node/Pino implementation while no Node backend exists;
- browser log ingestion;
- automated privacy deletion workflow.

Excluded work is recorded under the production logging section of
`docs/technical-debt/004-post-mvp-roadmap-work.md`. None of it blocks the MVP.

## Identifier Semantics

| Field               | Lifetime                               | Source                             | MVP persistence                   | Purpose                                          |
| ------------------- | -------------------------------------- | ---------------------------------- | --------------------------------- | ------------------------------------------------ |
| `correlationId`     | Entire business flow                   | Accepted/generated at root         | New job execution and outbox rows | Primary end-to-end debug key                     |
| `requestId`         | Originating HTTP request/trigger chain | Accepted/generated at HTTP ingress | Outbox when available             | Support/request lookup                           |
| `executionId`       | One job execution                      | Existing scheduler domain          | Already durable                   | Job state and retry lineage                      |
| `parentExecutionId` | Parent/fan-out relationship            | Existing scheduler domain          | Already durable                   | Relate child executions                          |
| `triggerRequestId`  | Existing manual-trigger audit request  | Existing job operations API        | Existing audit persistence        | Distinguish domain request from HTTP `requestId` |

Do not alias these values. They are diagnostic metadata, never authentication, authorization, ownership, idempotency, or deduplication keys.

Canonical wire names:

```text
HTTP:
  X-Correlation-ID
  X-Request-ID

Kafka:
  x-correlation-id
  x-request-id
```

The existing manual-job API field and route parameter named `requestId` remain wire-compatible. Structured logs call that value `triggerRequestId`; HTTP transport context remains `requestId`.

## Current-State Findings

Scan base: `main` at `476d61d692525e484a28bb9c5ffe903b11faa3be`.

- Platform Logback references MDC `traceId`, but no shared context populates it.
- Kafka publishers/consumers do not propagate application correlation headers.
- Job execution and scheduler outbox rows do not persist correlation.
- Python services have no shared `ContextVar` logging context.
- TypeScript is currently browser-only.
- Core error handling creates a failure-only request ID that is disconnected from preceding logs.
- Logs cannot currently be queried in one place by business flow.

## Contract Ownership

Add:

```text
libs/contracts/observability/
  correlation-context.schema.json
  log-event.schema.json
  README.md
```

The contract defines:

- canonical field names and JSON types;
- HTTP and Kafka header names;
- ID validation and maximum length;
- required MVP lifecycle/error fields;
- `schemaVersion: 1`;
- prohibited fields and basic truncation/redaction rules.

Runtime code remains language-specific:

```text
Java   -> apps/core/.../shared/infrastructure/observability
Python -> libs/py-common/py_common/observability
Web    -> apps/omni-console HTTP client helper
```

Do not add correlation fields to every Protobuf payload. Headers carry transport context; jobs/outboxes carry durable context.

### Contract Impact

| Surface                  | MVP impact                                                   |
| ------------------------ | ------------------------------------------------------------ |
| Kafka/service Protobuf   | No payload change; additive headers only                     |
| Object-storage manifests | No change                                                    |
| Dataset paths/ownership  | No change                                                    |
| Java API                 | Internal observability helpers                               |
| Python API               | Shared `py-common.observability` helpers                     |
| HTTP                     | Additive request/response headers                            |
| Database                 | Nullable correlation/request columns for new job/outbox rows |
| Configuration            | JSON log mode plus optional collector/backend settings       |

Consumers must continue accepting Kafka records without headers during rollout.

## Sync Lifecycle Events

Use a stable `eventName`, not free-text messages, as the primary query field.

| Event                    | Level | Required timing                                     | Purpose                                |
| ------------------------ | ----- | --------------------------------------------------- | -------------------------------------- |
| `sync.started`           | INFO  | When a worker begins one logical work item          | Establish start time and attempt       |
| `sync.completed`         | INFO  | After output/status persistence succeeds            | Establish success and duration         |
| `sync.failed`            | ERROR | At the boundary that owns the failed work item      | Primary failure record                 |
| `sync.retry_scheduled`   | WARN  | When another attempt will occur                     | Explain retry delay/attempt            |
| `sync.blocked`           | WARN  | When a dependency prevents execution                | Separate dependency state from failure |
| `outbox.dispatch_failed` | ERROR | When scheduler outbox publish fails                 | Locate pre-worker Kafka failure        |
| `kafka.consume_failed`   | ERROR | When record processing fails before a domain result | Locate transport/decoding failure      |

One boundary owns the canonical `sync.failed` event for a work item. Lower layers may log supporting errors, but must not emit multiple canonical failure events for the same attempt.

### Required failure fields

```text
schemaVersion
timestamp
level
service
environment
eventName
message
correlationId
requestId                  # when available
executionId
parentExecutionId          # when available
jobDefinitionId            # when available
workType
workKey
stage
attempt
retryable
errorCategory
errorCode
exceptionType
exceptionMessage
durationMs                 # when start time is known
topic partition offset     # Kafka consumer failure
```

### MVP error taxonomy

| `errorCategory`       | Example causes                                              |
| --------------------- | ----------------------------------------------------------- |
| `PROVIDER_RATE_LIMIT` | HTTP 429, provider throttle                                 |
| `PROVIDER_AUTH`       | Invalid/expired provider credentials                        |
| `PROVIDER_TIMEOUT`    | Provider read/connect timeout                               |
| `PROVIDER_RESPONSE`   | Invalid response shape or provider error                    |
| `NETWORK`             | DNS, connection reset, unreachable host                     |
| `VALIDATION`          | Invalid command/payload/data                                |
| `DEPENDENCY_BLOCKED`  | Required upstream state/data unavailable                    |
| `KAFKA`               | Publish, consume, serialization, or deserialization failure |
| `DATABASE`            | Job/outbox/status persistence failure                       |
| `STORAGE`             | MinIO/S3/Parquet read/write failure                         |
| `INTERNAL`            | Unclassified application failure                            |

`errorCode` is a stable application/provider code where available. Do not use the full exception message as a code.

The first implementation may map unknown errors to `INTERNAL`; it must preserve `exceptionType`, a sanitized `exceptionMessage`, and stack trace so the taxonomy can be refined later.

## Canonical Log Example

```json
{
  "schemaVersion": 1,
  "timestamp": "2026-09-18T10:00:00.000Z",
  "level": "ERROR",
  "service": "ingestor",
  "environment": "local",
  "eventName": "sync.failed",
  "message": "Stock price sync failed",
  "correlationId": "4a948e9b-3c3d-47e3-aaf9-8409df7b3256",
  "requestId": "6d0de49c-c31d-4f87-b750-72bc258e1f71",
  "executionId": "598d1db0-5e97-45a4-bfaa-dcff35069563",
  "workType": "SYMBOL",
  "workKey": "FPT",
  "stage": "provider.fetch_eod",
  "attempt": 3,
  "retryable": true,
  "errorCategory": "PROVIDER_RATE_LIMIT",
  "errorCode": "HTTP_429",
  "exceptionType": "RetryError",
  "exceptionMessage": "Provider request exhausted retry policy",
  "durationMs": 30124,
  "topic": "sync-stock-prices-job",
  "partition": 0,
  "offset": 100
}
```

Optional fields are omitted rather than filled with misleading placeholders.

## Runtime Context

### Java

Add:

- immutable `CorrelationContext`;
- ID generation/validation helpers;
- `MdcScope implements AutoCloseable`;
- HTTP request filter;
- Kafka header helpers at shared publisher/consumer boundaries;
- explicit snapshot/task decorator only where an executor boundary requires it.

Use SLF4J MDC. Do not add another raw `ThreadLocal<Map<...>>`. Always restore/clear scope in `finally` or try-with-resources.

Remove the misleading `traceId:-system` Logback placeholder and emit JSON in production/observability mode.

### Python

Add under `libs/py-common/py_common/observability`:

- token-based `ContextVar` install/reset;
- JSON logging formatter/filter;
- aiokafka header extraction/injection;
- shared failure-event helper;
- optional ASGI middleware for services exposing HTTP.

Reset context after success, exception, cancellation, and consumer shutdown.

### TypeScript

The browser console only:

- sends `X-Correlation-ID` and `X-Request-ID`;
- reads returned IDs;
- shows the IDs in a support/error detail.

Do not add Pino, `AsyncLocalStorage`, or browser log shipping in the MVP.

## Propagation and Persistence

### HTTP

- accept one valid canonical UUID per header;
- generate missing/invalid values;
- never log rejected raw header values;
- return both IDs on success and mapped errors;
- never use IDs for authorization;
- propagate only to approved internal Omni HTTP destinations.

### Kafka

- producer removes duplicate canonical keys and adds one value per header;
- consumer validates headers before processing;
- missing/invalid legacy headers generate safe values and do not fail the record;
- retry/status/derived-event publishers preserve active IDs;
- every consumer closes context before the next record.

### Jobs and outbox

Add nullable columns:

```sql
job_execution_histories.correlation_id UUID
job_execution_histories.request_id VARCHAR(128)
scheduler_outbox_messages.correlation_id UUID
scheduler_outbox_messages.request_id VARCHAR(128)
```

MVP migration rules:

- all new root executions receive a correlation ID;
- children inherit the parent's correlation ID;
- new outbox rows snapshot the current durable IDs;
- dispatcher reads the row snapshot, never ambient MDC;
- retries preserve the same IDs;
- existing historical rows may remain null;
- no historical backfill or immediate `NOT NULL` constraint is required for MVP.

The future notification outbox must adopt the same snapshot semantics when implemented, but notification-outbox implementation does not block this plan.

## Minimal Collection

Applications emit one JSON object per line to stdout.

Fluent Bit:

- runs as a separate Compose service;
- reads container logs without mounting the Docker socket;
- parses Docker wrapping and application JSON;
- uses bounded filesystem buffering;
- adds trusted `service`, `environment`, and `instance` fields;
- sends records to VictoriaLogs;
- does not block application readiness.

VictoriaLogs:

- runs as one `VLSingle` Compose service with persistent local storage;
- binds only to the private Compose network or localhost;
- uses explicit time and disk retention suitable for local/home-lab use;
- provides the initial query UI;
- is not described as HA or fully production-ready.

MVP VictoriaLogs stream fields:

```text
service
environment
eventName
```

Do not make `correlationId`, `requestId`, `executionId`, `workKey`, exception text, or raw HTTP paths stream fields or metrics labels.

## Debug Queries and Runbook

Document equivalent LogsQL queries for:

```text
one correlationId ordered by timestamp
all sync.failed events in a time range
failures by service and stage
failures by workType/workKey
provider rate-limit failures
retryable failures with no later sync.completed
outbox dispatch failures
Kafka record by topic/partition/offset
```

Failure investigation:

1. Start from `executionId`, `workKey`, timestamp, or support ID.
2. Find the canonical `sync.failed` event.
3. Copy its `correlationId`.
4. Query all records with that correlation ID ordered by time.
5. Identify the last successful stage and first failed stage.
6. Inspect `errorCategory`, `errorCode`, `retryable`, attempt, and exception fields.
7. Check whether a later `sync.completed` exists for the same execution/work item.
8. Use Kafka coordinates only when record-level inspection/replay is required.

## Implementation Increments

### 024-I1 — Context, schema, and lifecycle events

Status: pending; not roadmap-scheduled.

- Add observability JSON schemas and field glossary.
- Implement Java MDC and Python `ContextVar` scopes.
- Configure one-line JSON output.
- Add lifecycle-event helper and MVP error taxonomy.
- Add redaction/truncation rules for exception messages and stack traces.
- Add isolation, cleanup, schema, and error-mapping tests.

Exit: representative Java/Python `sync.started`, `sync.completed`, and `sync.failed` events validate against one schema with no context leakage.

### 024-I2 — HTTP, Kafka, job, and outbox correlation

Status: pending.  
Depends on: 024-I1.

- Add HTTP request filter/middleware and response headers.
- Add minimal console header propagation/support display.
- Update all shared Java/Python Kafka produce/consume boundaries.
- Add nullable job/outbox columns and populate new rows.
- Preserve context through retries, status messages, and process restart.
- Keep legacy headerless records and historical null rows compatible.

Exit: Java -> Kafka -> Python -> Kafka -> Java keeps one correlation ID, and outbox retry after restart uses the stored IDs.

### 024-I3 — Sync failure diagnostics

Status: pending.  
Depends on: 024-I1 and 024-I2.

- Instrument sync entry/exit/failure boundaries in Platform, Ingestor, and Analyzer.
- Map provider, Kafka, database, storage, validation, and dependency failures.
- Record stage, attempt, retryable, duration, work identity, and Kafka coordinates.
- Ensure exactly one canonical `sync.failed` per work-item attempt.
- Add integration tests for provider 429/timeout/bad response, validation, storage, Kafka, and unknown exception paths.

Exit: every tested failure answers which sync, when, where, why, retryability, and correlation trail.

### 024-I4 — Central search and debug runbook

Status: pending.  
Depends on: 024-I1 through 024-I3.

- Add optional Compose profile with pinned Fluent Bit and VictoriaLogs images.
- Add bounded Fluent Bit filesystem buffer and persistent VictoriaLogs volume.
- Add local/home-lab retention and private binding.
- Add saved/example LogsQL queries and the failure runbook.
- Test collector/backend unavailable and restart behavior without failing sync processing.

Exit: an operator finds a failed sync in VictoriaLogs and reconstructs the Java/Python/Kafka flow from one correlation ID.

## Verification

Required coverage:

- Java MDC nesting, cleanup, and reused-thread isolation;
- Python `ContextVar` reset, concurrent-task isolation, and cancellation;
- structured-log schema and prohibited-field fixtures;
- HTTP missing/invalid/duplicate ID handling;
- Kafka inject/extract, missing legacy headers, retry preservation, and cleanup;
- new job/outbox row persistence and restart/retry behavior;
- lifecycle event success/failure ordering;
- one canonical failure per attempt;
- error taxonomy mapping and safe unknown fallback;
- Fluent Bit parsing/buffering and VictoriaLogs search smoke test;
- collector/backend failure does not change business processing.

Required end-to-end scenario:

```text
manual/scheduled trigger
  -> Platform job execution
  -> scheduler outbox
  -> Kafka
  -> Python sync handler
  -> forced provider/storage failure
  -> status Kafka
  -> Platform
  -> VictoriaLogs correlation query
```

Assert:

- one `correlationId` across all boundaries;
- correct `executionId`, `workType`, and `workKey`;
- one canonical `sync.failed` for the failed attempt;
- failure timestamp, stage, attempt, retryability, category, code, exception, and Kafka coordinates;
- a clean context for the next unrelated request/record.

Candidate Nx targets must be confirmed from each `project.json` before execution:

```text
nx run contracts:test
nx run platform:build
nx run py-common:lint
nx run py-common:test
nx run analyzer:lint
nx run analyzer:test
nx run ingestor:lint
nx run ingestor:test
nx run query-service:lint
nx run query-service:test
nx run omni-console:lint
nx run omni-console:typecheck
nx run omni-console:test
```

Executable checks are **not run** for this documentation-only revision.

## MVP Acceptance Criteria

- [ ] Java and Python logs validate against one schema.
- [ ] Every backend log is one JSON line and contains no prohibited payload/credential fields.
- [ ] Java uses scoped MDC without a second raw ThreadLocal store.
- [ ] Python uses token-reset `ContextVar`.
- [ ] HTTP returns correlation/request IDs on success and error.
- [ ] Kafka producers/consumers preserve or safely create canonical headers.
- [ ] New job executions and scheduler-outbox rows persist correlation.
- [ ] Retry and restart preserve the same correlation ID.
- [ ] Every sync attempt emits start plus either completed or one canonical failed event.
- [ ] Failed events contain when, service, stage, work identity, attempt, retryability, category/code, and safe exception details.
- [ ] Java -> Kafka -> Python -> Kafka -> Java is queryable by one correlation ID.
- [ ] Fluent Bit/VictoriaLogs are optional and never affect application readiness.
- [ ] A documented query/runbook locates the cause of a failed sync.
- [ ] Production hardening remains explicitly deferred and does not block MVP completion.

## Technical Debt Boundary

Move to post-MVP technical debt:

- Kubernetes/Helm and cluster operations;
- VictoriaLogs HA or `VLCluster`;
- production authentication proxy beyond private/localhost MVP binding;
- backup/restore, RPO/RTO, and disaster recovery;
- numeric SLOs, formal load/capacity certification, and long outage tests;
- full alerting/dashboards stack;
- raw S3/R2/MinIO log archive;
- OpenTelemetry tracing;
- advanced sampling/cardinality governance;
- historical DB backfill and enforced non-null correlation;
- full privacy deletion automation;
- future Node logging runtime.

These items may be promoted independently after the MVP proves useful failure diagnostics.

## Repository Guidance Updates

MVP implementation must review and update where applicable:

```text
docs/INDEX.md
docs/README.md
docs/data/001-kafka-contracts.md
docs/data/003-database.md
docs/flows/001-job-execution.md
apps/core and Python service READMEs
AGENTS.md
CLAUDE.md
.roo/rules/
```

This documentation-only revision updates the plan, registries, and post-MVP technical-debt owner. Runtime/canonical flow/data docs change when implementation begins.

## Non-Goals

- No direct application appender to VictoriaLogs, S3, or MinIO.
- No Loki dependency.
- No PostgreSQL log storage.
- No browser log shipping.
- No logging of payload bodies, authorization values, cookies, credentials, provider tokens, SQL text, or arbitrary headers.
- No trace IDs in business payloads.
- No production-ready or HA claim for the MVP Compose stack.
- No blocking the MVP on deferred production-hardening work.
