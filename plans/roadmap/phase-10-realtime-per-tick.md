# Phase 10 — Realtime Per Tick

Status: Provider-independent P10-I1/P10-I2 source is locally verified and `verification_pending`. On 2026-09-12 the owner moved P10-I0 provider discovery and P10-I3 provider/Kafka/WebSocket/live runtime to technical debt; both are `superseded` and ineligible for automation until explicitly reactivated.

## Goal

Establish a strict, replayable per-tick foundation without claiming provider or runtime capabilities that have not been demonstrated.

## Outcome

Phase 10 provides a strict canonical `MarketTick` plus provider-neutral finite processing: deterministic normalized Parquet bytes, bounded micro-batches, injected immutable publication, archive compaction/rebuild, UTC event-time one-minute bars, and exact-identity comparison with P9-I1 completed-session trades. No service owns or invokes these callables yet. Provider discovery, adapter, Kafka/WebSocket transport, reconnect/resume/correction semantics, and live runtime are deferred technical debt rather than active Phase 10 blockers.

## Dataset Outputs

The foundation reserves the logical immutable archive-part path:

```text
realtime/ticks/source={source}/exchange={exchange}/trading_date={trading_date}/symbol={symbol}/archive_version={archive_version}/part={part}.parquet
```

P10-I2 implements a publication callable accepting the existing storage-backed `ImmutableDatasetPublisher`; it does not configure or run a service writer. Canonical rows use strict schema and stable replay order. Content-addressed batches are immutable, version manifests precede `READY.json`, and a pre-READY failure preserves the previous pointer.

## Metadata Outputs

The provider-neutral publication boundary emits a per-partition immutable version manifest and `READY.json` through the existing publisher when invoked by an injected caller. It does not write `_metadata/metadata.json`; `SYNC_METADATA` remains its sole canonical writer.

## Algorithm Feature Outputs

- `DIRECT`: deterministic finite tick rows and duplicate-safe one-minute UTC event-time OHLCV, trade value, and tick count.
- `DERIVED`: completed-session count, volume, trade-value, open, and close reconciliation differences/status.
- `CONDITIONAL`: provider side, conditions, corrections, depth, and live sequencing remain absent pending evidence.

## Algorithms Unlocked

Deterministic tick replay and deduplication become testable foundations for later live bars, short-horizon features, archive rebuild, and completed-session reconciliation. No live algorithm is unlocked or claimed in the current increment.

## Contract Impact

| Contract area                        | Decision                                                                                                                                                                                                                        |
| ------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Kafka or service-to-service protobuf | Active Kafka payloads are unchanged. No Proto3 schema, topic, producer, consumer, dual-read migration, or provider transport is added. A future Kafka message must carry logical tick identity only, never bucket/object paths. |
| Object-storage JSON manifests        | Unchanged. No manifest writer exists in this increment; immutable-version-before-READY and prior-READY preservation remain mandatory for future publication.                                                                    |
| Storage paths or dataset ownership   | Changed: shared configuration and `StockDataPaths` reserve one canonical logical immutable archive-part path. No runtime producer owns or writes it yet.                                                                        |
| Public Java or Python APIs           | Changed: `py_common.market_ticks` adds the strict `MarketTick`, boundary rejection taxonomy/parser, deterministic identity, and finite replay API. No Java API changes.                                                         |
| Configuration or environment         | Shared path configuration changes only. No credentials, provider configuration, topic, rate-limit setting, or deployment environment change.                                                                                    |

Compatibility policy is strict and Phase-10-local: introduce no alias fields, fallback topic/path, dual-read DTO, permissive parser, historical rewrite, or Proto3 migration. Existing Phase 9 and unrelated signal/indicator/Parquet compatibility remains untouched.

## Increment P10-I0 — Provider capability discovery

| Field                   | Value                                      |
| ----------------------- | ------------------------------------------ |
| id                      | P10-I0                                     |
| title                   | Provider capability discovery and evidence |
| status                  | superseded                                 |
| priority                | critical                                   |
| depends_on              | []                                         |
| blocks                  | []                                         |
| owned_modules           | [docs/plans, docs/data, docs/flows]        |
| execution_mode          | manual                                     |
| requires_owner_decision | true                                       |
| pr                      | null                                       |
| last_verified_commit    | null                                       |

Goal: obtain provider-specific, reproducible evidence before selecting or implementing any connection/runtime behavior.

Required evidence matrix:

- connection protocol, authentication, credential lifecycle, and environments;
- subscription limits, symbol batching, exchange coverage, and market-session scope;
- trade identity guarantees and reuse/reset behavior;
- sequence presence, scope, monotonicity, gaps, and ordering guarantees;
- event timestamp source, precision, timezone, clock behavior, and correction rules;
- reconnect, resubscribe, resume/cursor, snapshot, and gap-recovery behavior;
- corrections, cancellations, duplicates, late events, and out-of-order delivery;
- connection/message/rate limits, backoff requirements, and provider error semantics;
- mandatory versus conditional fields, including trade ID, sequence, side, and conditions.

Acceptance criteria: the evidence identifies a supported exchange/symbol matrix, captures redacted representative frames and observed failure/reconnect scenarios, distinguishes documented guarantees from observations, and resolves every item above sufficiently to design a bounded adapter. No runtime implementation may infer a guarantee from the canonical model.

Verification: manual/provider evidence and owner review are required after explicit reactivation. No Nx target can prove external provider guarantees.

Deferral rule: this increment is technical debt and must not be selected by roadmap automation. Reactivation requires provider access plus an owner decision; unknown identity/order/resume/correction guarantees remain stop conditions after reactivation.

## Increment P10-I1 — Provider-independent tick foundation

| Field                   | Value                                                                   |
| ----------------------- | ----------------------------------------------------------------------- |
| id                      | P10-I1                                                                  |
| title                   | Strict MarketTick contract, identity, replay, and logical archive paths |
| status                  | verification_pending                                                    |
| priority                | critical                                                                |
| depends_on              | []                                                                      |
| blocks                  | [P10-I2]                                                                |
| owned_modules           | [libs/py-common, configs, docs/data, docs/flows]                        |
| execution_mode          | autonomous                                                              |
| requires_owner_decision | false                                                                   |
| pr                      | null                                                                    |
| last_verified_commit    | null                                                                    |

Goal: provide one provider-independent canonical JSON domain contract without implementing or claiming a provider transport.

Scope and approach:

1. Require exact camelCase JSON fields and forbid extras/coercion/legacy aliases.
2. Require schema version 1, lowercase normalized source, uppercase exchange/symbol, UTC event/receive timestamps, positive decimal price/volume, and optional trade ID/sequence.
3. Derive `eventId` from canonical immutable trade facts; exclude arrival time so retries remain identical.
4. Reject malformed input at the boundary with stable categories: invalid JSON/shape, missing field, unknown field, invalid value/timestamp, and identity mismatch.
5. Deduplicate by `eventId` and replay deterministically by event time, available sequence, then event identity. Replay ordering does not claim provider ordering or invent a missing sequence.
6. Reserve one shared logical archive path; do not expose physical storage paths in Kafka.

Acceptance criteria:

- one strict canonical `MarketTick` model and canonical JSON serializer/parser exist in `libs/py-common`;
- aliases, snake_case transport fields, unknown fields, coercion, malformed timestamps, and tampered identities are rejected;
- semantically identical decimal values and changed receive times preserve identity;
- finite replay is deterministic under reordered input and exact duplicates collapse;
- shared logical archive paths are normalized and tested against canonical YAML;
- no provider adapter, WebSocket, topic, producer/consumer, Proto3 schema, generated artifact, runtime capability claim, or historical rewrite is introduced;
- canonical roadmap, supporting plan, Kafka/data/flow docs, and indexes are synchronized;
- targeted Nx checks and CI remain required before completion.

Local verification passed on 2026-09-12:

```text
nx run py-common:lint
nx run py-common:test  # 114 passed, 8 existing deprecation warnings
nx run py-common:build
```

The first lint attempt found attributable line-length and formatting issues; they
were corrected through the project formatter and the full approved sequence then
passed. Post-edit code-review-graph change detection reported risk 0.40 and no
affected execution flows. CI/PR evidence is still required before `completed`.

Stop conditions: stop if the canonical model starts encoding an unverified provider guarantee, if a second transport model/alias is proposed, or if storage publication would bypass immutable-version-before-READY semantics.

## Increment P10-I2 — Provider-independent archive, rebuild, bars, and reconciliation

| Field                   | Value                                                           |
| ----------------------- | --------------------------------------------------------------- |
| id                      | P10-I2                                                          |
| title                   | Provider-independent archive, rebuild, bars, and reconciliation |
| status                  | verification_pending                                            |
| priority                | critical                                                        |
| depends_on              | [P10-I1]                                                        |
| blocks                  | [P10-I3]                                                        |
| owned_modules           | [libs/py-common, configs, docs/data, docs/flows]                |
| execution_mode          | autonomous                                                      |
| requires_owner_decision | false                                                           |
| pr                      | null                                                            |
| last_verified_commit    | null                                                            |

Goal: complete every finite provider-independent archive and completed-session capability without inventing a service runtime.

Implemented source includes canonical tick DataFrame/Parquet representation, bounded content-addressed batches, an injected immutable publisher callable, deterministic finite compaction with exact partition checks and `eventId` collapse, duplicate-safe UTC one-minute OHLCV/value bars, and reconciliation against one exact P9-I1 partition using existing thresholds and explicit `READY`/`WARNING`/`REJECTED` status.

Acceptance criteria: deterministic bytes/order under reordered input; no per-tick write API; duplicate-safe reruns; version-before-READY and prior READY preservation; finite rebuild rejects absent, empty, mixed, or conflicting parts; bars use UTC event time; reconciliation requires exact source/exchange/symbol/date and compares counts, volume, value, open, and close; no provider semantics or runtime is introduced.

Verification: owner-authorized local checks passed on 2026-09-12: `nx run py-common:lint`, `nx run py-common:test` (125 passed, 8 existing deprecation warnings), and `nx run py-common:build`. Attributable import ordering, strict zip, and canonical Parquet dtype failures were repaired before the full passing rerun. CI/PR evidence remains absent, so status is `verification_pending`.

## Increment P10-I3 — Provider adapter, Kafka/WebSocket, and live runtime

| Field                   | Value                                                   |
| ----------------------- | ------------------------------------------------------- |
| id                      | P10-I3                                                  |
| title                   | Provider adapter, Kafka/WebSocket, and live runtime     |
| status                  | superseded                                              |
| priority                | low                                                     |
| depends_on              | [P10-I2]                                                |
| blocks                  | []                                                      |
| owned_modules           | [apps/ingestor, apps/analyzer, libs/py-common, configs] |
| execution_mode          | approval_required                                       |
| requires_owner_decision | true                                                    |
| pr                      | null                                                    |
| last_verified_commit    | null                                                    |

Goal after explicit reactivation: obtain provider evidence, then design and implement provider mapping, approved transport, operational ownership, reconnect/resume/gap/correction behavior, and live processing.

P9-I1 remains the completed-session reference only and stays `verification_pending`; P9-I4 remains `in_progress`; P9-I2/P9-I3 remain `superseded`.

Deferral and stop conditions: P10-I3 is technical debt and must not be selected by roadmap automation. After explicit reactivation, no provider adapter, topic semantics, offsets, reconnect/resume, correction policy, side/condition fields, or live runtime may be inferred without provider evidence and owner-approved objective acceptance criteria.

## Repository Guidance Updates

Reviewed `AGENTS.md`, `CLAUDE.md`, and `.roo/rules`. No guidance update is required: existing rules already require shared Python placement, logical dataset references, shared path builders, no generated-contract edits, graph impact/change analysis, and READY-last publication. Canonical roadmap, supporting plan, data/flow docs, and documentation indexes must be updated in this change.

## Verification

Owner-authorized P10-I1/P10-I2 local verification passed on 2026-09-12:
py-common lint, 125 tests, and build. CI, PR/commit evidence, configured
object-storage integration, and production verification remain unresolved for the
active foundations. Provider capability validation and Kafka/WebSocket runtime
verification move with P10-I0/P10-I3 to technical debt.

## Acceptance Criteria

Phase 10 remains split objectively: P10-I1 and P10-I2 are `verification_pending`; P10-I0 and P10-I3 are `superseded` technical debt. All provider-independent source and local checks are complete. P10-I1/P10-I2 cannot become `completed` until their CI/PR and applicable configured-publication evidence is recorded; deferred provider/runtime evidence is not an active completion prerequisite. Phase 9 status is unchanged.
