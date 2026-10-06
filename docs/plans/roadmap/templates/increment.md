# Increment Template

## Metadata

| Field                   | Value      |
| ----------------------- | ---------- |
| id                      | P?-I?      |
| title                   |            |
| status                  | pending    |
| priority                | medium     |
| depends_on              | []         |
| blocks                  | []         |
| owned_modules           | []         |
| execution_mode          | autonomous |
| requires_owner_decision | false      |
| pr                      | null       |
| last_verified_commit    | null       |

## Goal

## Current verified baseline

## Dependencies and eligibility conditions

## In scope

## Out of scope

## Expected implementation approach

## Files or modules likely to be touched

## Cross-service blast radius

Record the code-review-graph changed/impacted files, then reconcile Platform, Analyzer, Ingestor, Query Service, Console, shared contracts/libraries, persistence, configuration, tests, and operations against canonical documentation. For every applicable surface, state the impact or a concrete no-impact reason; bounded graph hops do not prove cross-language Kafka or storage boundaries are unaffected.

## Acceptance criteria

## Impact-to-test coverage matrix

Use one row per changed or directly impacted production file/safety-critical symbol and acceptance criterion:

| Feature or criterion | Changed/impacted source | Executed tests | Coverage evidence | Remaining gap |
| -------------------- | ----------------------- | -------------- | ----------------- | ------------- |

## Required unit tests

## Required integration or contract tests

## Coverage thresholds

Declare numeric thresholds for critical components. Default minimum: 80% line and 80% branch coverage per critical component. Aggregate project coverage is not sufficient. Explicitly include success, failure, retry, compatibility, concurrency, transaction, and data-loss-prevention branches when applicable.

## Required Nx/test/coverage/build/CI commands

## Data migration or backward-compatibility considerations

## Security, concurrency, data-quality, and operational risks

## Stop conditions requiring owner input

## Verification evidence

Record blast-radius analysis, canonical cross-service reconciliation, executed test/coverage results, unresolved instrumentation limitations, and applicable runtime evidence. Branch, commit, PR, merge, and CI identifiers are traceability metadata and do not replace coverage.

## Completion and rollback notes
