# Phase 7 — Multi-Channel Notification Routing

## Goal

Evolve the existing single Telegram target into template-based, recipient-aware routing without coupling job aggregation to delivery providers.

## Increment P7-I1 — Typed operation/signal routes and notification templates

| Field                   | Value                                                    |
| ----------------------- | -------------------------------------------------------- |
| id                      | P7-I1                                                    |
| title                   | Typed operation/signal routes and notification templates |
| status                  | pending                                                  |
| priority                | medium                                                   |
| depends_on              | [P1-I4, P2-I3]                                           |
| blocks                  | [P7-I2]                                                  |
| owned_modules           | [apps/core, configs]                                     |
| execution_mode          | autonomous                                               |
| requires_owner_decision | false                                                    |
| pr                      | null                                                     |
| last_verified_commit    | null                                                     |

Goal: separate domain events, notification policy, templates, and delivery routing.

Acceptance criteria: operational events and signal digests resolve to explicit routes, templates validate required variables, domain publishers do not contain Telegram chat IDs, and missing route behavior is deliberate and tested.

Required tests/checks: routing config validation, template variable tests, event-to-route tests, and Core Nx checks.

Stop conditions: stop if channel taxonomy or default-route fallback semantics are owner decisions.

## Increment P7-I2 — Delivery adapters, retries, idempotency, and provider migration

| Field                   | Value                                                           |
| ----------------------- | --------------------------------------------------------------- |
| id                      | P7-I2                                                           |
| title                   | Delivery adapters, retries, idempotency, and provider migration |
| status                  | pending                                                         |
| priority                | medium                                                          |
| depends_on              | [P7-I1]                                                         |
| blocks                  | []                                                              |
| owned_modules           | [apps/core, configs, docs/flows]                                |
| execution_mode          | autonomous                                                      |
| requires_owner_decision | false                                                           |
| pr                      | null                                                            |
| last_verified_commit    | null                                                            |

Goal: add provider adapters and reliable delivery semantics without changing job execution status.

Acceptance criteria: duplicate terminal events do not duplicate deliveries, provider failures are retryable and observable, exhausted deliveries record failure/dead-letter state, provider message IDs are tracked, and Telegram remains compatible during migration.

Required tests/checks: idempotency tests, retryability tests, provider adapter tests with secrets mocked, and affected Nx checks.

Stop conditions: stop if provider credentials or live provider access are required.
