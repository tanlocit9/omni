# Post-MVP Roadmap Work

## Decision

On 2026-09-05, the owner narrowed the active MVP to the existing daily/EOD pipeline, usable Telegram operational and signal notifications, and basic operator controls.

Work that primarily adds migration machinery, advanced metadata, deployment hardening, Console/query polish, delivery hardening, intraday processing, or realtime processing is deferred. It must not be selected by roadmap automation until the owner explicitly promotes it back into the canonical increment registry.

This is prioritization debt, not a claim that the work has no long-term value. Existing implementations and verification evidence remain valid historical evidence even when their roadmap increment is superseded for MVP scheduling.

## Deferred Increments

| Area                            | Deferred increments        | Reason for deferral                                                                                                                                                                                                                         |
| ------------------------------- | -------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Proto3 migration                | P2-I2, P2-I3               | Generated adapters, cross-language migration, dual-read operation, and cutover do not add immediate MVP user value while the current daily/EOD boundary remains usable.                                                                     |
| Advanced manifests and metadata | P3-I1, P3-I2, P3-I3, P3-I5 | Generalized manifest infrastructure, migration, and reconciliation are post-MVP platform hardening. P3-I4 remains completed because its date-contract correction already protects the active EOD pipeline.                                  |
| Portable deployment hardening   | P5-I1, P5-I2, P5-I3        | Image hardening, cloud/storage profiles, backup rehearsal, and immutable publication are deferred until an MVP deployment target is selected.                                                                                               |
| Console and query polish        | P6-I1, P6-I2, P6-I3, P6-I4 | Dataset exploration, SQL tooling, Arrow workflows, and dashboard work are outside the basic operator-control MVP. Existing merged source is retained but is not an active completion priority.                                              |
| Telegram delivery hardening     | P8-I3                      | Distributed idempotency, retries, dead-letter outcomes, advanced observability, and controlled live rollout are deferred. Existing cooldown, exception isolation, destination routing, and mocked payload coverage remain the MVP baseline. |
| Intraday EOD                    | P9-I1, P9-I2, P9-I3        | Higher-frequency post-close datasets and features are outside the daily/EOD MVP.                                                                                                                                                            |
| Realtime per tick               | P10-I1, P10-I2             | Tick ingestion and live processing are outside the daily/EOD MVP.                                                                                                                                                                           |

## Retained MVP Scope

The active MVP keeps:

- the existing daily/EOD ingestion and analysis pipeline;
- correctness work already protecting scheduler, execution identity, date contracts, and canonical sector processing;
- Phase 7 basic job catalog, safe trigger, and execution visibility;
- P8-I1 operational/generic Telegram formats;
- P8-I2 immediate/digest signal formats.

Completed increments remain completed. P1-I3, P8-I1, and P8-I2 may finish evidence reconciliation because their implementations directly support the retained MVP. Deferred increments must not block those MVP evidence gates solely because of historical dependency links.

## Existing Safety Baseline

Deferring hardening does not authorize removing existing safeguards. Preserve current validation, transaction boundaries, exception isolation, cooldown deduplication, destination isolation, secret handling, bounded payloads, and tests. A defect affecting correctness, data loss, credentials, or unsafe operation remains MVP work rather than technical debt.

## Reactivation Triggers

Reassess deferred work when one of these becomes true:

- the MVP requires a second independently deployed producer or consumer boundary;
- current JSON contracts cause compatibility or ownership failures;
- multiple replicas make process-local notification admission materially incorrect;
- a concrete deployment target requires image, storage, backup, or recovery guarantees;
- operators need dataset exploration, arbitrary read-only SQL, or dashboard workflows beyond basic job controls;
- intraday or realtime data becomes an approved product requirement;
- observed production failures show that a deferred control is required for safe operation.

Reactivation requires an owner decision, refreshed dependencies and acceptance criteria, and a new or restored canonical roadmap increment. Do not treat this document as authorization to implement deferred work autonomously.

## Configurable Signal Combination Platform

The MVP deliberately implements only the fixed, equal-vote `CONFIRMED_TREND_EQUALS` strategy described in [`docs/plans/016-confirmed-trend-equals-mvp.md`](../plans/016-confirmed-trend-equals-mvp.md). The following generalized combination platform is post-MVP technical debt.

### Intended Capability

Operators can create named combinations from arbitrary registered signal strategies, assign optional weights, enable or disable the whole combination or individual components, precompute results, select combinations in Dashboard, and choose which combinations produce Telegram notifications.

A stable display name points to one active immutable version. Editing calculation behavior creates a new version rather than rewriting historical meaning.

### Generalized Configuration

```json
{
  "name": "CONFIRMED_TREND",
  "enabled": true,
  "timeframe": "1d",
  "threshold": 0.6,
  "components": [
    {
      "strategy": "TREND_MOMENTUM_V1",
      "enabled": true,
      "weight": 0.7
    },
    {
      "strategy": "ICHIMOKU_V1",
      "enabled": true,
      "weight": 0.3
    }
  ]
}
```

Rules:

- normalize strategy names and reject duplicate component strategies;
- require at least two enabled, distinct components;
- split weights equally across enabled components when all enabled weights are omitted;
- reject mixed omitted/provided weights unless a future contract defines unambiguous semantics;
- require finite, non-negative explicit weights with a positive total;
- exclude disabled components and normalize enabled weights to total `1.0`;
- map `BULLISH = +1`, `NEUTRAL = 0`, and `BEARISH = -1`;
- calculate the weighted sum and apply the immutable version's threshold;
- produce `NO_DECISION` when a required enabled component is missing, stale, date-mismatched, or `NO_DECISION`;
- retain component signals, source scores, normalized weights, contributions, reasons, dates, and source data versions.

Changing combination enablement, component enablement, component membership, weights, threshold, timeframe, or ensemble algorithm creates a new immutable version.

### Identity and Lifecycle

The stable name is operator-facing. The `combinationId` identifies exact canonical calculation semantics and should be derived from stable name, canonical sorted components, enabled flags, normalized decimal weights, threshold, timeframe, and ensemble algorithm version.

Suggested lifecycle:

```text
DRAFT -> PRECOMPUTING -> READY -> ACTIVE -> INACTIVE
                         \\-> FAILED
```

Activation rules:

1. Platform validates and stores a new immutable version.
2. Analyzer precomputes all available supported history for that version.
3. The previous active version remains active during precompute.
4. Analyzer reports processed, skipped, unavailable, and failed counts plus published data identity.
5. Platform atomically activates only a successfully published READY version.
6. Failed precompute leaves the previous version active.
7. Old READY/INACTIVE versions remain queryable and may be reactivated for rollback.

### Platform Persistence and APIs

Suggested relational ownership:

- `signal_combinations`: stable name, enabled state, active version ID, audit fields;
- `signal_combination_versions`: immutable combination ID, version, canonical configuration, configuration hash, lifecycle state, precompute execution ID, actor/timestamps;
- existing manual-trigger/outbox patterns should carry idempotent precompute requests rather than adding browser-to-Kafka access.

Suggested operator-only APIs:

```text
GET  /api/v1/signal-combinations
GET  /api/v1/signal-combinations/{name}
POST /api/v1/signal-combinations
PUT  /api/v1/signal-combinations/{name}
POST /api/v1/signal-combinations/{name}/versions/{version}/precompute
POST /api/v1/signal-combinations/{name}/versions/{version}/activate
```

Writes require authenticated operator identity, idempotency, optimistic concurrency, validation, and audit. Creating or changing a combination starts precompute automatically; the explicit precompute endpoint retries failed work.

### Analyzer and Dataset Ownership

Analyzer owns ensemble calculation and persisted result datasets. Platform owns configuration and activation state. Query Service and browsers must never calculate combinations.

Persist each immutable version separately, for example:

```text
signals/combination=confirmed_trend/combination_id=<immutable-id>/timeframe=1d/exchange=HOSE/
```

Rows retain combination identity and complete component evidence. Existing outcome evaluation can attach realized T+5/T+10/T+15/T+20 outcomes without rewriting the original decision.

### Dashboard and Telegram

Dashboard lists existing enabled named combinations and their available versions. Normal use reads the active READY version with exchange, exact symbol, and limit filters; historical version selection is explicit. Combined rows expose expandable component evidence.

Telegram configuration references stable combination names or an explicit approved list. Delivery resolves each name to its active READY `combinationId`. Disabled combinations send nothing. Deduplication identity includes combination ID, symbol, signal date, and result so version changes cannot collapse distinct decisions.

### Deferred Verification

Before reactivation, add coverage for canonical identity, weight normalization, enablement, validation, immutable version creation, precompute failure, atomic activation, rollback, concurrent updates, stale components, READY publication, outcome evaluation, Dashboard selection, Telegram selection, and audit history.
