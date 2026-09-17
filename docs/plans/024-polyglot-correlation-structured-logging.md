# Plan 024 — Polyglot Correlation and Structured Logging

Canonical status and schedule owner: [Phase 11 increments P11-I1 through P11-I5](../../plans/roadmap/implementation-increments.md). This document is the canonical supporting implementation design and does not independently own statuses or readiness.

Plan ID: `024`
Status: Owner-approved Phase 11 supporting plan; implementation not started
Scope: Java Platform, Python services, TypeScript console, HTTP, Kafka, jobs, and durable outbox boundaries
Roadmap: Execute P11-I1 through P11-I5 sequentially after completed P4-I3 and P8-I5; do not implement the full plan as one change
Selected log stack: structured JSON → Fluent Bit → VictoriaLogs; Loki is not required

## Goal

Establish one durable business-correlation model and one structured-log contract across Omni's Java, Python, and browser boundaries without coupling business availability to a log collector or tracing backend.

## Outcome

After P11-I1 through P11-I5 complete, HTTP requests, scheduled and manual jobs, durable outbox retries, Kafka processing, and status callbacks preserve independently meaningful `correlationId`, `requestId`, and `executionId` values. Java and Python emit schema-compatible structured JSON, the browser propagates identifiers without bundling a server logger, and an optional Fluent Bit/VictoriaLogs profile provides searchable logs while remaining fail-open.

## Dataset Outputs

No analytical dataset output.

## Metadata Outputs

No dataset metadata output.

## Algorithm Feature Outputs

No direct algorithm feature output.

## Algorithms Unlocked

No trading algorithm directly. Durable correlation and structured logs make pipeline failures, retries, stale inputs, duplicate processing, and latency regressions diagnosable without changing analytical behavior.

## Decision

Use three separate identifiers with fixed semantics:

| Field           | Lifetime                                                             | Durable storage                                              | Purpose                              |
| --------------- | -------------------------------------------------------------------- | ------------------------------------------------------------ | ------------------------------------ |
| `correlationId` | Entire business flow across HTTP, jobs, Kafka, retries, and services | Required on job execution and durable outbox rows            | Primary end-to-end investigation key |
| `requestId`     | Originating request/trigger and its asynchronous chain               | Required on outbox when available; optional on job execution | Operator-facing request lookup       |
| `executionId`   | One durable job execution                                            | Existing job execution identity                              | Job status, retry, and lineage       |

Do not alias these values. Do not replace them with OpenTelemetry `traceId`. A later tracing increment may add `traceId` and `spanId` without changing the business correlation model.

Canonical propagation names:

```text
HTTP
  X-Correlation-ID
  X-Request-ID

Kafka
  x-correlation-id
  x-request-id
```

`correlationId` remains unchanged for the whole flow. Child executions inherit it and receive their own `executionId`. Outbox retries reuse the values captured when the row was created.

## Main-Branch Scan

Scan base: `main` on 2026-09-17.

- Core's Logback pattern reads MDC `traceId`, but Core has no tracing dependency or shared MDC population. The field normally falls back to `system`.
- `KafkaPublisher` publishes key and payload only. `AbstractConsumer` records Kafka coordinates but does not extract correlation headers or open a scoped logging context.
- `job_execution_histories` has no `correlation_id` or transport request identifier.
- `scheduler_outbox_messages` stores payload and delivery state but no immutable correlation snapshot.
- Python services use ordinary `logging.basicConfig`; shared ASGI and Kafka helpers have no `ContextVar` correlation support.
- Python status publishers do not inject Kafka correlation headers.
- TypeScript currently exists as the React/Vite `omni-console`; there is no Node backend service on `main`.
- Console API requests do not create or propagate correlation/request headers.
- `libs/contracts` currently owns Protobuf service contracts only. Its Nx targets do not validate JSON schemas.
- Notification outbox is planned but not implemented on `main`; its implementation must adopt this model from the start.

## Architecture

```text
Omni Console
  -> HTTP headers
Core / Query Service
  -> scoped log context
  -> job execution + outbox correlation snapshot
Kafka
  -> headers
Analyzer / Ingestor
  -> scoped log context
  -> Kafka headers
Core
```

### Contract ownership

Keep machine-readable names and shapes in `libs/contracts`, but keep runtime logging implementations language-specific.

Proposed contract files:

```text
libs/contracts/observability/log-event.schema.json
libs/contracts/observability/correlation-context.schema.json
libs/contracts/observability/README.md
```

The contracts define:

- canonical structured-log field names and types;
- HTTP and Kafka header names;
- identifier validation and length limits;
- required versus optional fields;
- schema version.

Do not add logging libraries, MDC helpers, Python context code, or TypeScript runtime code to `libs/contracts`. Update the contracts Nx targets so JSON schemas are included in lint/test checks.

Do not add correlation fields to every Protobuf business payload. HTTP/Kafka headers carry transport context; job and outbox tables provide durable correlation.

### Java

Proposed location:

```text
apps/core/src/main/java/com/omni/platform/shared/infrastructure/observability/
```

Add:

- `CorrelationContext`: immutable validated values.
- `CorrelationIds`: generation and validation.
- `MdcScope implements AutoCloseable`: install and restore fields.
- `RequestCorrelationFilter`: HTTP extraction/generation and response headers.
- Kafka extraction/injection helpers or Spring interceptors.
- `CorrelationTaskDecorator`: explicit context copy for approved executor boundaries.

Use SLF4J MDC as the Java per-thread log context. MDC is already thread-local in its runtime behavior; do not introduce a second raw `ThreadLocal<Map<...>>`. Every consumer, scheduler, executor, and request boundary must close/clear its scope in `finally` or try-with-resources.

Do not assume MDC automatically follows `@Async`, executor pools, callbacks, or virtual threads. Copy only the allowlisted context through an explicit decorator/snapshot.

Use Logback structured JSON output. Prefer Spring Boot-supported structured logging when it satisfies the schema; otherwise use one maintained JSON encoder behind Logback. An async appender may buffer console/file output, but no application appender may upload directly to MinIO/S3.

### Python

Proposed location:

```text
libs/py-common/py_common/observability/
  context.py
  logging.py
  http.py
  kafka.py
```

Use `ContextVar`, not `threading.local()`, because the services use asynchronous ASGI and aiokafka flows.

Add:

- token-based context install/reset helpers;
- ASGI middleware for HTTP headers;
- logging filter/formatter that emits the canonical JSON schema;
- aiokafka header extraction/injection helpers;
- wrappers that reset context after every record, including failures and cancellation.

Use one maintained JSON logger/formatter through `py-common`. Business handlers must not manipulate global logging state directly.

### TypeScript

Current TypeScript scope is browser-only.

Add a small console HTTP client helper that:

- creates or accepts a `correlationId` for a user operation;
- generates a new `requestId` for each outbound HTTP request;
- sets both canonical headers;
- reads returned IDs for error/support display;
- never uploads browser console logs directly to MinIO.

Do not add Pino or `AsyncLocalStorage` to the React bundle.

If a Node service is introduced later, create `libs/ts-common/observability` and use:

- Node `AsyncLocalStorage` for scoped context;
- Pino-compatible structured JSON;
- HTTP and Kafka middleware using the same contracts;
- mandatory cleanup/isolation tests.

## Canonical Structured Log

All backend runtimes emit the same logical fields. Optional fields are omitted rather than filled with misleading placeholders.

```json
{
  "schemaVersion": 1,
  "timestamp": "2026-09-17T10:00:00.000Z",
  "level": "INFO",
  "service": "ingestor",
  "environment": "local",
  "logger": "app.handlers.eod",
  "correlationId": "4a948e9b-3c3d-47e3-aaf9-8409df7b3256",
  "requestId": "req-01",
  "executionId": "598d1db0-5e97-45a4-bfaa-dcff35069563",
  "parentExecutionId": null,
  "jobDefinitionId": "2f917bcb-ef30-43e8-b646-3a8f4b623688",
  "workType": "SYMBOL",
  "workKey": "FPT",
  "topic": "sync-symbols-job",
  "partition": 0,
  "offset": 100,
  "message": "Processing EOD command"
}
```

Canonical fields:

```text
schemaVersion
timestamp
level
service
environment
logger
message
correlationId
requestId
executionId
parentExecutionId
jobDefinitionId
workType
workKey
topic
partition
offset
messageKey
httpMethod
httpPath
httpStatus
durationMs
exceptionType
exceptionMessage
stackTrace
```

Never log payload bodies, authorization values, cookies, credentials, Telegram tokens, SQL text, or arbitrary request headers. IDs belong in logs, not metric labels.

## Persistence

Add a new migration after rechecking the latest Flyway version at implementation time. On the scanned `main`, `V10` is the next candidate.

### Job execution

Add:

```sql
correlation_id UUID NOT NULL
request_id VARCHAR(128)
```

Migration rules:

- backfill each existing root execution with a generated UUID;
- child executions inherit the parent's correlation ID when the relationship is reliable;
- otherwise generate a safe independent correlation ID;
- add an index on `correlation_id`;
- do not hide these values only inside `meta_json`.

Scheduled work generates a correlation ID when the root execution is created. HTTP-triggered work uses the validated incoming/generated correlation ID.

The existing manual-trigger `requestId` is the default `X-Request-ID` and durable request identifier for that manual-trigger flow. If no valid request ID is available at an ingress or background boundary, generate one and propagate it under `X-Request-ID`/`x-request-id`. Do not create a second competing request identifier for the same flow; `correlationId` and `executionId` remain separate.

### Scheduler outbox

Persist an immutable snapshot:

```sql
correlation_id UUID NOT NULL
request_id VARCHAR(128)
```

The dispatcher reads these columns and injects Kafka headers. It must not read whatever MDC happens to exist on the scheduled dispatcher thread. Retries preserve the original values.

A constrained `headers_json` is acceptable only if future tracing headers require it. If introduced, allowlist keys and enforce per-value and aggregate size limits.

### Notification outbox

The future notification outbox must use the same base correlation fields and retry semantics from its first migration. A shared Java outbox base type may expose correlation behavior, while each outbox remains independently owned and queryable.

## Processing Rules

### HTTP ingress

1. Validate incoming IDs.
2. Generate missing IDs.
3. Open scoped context.
4. Return both IDs in response headers.
5. Reuse them in mapped error responses.
6. Always close context.

Suggested validation:

- `correlationId`: canonical UUID.
- `requestId`: 1-128 visible ASCII characters matching `[A-Za-z0-9._:-]+`.
- Invalid values are replaced and rate-limited warnings do not echo raw input.

### Kafka produce

1. Snapshot the current allowlisted context.
2. Inject lowercase ASCII headers.
3. Preserve domain identifiers in the existing payload contract.
4. Do not fail business processing solely because context is absent during rollout.

### Kafka consume

1. Extract and validate headers before payload handling.
2. Generate missing values for legacy records.
3. Open scoped log context with Kafka coordinates.
4. Add domain fields after successful decoding.
5. Process, publish, retry, or report failure.
6. Clear/reset context in all paths.

### Jobs and async execution

- Root job: create or inherit the flow correlation ID.
- Child job: inherit correlation ID; create a new execution ID.
- Retry of the same execution/outbox record: preserve correlation ID.
- New operator action: create a new request ID; reuse correlation ID only when intentionally continuing an existing flow.
- Background scheduler/executor threads start clean unless a context snapshot is explicitly installed.

## Log Collection and Deployment

Application services emit structured JSON to stdout or rolling files. The selected runtime is:

```text
Java / Python / future Node
  -> JSON stdout or file
  -> Fluent Bit
  -> VictoriaLogs
```

- Fluent Bit owns collection, disk buffering, retry, parsing, and delivery.
- VictoriaLogs is the only required log backend; Loki is not part of the target stack.
- Grafana is optional because VictoriaLogs provides a built-in query UI.
- Prometheus and VictoriaMetrics are metrics systems and remain outside this logging plan.
- Application services must not maintain a logging connection pool or upload one object per record.
- Observability remains an optional deployment profile; application health must not depend on Fluent Bit or VictoriaLogs.

Deployment progression:

| Environment         | Deployment                                                         |
| ------------------- | ------------------------------------------------------------------ |
| Local default       | JSON stdout only                                                   |
| Local observability | One Fluent Bit container + one VictoriaLogs container              |
| Single cloud VM     | Reuse the Compose profile with persistent VictoriaLogs storage     |
| Kubernetes          | Fluent Bit DaemonSet + VictoriaLogs `VLSingle`/StatefulSet         |
| Larger cluster      | Fluent Bit per node + `VLCluster` through VictoriaMetrics Operator |

If raw long-term archive is later required, configure an explicit second Fluent Bit output to MinIO/S3:

```text
Fluent Bit
  -> VictoriaLogs        # realtime search, bounded retention
  -> MinIO/S3 JSON.gz    # optional raw archive
```

The archive is optional and must not be confused with VictoriaLogs persistence. Use a service/date/hour object layout only when the archive output is enabled.

Browser logs are excluded unless a separate authenticated, rate-limited, redacted ingestion API is approved.

## Contract Impact

| Contract area                     | Decision                                                                                                                                                                                                      |
| --------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Kafka/service-to-service protobuf | Protobuf payloads remain unchanged. Add optional, validated `x-correlation-id` and `x-request-id` Kafka headers; update every producer and consumer together with backward-compatible header-absent handling. |
| Object-storage JSON manifest      | Unchanged. Correlation does not alter READY, immutable version, or `dataVersion` lineage semantics.                                                                                                           |
| Storage path/dataset ownership    | Unchanged. Optional raw log archive, if later enabled, is an operations output owned by Fluent Bit and is not a business dataset or routing contract.                                                         |
| Public Java/Python API            | Add small shared Java observability APIs and `py_common.observability` APIs. Browser HTTP helpers add canonical headers. Callers and implementations migrate incrementally in P11-I1 through P11-I4.          |
| Configuration/environment         | Add structured-log and optional collector/backend settings with fail-open defaults. VictoriaLogs is optional; application health cannot depend on it.                                                         |
| Database                          | P11-I3 adds additive correlation/request columns to job execution and scheduler/notification outbox persistence. Schema and compatible readers deploy before writers; retries preserve immutable snapshots.   |

Compatibility and rollout are consumer-first: accept absent headers, deploy schema/readers before writers, then enable producers. Physical object paths never enter Kafka business messages, generated protobuf output is not edited, and existing READY-last behavior is unchanged.

## Repository Guidance Updates

Implementation must review and synchronize:

- `docs/architecture/001-system-overview.md`;
- `docs/flows/001-job-execution.md` and affected service flows;
- `docs/data/001-kafka-contracts.md` for transport-header conventions;
- `docs/data/003-database.md` for durable correlation columns;
- `docs/development/001-where-to-change.md`;
- Platform, Analyzer, Ingestor, Query Service, Console, contracts, and `py-common` README files where their runtime boundary changes;
- `AGENTS.md`, `CLAUDE.md`, and `.roo/rules/` only when repository workflow or ownership guidance changes.

## Implementation Increments

### P11-I1 — Contract and structured logging

- Add observability JSON schemas and contract tests.
- Implement Java `MdcScope` and canonical JSON output.
- Implement Python `ContextVar` and canonical JSON output.
- Remove the misleading Logback `traceId:-system` placeholder.
- Add isolation, nesting, cleanup, and schema-conformance tests.

Exit: representative Java and Python log events validate against one schema without stale context leakage.

### P11-I2 — HTTP and console propagation

- Add Core request-correlation filter.
- Add shared Python ASGI middleware.
- Update exception responses to reuse active IDs.
- Update console HTTP clients to send and receive the canonical headers.
- Configure CORS to allow request headers and expose response headers.
- Add integration tests for missing, valid, invalid, and concurrent IDs.

Exit: a console request can be located in Core or Query Service logs by correlation/request ID.

### P11-I3 — Job and outbox persistence

- Add migration and entity fields.
- Populate root/child execution correlation.
- Snapshot correlation/request IDs in scheduler outbox rows.
- Include correlation fields in job API responses where operationally useful.
- Define notification outbox adoption requirements.

Exit: delayed dispatch and retry retain the original correlation ID after process restart.

### P11-I4 — Kafka propagation

- Add Java and Python header helpers.
- Update every producer and consumer boundary.
- Update scheduler outbox dispatch to publish stored headers.
- Cover status, upsert, signal, metadata, notification, and intraday paths.
- Test absent, malformed, duplicated, oversized, and invalid UTF-8 headers.

Exit: Java → Kafka → Python → Kafka → Java logs share one correlation ID and retain the expected request/execution IDs.

### P11-I5 — Fluent Bit and VictoriaLogs deployment

- Add an optional Compose observability profile with Fluent Bit and VictoriaLogs.
- Add persistent VictoriaLogs storage and an explicit retention period.
- Add Fluent Bit disk buffering, bounded retry, health checks, and fail-open behavior.
- Add Kubernetes-ready configuration for Fluent Bit DaemonSet and VictoriaLogs `VLSingle`; defer `VLCluster` until capacity requires it.
- Keep Grafana optional and keep Loki out of the deployment.
- Add optional MinIO/S3 raw JSON archive only as a separate Fluent Bit output.
- Verify collector/backend/archive outages cannot block business processing.
- Add OpenTelemetry only if span timing is needed; keep it independent of durable business correlation.

Exit: logs are searchable in VictoriaLogs by service, time range, and correlation fields; the same application images run with observability disabled, on Compose, and on Kubernetes.

## Verification

Required unit coverage:

- Java MDC nested scope restore and thread-pool isolation.
- Python `ContextVar` nested reset and concurrent task isolation.
- Future Node `AsyncLocalStorage` isolation if a Node service exists.
- HTTP identifier validation/generation.
- Kafka extraction/injection and cleanup.
- Job parent/child inheritance.
- Outbox restart/retry preservation.
- JSON schema validation and secret redaction.

Required integration flow:

```text
Console HTTP
  -> Core
  -> job execution
  -> scheduler outbox
  -> Kafka
  -> Analyzer or Ingestor
  -> status Kafka
  -> Core
```

Assert the same `correlationId` at every boundary. Assert the expected `requestId` and `executionId` semantics independently.

Required repository checks remain subject to the approval gate; confirm each target before implementation:

```text
nx run contracts:test
nx run platform:test
nx run platform:build
nx run py-common:test
nx run analyzer:test
nx run ingestor:test
nx run query-service:test
nx run omni-console:test
nx run omni-console:typecheck
nx affected -t test,lint,build
```

This plan-only roadmap reconciliation does not run implementation checks. Each increment records only checks applicable to its changed projects, followed by exact-head CI evidence before completion.

## Acceptance Criteria

- [ ] One canonical schema is validated across Java and Python backend logs.
- [ ] Browser TypeScript propagates IDs without bundling a server logger.
- [ ] Future Node services have an explicit `AsyncLocalStorage` and Pino direction.
- [ ] Java uses scoped MDC without an additional raw ThreadLocal store.
- [ ] Python uses token-reset `ContextVar` scopes.
- [ ] Every HTTP response exposes correlation and request IDs.
- [ ] Every Kafka producer/consumer supports canonical headers.
- [ ] Job executions persist `correlationId`.
- [ ] Scheduler and notification outboxes preserve immutable correlation across retries.
- [ ] Java → Kafka → Python → Kafka → Java retains one correlation ID.
- [ ] Context never leaks across requests, records, tasks, or executor threads.
- [ ] Fluent Bit owns collection and delivery; application code remains backend-agnostic.
- [ ] VictoriaLogs is the selected log backend and Loki is not required.
- [ ] The same application images run with no backend, Compose VictoriaLogs, or Kubernetes VictoriaLogs.
- [ ] Logging or collector failure never fails business processing.
- [ ] Logs contain no secrets or uncontrolled payload data.

## Non-Goals

- No direct Logback/Python/Pino appender to VictoriaLogs, S3, or MinIO.
- No Loki dependency in the selected deployment.
- No storage of logs in PostgreSQL.
- No mandatory OpenTelemetry backend in the first increments.
- No trace IDs inside every business payload.
- No browser-to-MinIO log upload.
- No one-size-fits-all runtime logging package shared across languages.
