# Phase 11 — Cross-Service Observability

## Goal

Add durable business correlation, schema-compatible structured logging, transport propagation, and optional centralized log collection after the scheduler and notification outboxes are stable.

## Phase eligibility and execution order

Phase 11 starts only after both P4-I3 and P8-I5 are `completed`. Its increments execute strictly in this order:

```text
P4-I3 + P8-I5
  -> P11-I1 contract and structured logging
  -> P11-I2 HTTP and Console propagation
  -> P11-I3 job and outbox persistence
  -> P11-I4 Kafka propagation
  -> P11-I5 Fluent Bit and VictoriaLogs deployment
```

All five increments are `pending`. They are autonomous and require no remaining owner decision, but normal roadmap ownership-conflict rules still apply before promotion to `ready`.

The canonical supporting design is [`../../docs/plans/024-polyglot-correlation-structured-logging.md`](../../docs/plans/024-polyglot-correlation-structured-logging.md). The superseded Plan 015 is historical only.

## Shared contract decisions

- `correlationId` identifies the complete business flow.
- `requestId` identifies an ingress or trigger chain and is propagated as `X-Request-ID`/`x-request-id`.
- The existing manual-trigger `requestId` is the default request ID for that flow. Generate a new request ID only when no valid value exists.
- `executionId` remains one durable job execution identity.
- These identifiers are not aliases and are not replaced by a future OpenTelemetry trace ID.
- Kafka transport uses optional validated headers; business protobuf payloads remain unchanged.
- Structured logs are fail-open and must not expose secrets or uncontrolled payloads.

## Increment P11-I1 — Correlation contract and structured logging

| Field                   | Value                                                                                               |
| ----------------------- | --------------------------------------------------------------------------------------------------- |
| id                      | P11-I1                                                                                              |
| title                   | Correlation contract and structured logging                                                         |
| status                  | pending                                                                                             |
| priority                | high                                                                                                |
| depends_on              | [P4-I3, P8-I5]                                                                                      |
| blocks                  | [P11-I2]                                                                                            |
| owned_modules           | [libs/contracts, apps/core, libs/py-common, apps/analyzer, apps/ingestor, apps/query-service, docs] |
| execution_mode          | autonomous                                                                                          |
| requires_owner_decision | false                                                                                               |
| pr                      | null                                                                                                |
| last_verified_commit    | null                                                                                                |

Goal: define canonical machine-readable correlation/log schemas and emit compatible structured JSON from Java and Python with scoped context cleanup.

Current verified baseline: logging is runtime-specific, Core has a misleading unpopulated MDC trace placeholder, and no shared JSON schema or cross-language scoped correlation implementation exists.

In scope: JSON schemas under `libs/contracts/observability`, Java MDC scope/generation/validation, Python `ContextVar` scope and formatter, redaction, schema versioning, cleanup/isolation tests, and valid contracts targets for JSON schema checks.

Out of scope: HTTP/Kafka propagation, database persistence, browser logging, collector deployment, and mandatory tracing.

Acceptance criteria:

- Representative Java and Python events validate against one canonical schema.
- Nested scopes restore prior values and concurrent/sequential work cannot leak context.
- Missing optional values are omitted rather than replaced with misleading placeholders.
- Logging failures remain fail-open and secret/payload redaction is covered.

Required tests/checks: schema contract tests, Java MDC unit tests, Python ContextVar tests, `nx run contracts:test`, `nx run platform:test`, `nx run platform:build`, `nx run py-common:test`, and applicable service lint/test/build targets after command approval.

Migration/backward compatibility: additive runtime behavior only; console output may remain available locally. Rollback selects the prior formatter without changing business contracts.

Stop conditions: stop if one schema cannot represent both runtimes without exposing payloads, or if implementation requires changing business protobuf messages.

## Increment P11-I2 — HTTP and Console correlation propagation

| Field                   | Value                                                                                   |
| ----------------------- | --------------------------------------------------------------------------------------- |
| id                      | P11-I2                                                                                  |
| title                   | HTTP and Console correlation propagation                                                |
| status                  | pending                                                                                 |
| priority                | high                                                                                    |
| depends_on              | [P11-I1]                                                                                |
| blocks                  | [P11-I3]                                                                                |
| owned_modules           | [apps/core, libs/py-common, apps/analyzer, apps/query-service, apps/omni-console, docs] |
| execution_mode          | autonomous                                                                              |
| requires_owner_decision | false                                                                                   |
| pr                      | null                                                                                    |
| last_verified_commit    | null                                                                                    |

Goal: propagate validated `X-Correlation-ID` and `X-Request-ID` through browser and backend HTTP boundaries with scoped log context.

In scope: Platform filter, shared Python ASGI middleware, Console client helper, CORS request/exposed headers, mapped-error reuse, generation/validation, and concurrent isolation tests.

Request-ID rule: the existing manual-trigger `requestId` is reused as the default `X-Request-ID` for that trigger flow. Other ingress/background boundaries generate a request ID only when no valid value exists.

Out of scope: Kafka propagation, durable columns, browser log upload, Pino, and Node runtime infrastructure.

Acceptance criteria: every backend HTTP response returns valid IDs, supplied valid IDs are preserved, invalid/missing IDs are safely replaced, Console sends/receives headers, and error responses/logs use the active IDs.

Required tests/checks: Platform HTTP tests, Python ASGI tests, Console tests/typecheck, CORS tests, concurrency cleanup, and relevant approved Nx targets.

Compatibility/rollback: headers are additive; clients not sending them remain supported. Rollback may disable propagation while retaining schema-compatible logging.

Stop conditions: stop if correlation values are used for authentication/idempotency or if arbitrary browser headers are propagated.

## Increment P11-I3 — Durable job and outbox correlation

| Field                   | Value                                        |
| ----------------------- | -------------------------------------------- |
| id                      | P11-I3                                       |
| title                   | Durable job and outbox correlation           |
| status                  | pending                                      |
| priority                | high                                         |
| depends_on              | [P11-I2]                                     |
| blocks                  | [P11-I4]                                     |
| owned_modules           | [apps/core, database, docs/data, docs/flows] |
| execution_mode          | autonomous                                   |
| requires_owner_decision | false                                        |
| pr                      | null                                         |
| last_verified_commit    | null                                         |

Goal: preserve immutable correlation/request identity across execution creation, process restart, delayed scheduler dispatch, notification delivery, and retries.

In scope: additive execution and scheduler/notification outbox columns, root/child inheritance, manual-trigger request-ID reuse, API visibility where operationally useful, schema-first rollout, indexes, migration/backfill, and retry preservation tests.

Out of scope: Kafka header publication, payload schema changes, and mutable context lookup from dispatcher threads.

Acceptance criteria: root and child executions have correct correlation semantics; outbox retries use persisted snapshots; existing rows migrate safely; scheduler and notification outboxes follow the same identity semantics while retaining independent ownership.

Required tests/checks: migration tests, Platform persistence/API tests, restart/retry tests, parent/child inheritance, old-row compatibility, and approved `platform:test`/`platform:build` evidence.

Migration/rollback: confirm the latest Flyway version at implementation time; deploy additive schema and compatible readers before writers. Do not drop populated columns during rollback.

Stop conditions: stop for destructive migration, ambiguous existing-row backfill, or any proposal to hide required durable identifiers only in untyped metadata JSON.

## Increment P11-I4 — Kafka correlation propagation

| Field                   | Value                                                                                     |
| ----------------------- | ----------------------------------------------------------------------------------------- |
| id                      | P11-I4                                                                                    |
| title                   | Kafka correlation propagation                                                             |
| status                  | pending                                                                                   |
| priority                | high                                                                                      |
| depends_on              | [P11-I3]                                                                                  |
| blocks                  | [P11-I5]                                                                                  |
| owned_modules           | [apps/core, libs/py-common, apps/analyzer, apps/ingestor, configs, docs/data, docs/flows] |
| execution_mode          | autonomous                                                                                |
| requires_owner_decision | false                                                                                     |
| pr                      | null                                                                                      |
| last_verified_commit    | null                                                                                      |

Goal: propagate persisted or active correlation/request IDs through every Java/Python Kafka producer and consumer boundary without changing business payload schemas.

In scope: shared header helpers, all producer/consumer boundaries, scheduler/notification outbox publication, status/upsert/signal/metadata/intraday flows, absent/invalid/duplicate/oversized/UTF-8 handling, cleanup, and end-to-end Java→Python→Java coverage.

Out of scope: mandatory tracing headers, arbitrary header persistence, physical storage paths, and protobuf field changes.

Acceptance criteria: one correlation ID survives the complete representative flow; request/execution IDs retain independent semantics; legacy header-absent records remain processable; malformed diagnostic headers never reject valid business work.

Required tests/checks: producer/consumer unit tests on both languages, end-to-end Kafka integration coverage, outbox retry tests, applicable service targets, contract documentation, impact analysis, and exact-head CI.

Compatibility/rollback: consumers deploy first and accept optional headers; producers enable later. Producer injection can be disabled while consumers remain tolerant.

Stop conditions: stop if implementation requires a business-message payload change or permits unbounded/untrusted headers.

## Increment P11-I5 — Fluent Bit and VictoriaLogs deployment

| Field                   | Value                                                         |
| ----------------------- | ------------------------------------------------------------- |
| id                      | P11-I5                                                        |
| title                   | Fluent Bit and VictoriaLogs deployment                        |
| status                  | pending                                                       |
| priority                | medium                                                        |
| depends_on              | [P11-I4]                                                      |
| blocks                  | []                                                            |
| owned_modules           | [docker-compose, configs, docs/deployment, docs/architecture] |
| execution_mode          | autonomous                                                    |
| requires_owner_decision | false                                                         |
| pr                      | null                                                          |
| last_verified_commit    | null                                                          |

Goal: make structured logs searchable through an optional Fluent Bit and VictoriaLogs deployment while preserving identical application images and fail-open business behavior.

In scope: optional Compose profile, persistent VictoriaLogs storage and retention, Fluent Bit buffering/retry/health, Kubernetes-ready `VLSingle`/DaemonSet configuration, outage tests, and optional separately approved raw archive output.

Out of scope: Loki, mandatory Grafana, application-to-backend appenders, PostgreSQL log storage, mandatory OpenTelemetry, `VLCluster` before capacity evidence, and browser log ingestion.

Acceptance criteria: logs are searchable by service/time/correlation; collector/backend outages do not block services; observability can be disabled; Compose and Kubernetes configuration use the same application images; secrets are excluded.

Required checks: approved Compose/config validation, outage rehearsal, retention/storage review, application health verification with observability disabled, and exact-head CI. Provider/network deployment evidence is manual when applicable and must not be inferred from local configuration.

Compatibility/rollback: disable the optional profile while applications continue JSON output. Preserve VictoriaLogs data according to the documented retention/backup decision.

Stop conditions: stop if deployment requires credentials, production access, mandatory backend availability, or an unapproved raw S3/MinIO archive contract.

## Completion rule

Every increment requires its own branch/draft PR, objective tests, approved Nx checks, exact-head CI where applicable, graph change detection, and synchronized canonical documentation. No increment may claim completion from source presence or plan approval alone.
