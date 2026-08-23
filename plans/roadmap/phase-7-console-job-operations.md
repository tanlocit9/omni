# Phase 7 — Omni Console Job Operations

## Goal

Add an operator-facing Jobs tab to Omni Console that lists Platform-owned job definitions, safely triggers an existing definition through a Platform HTTP API, and displays execution status without duplicating scheduler, dependency, retry, or synchronization logic in the browser or Query Service.

## Outcome

An authenticated operator can browse triggerable job definitions, inspect scheduling and dependency metadata, submit a deliberate trigger request, receive a stable execution identity, and follow the resulting execution state. Platform remains the source of truth for job definitions and execution semantics.

## Dataset Outputs

No analytical dataset output.

## Metadata Outputs

No dataset metadata output.

## Algorithm Feature Outputs

No direct algorithm feature output.

## Algorithms Unlocked

No analytical algorithm is introduced. The capability improves operational recovery and controlled reruns of existing jobs while preserving the same scheduler and dataset-publication contracts used by scheduled execution.

## Contract Impact

| Contract area                      | Decision                                                                                                                                                                                                                                                               |
| ---------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Kafka/service-to-service protobuf  | No planned schema change. The Platform trigger path must invoke the existing scheduler/dispatch boundary; the browser must not publish Kafka messages directly. If implementation requires a transport change, stop and create a producer/consumer compatibility plan. |
| Object-storage JSON manifest       | Unchanged. Triggered jobs publish manifests through their existing producer paths and retain READY-last, immutable-version, and lineage semantics.                                                                                                                     |
| Storage path/dataset ownership     | Unchanged. Trigger requests use logical job-definition identity and parameters, never bucket names or physical object paths. Existing producers retain dataset ownership.                                                                                              |
| Public Java/Python API             | Platform adds or formalizes read/trigger/status HTTP DTOs and service methods. Run impact analysis before changing shared/public methods and verify all callers and implementations. Query Service remains an analytical read boundary and does not own job control.   |
| Configuration/environment contract | Console receives a private Platform API base URL and authorization configuration through deployment-owned settings. No scheduler rule, provider credential, or cloud credential is exposed to the browser.                                                             |
| Platform HTTP API                  | Add versioned contracts for job-definition catalog/detail, trigger submission, and execution status. Responses use stable logical identifiers, explicit triggerability/block reasons, typed validation errors, and no secret configuration values.                     |

## Repository Guidance Updates

Implementation must review and synchronize [`AGENTS.md`](../../AGENTS.md), [`CLAUDE.md`](../../CLAUDE.md), [`.roo/rules`](../../.roo/rules), [`docs/README.md`](../../docs/README.md), [`docs/flows/job-execution.md`](../../docs/flows/job-execution.md), and the affected Platform/Console service documentation. This planning-only change does not alter current runtime architecture, so repository agent guidance does not require an immediate rule change.

## Increment P7-I1 — Job-definition catalog and triggerability API

| Field                   | Value                                         |
| ----------------------- | --------------------------------------------- |
| id                      | P7-I1                                         |
| title                   | Job-definition catalog and triggerability API |
| status                  | pending                                       |
| priority                | high                                          |
| depends_on              | [P1-I2, P6-I3]                                |
| blocks                  | [P7-I2, P7-I3]                                |
| owned_modules           | [apps/core, apps/omni-console, docs/flows]    |
| execution_mode          | autonomous                                    |
| requires_owner_decision | false                                         |
| pr                      | null                                          |
| last_verified_commit    | null                                          |

Goal: expose a bounded, read-only Platform catalog of existing job definitions and the metadata needed to render safe operator controls.

Acceptance criteria:

- catalog and detail responses expose stable definition identity, display name, active state, schedule summary, work type/key, dependency summary, last execution summary, accepted trigger parameters, and an explicit triggerable/block reason;
- secret values, provider credentials, physical object paths, and mutable internal scheduler fields are never returned;
- filtering and pagination are bounded and deterministic;
- inactive, unknown, dependency-blocked, and non-manual definitions are distinguishable;
- API authorization and audit actor resolution use the approved private operator identity boundary.

Required tests/checks: Platform DTO/service/controller tests, authorization tests, pagination/filter tests, secret-redaction tests, Console API-contract tests, and defined Platform/Console Nx targets.

Stop conditions: stop if no trustworthy operator principal is available, definition visibility policy is undefined, or exposing a field would leak secrets or physical storage details.

## Increment P7-I2 — Safe trigger and execution-status contract

| Field                   | Value                                        |
| ----------------------- | -------------------------------------------- |
| id                      | P7-I2                                        |
| title                   | Safe trigger and execution-status contract   |
| status                  | pending                                      |
| priority                | high                                         |
| depends_on              | [P7-I1, P4-I2]                               |
| blocks                  | [P7-I3]                                      |
| owned_modules           | [apps/core, database/migrations, docs/flows] |
| execution_mode          | approval_required                            |
| requires_owner_decision | true                                         |
| pr                      | null                                         |
| last_verified_commit    | null                                         |

Goal: submit an intentional operator trigger through the existing Platform scheduler boundary and return a stable execution identity with truthful status.

Acceptance criteria:

- trigger requests identify an existing definition and use an explicit idempotency key, actor, reason, and only allow-listed typed parameters;
- Platform validates active/manual-trigger policy, dependencies, concurrency/claim rules, and parameter semantics before dispatch;
- accepted triggers reuse existing execution/outbox behavior and return a stable execution ID; the HTTP handler does not publish directly to Kafka;
- duplicate requests with the same idempotency identity cannot create duplicate executions;
- dependency failures return BLOCKED/deferred semantics rather than false execution failure;
- execution status distinguishes accepted, blocked, running, succeeded, failed, and cancelled states and exposes actionable sanitized errors;
- audit history records actor, definition, request identity, reason, parameters after redaction, timestamps, and outcome;
- cancellation or force/bypass behavior is out of scope unless separately owner-approved.

Required tests/checks: authorization, validation, idempotency, dependency-blocked, scheduler claim/outbox, duplicate submission, audit, sanitized error, and execution-status tests through defined Platform Nx targets.

Stop conditions: stop if implementation bypasses scheduler claims/dependency guards, requires direct browser-to-Kafka access, lacks durable idempotency/audit storage, or requires owner approval for manual-trigger policy and that approval is not recorded.

## Increment P7-I3 — Omni Console Jobs tab and execution visibility

| Field                   | Value                                          |
| ----------------------- | ---------------------------------------------- |
| id                      | P7-I3                                          |
| title                   | Omni Console Jobs tab and execution visibility |
| status                  | pending                                        |
| priority                | medium                                         |
| depends_on              | [P7-I1, P7-I2]                                 |
| blocks                  | []                                             |
| owned_modules           | [apps/omni-console, apps/core, docs]           |
| execution_mode          | autonomous                                     |
| requires_owner_decision | false                                          |
| pr                      | null                                           |
| last_verified_commit    | null                                           |

Goal: add a Jobs tab alongside Datasets that presents definition state, deliberate trigger controls, and execution progress through the Platform API.

Acceptance criteria:

- Console navigation includes a Jobs tab without changing Dataset Explorer or SQL Console ownership;
- operators can search and inspect definitions, understand why a definition is not triggerable, and view recent execution summaries;
- trigger submission requires explicit confirmation, reason entry, typed parameter validation, and disables repeat submission while pending;
- accepted requests display the stable execution ID and refresh status with bounded polling/backoff and a terminal state;
- blocked, rejected, unauthorized, conflict, timeout, and server-error states are distinct and actionable;
- the browser contains no broker/object-store credentials, secret job configuration, physical paths, or duplicated scheduler decisions;
- accessibility, loading, empty, stale, and responsive states are tested.

Required tests/checks: Console route/navigation, catalog/detail, confirmation, parameter validation, duplicate-click prevention, status polling, terminal/error states, accessibility tests, production bundle inspection, and `omni-console` lint/test/build through defined Nx targets plus affected Platform checks.

Stop conditions: stop if the UI must infer triggerability instead of receiving it from Platform, if operator identity cannot be propagated, or if API access would make operational controls anonymous or internet-public.

## Verification

Before implementation, inspect [`platform`](../../apps/core/project.json) and [`omni-console`](../../apps/omni-console/project.json) targets with `nx show project platform` and `nx show project omni-console`. Run only defined targets, including targeted Platform tests and Console lint/test/build. Run code-review-graph impact analysis before changing public HTTP DTOs or scheduler methods, then run change detection after edits. Verify authorization, idempotency, dependency semantics, audit records, browser bundle contents, and synchronized job-execution/service documentation.

## Acceptance Criteria

Phase 7 is complete only when P7-I1 through P7-I3 satisfy their acceptance criteria, required Nx and CI checks pass, increment-specific PR/commit evidence is recorded, Platform remains the sole scheduler/job-definition authority, no trigger path bypasses claims or dependency guards, operator actions are authenticated and auditable, and canonical roadmap, flow, service, and repository guidance documentation is synchronized.
