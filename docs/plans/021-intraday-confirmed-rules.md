# Intraday Confirmation for Confirmed Trend — Implementation Plan

Status: implementation_ready / NOT IMPLEMENTED
Dependency: Phase 9 P9-I1 is `pending_owner_verification`; implementation must not start before owner verification is accepted.
Current source baseline: `9c66e3a4b7579854a76137019dc05e3c4b0f6159`

## Goal

Integrate completed-session intraday evidence into the deterministic rule-based confirmed signal without moving derived computation into Ingestor/Core and without rewriting historical `CONFIRMED_TREND_EQUALS_V1` meaning.

The existing daily components remain authoritative for direction:

- `TREND_MOMENTUM_V1`
- `ICHIMOKU_V1`

Intraday is a required confirmation/gating input for the new model version, not a third equal-weight vote.

## Model/version decision

Keep the strategy key `CONFIRMED_TREND_EQUALS` for query/notification compatibility, but activate a new model version only for newly evaluated rows:

```text
CONFIRMED_TREND_EQUALS_V2_INTRADAY
```

Do not rewrite V1 history automatically. Persist `modelVersion` on every combined row so V1 and V2 remain auditable.

## Ownership

| Layer | Responsibility |
| --- | --- |
| Ingestor | Fetch, normalize, reconcile, and publish raw completed-session `intraday-trades` only. No signal rules. |
| Analyzer | Resolve READY inputs, derive reusable intraday confirmation facts, evaluate deterministic rules, compose final confirmed signal, persist lineage. |
| Core/Platform | Schedule jobs and deliver notifications only. No feature/rule computation. |
| Query Service / Console | Read/display persisted result and explanation only. |

## Canonical rule input

One logical rule input per `(symbolKey, tradingDate, timeframe=1d)`:

```text
symbol_key
trading_date
timeframe

trend_momentum_signal
trend_momentum_score
trend_momentum_data_version
ichimoku_signal
ichimoku_score
ichimoku_data_version

intraday_provider
intraday_data_version
intraday_reconciliation_status
intraday_first_timestamp
intraday_last_timestamp
intraday_trade_count

session_return_pct
vwap
close_vs_vwap_pct
late_return_pct
late_volume_share
intraday_rule_output
intraday_reason_codes

model_version
input_data_versions
```

`input_data_versions` must contain the exact EOD/component/intraday versions used to produce the row. Derived values must never be written by Ingestor.

## Source-to-target mapping

| Target | Source | Rule |
| --- | --- | --- |
| Daily direction | persisted `TREND_MOMENTUM_V1` + `ICHIMOKU_V1` results | Existing equal-vote mapping remains unchanged. |
| `session_return_pct` | first and final normalized intraday trade | `(last_price / first_price) - 1` |
| `vwap` | normalized intraday trades | `sum(price * volume) / sum(volume)` |
| `close_vs_vwap_pct` | final trade + VWAP | `(last_price / vwap) - 1` |
| `late_return_pct` | final 30 minutes of available completed-session trades | return from first trade inside `[last_timestamp - 30m, last_timestamp]` to final trade |
| `late_volume_share` | normalized intraday trades | volume in final 30 minutes / full-session volume |
| reconciliation quality | P9-I1 immutable manifest | `READY`, `WARNING`; `REJECTED` is never consumable |

Using a relative final-30-minute window avoids introducing a market-session calendar dependency into this rule increment.

## Intraday deterministic rule

Use sign/direction in V1 rather than unvalidated percentage thresholds:

```text
F1 session_direction:
  BULLISH if session_return_pct > 0
  BEARISH if session_return_pct < 0
  NEUTRAL otherwise

F2 vwap_direction:
  BULLISH if close_vs_vwap_pct > 0
  BEARISH if close_vs_vwap_pct < 0
  NEUTRAL otherwise

F3 late_direction:
  BULLISH if late_return_pct > 0
  BEARISH if late_return_pct < 0
  NEUTRAL otherwise
```

`late_volume_share` and `trade_count` remain explanation/quality facts in this first rule version; do not invent a directional threshold until backtest evidence exists.

Rule output:

```text
BULLISH_CONFIRM  = at least 2 of F1/F2/F3 are BULLISH and none is BEARISH
BEARISH_CONFIRM  = at least 2 of F1/F2/F3 are BEARISH and none is BULLISH
MIXED            = facts conflict
NEUTRAL          = no directional majority
UNAVAILABLE      = required intraday input cannot be resolved safely
```

Reason codes expose individual facts, e.g. `INTRADAY_SESSION_BULLISH`, `INTRADAY_CLOSE_ABOVE_VWAP`, `INTRADAY_LATE_BULLISH`, `INTRADAY_MIXED`, and `INTRADAY_RECONCILIATION_WARNING`.

## Composition with `CONFIRMED_TREND_EQUALS`

Step 1: run the existing equal-vote daily combiner unchanged to obtain `daily_signal`.

Step 2: apply the intraday confirmation gate:

| Daily equal-vote result | Intraday rule output | V2 result |
| --- | --- | --- |
| BULLISH | BULLISH_CONFIRM | BULLISH |
| BEARISH | BEARISH_CONFIRM | BEARISH |
| NEUTRAL | any valid intraday output | NEUTRAL |
| BULLISH | BEARISH_CONFIRM or MIXED | NEUTRAL |
| BEARISH | BULLISH_CONFIRM or MIXED | NEUTRAL |
| BULLISH/BEARISH | NEUTRAL | NEUTRAL |
| any | UNAVAILABLE | NO_DECISION |
| any component invalid/stale/NO_DECISION | any | NO_DECISION |

Intraday may confirm or suppress a daily direction; it must not create a new BULLISH/BEARISH signal when the existing daily equal-vote result is NEUTRAL.

## Freshness and readiness

Required for V2:

1. component signal dates are equal;
2. component signal date equals the latest canonical EOD date used by the confirmed job;
3. intraday `trading_date` equals that exact date;
4. intraday immutable publication is READY and reconciliation is `READY` or `WARNING`;
5. the exact intraday object for the requested symbol/date/provider is resolvable;
6. all input `dataVersion` values are recorded before persistence.

No previous-day intraday fallback. Missing/stale intraday yields `UNAVAILABLE` -> final `NO_DECISION`.

`WARNING` reconciliation remains consumable but adds `INTRADAY_RECONCILIATION_WARNING`. A `REJECTED` candidate is not a valid input.

## Persistence and lineage

```text
intraday-trades READY
        |
        v
Analyzer derives IntradayConfirmationFacts in memory
        |
        +---- TREND_MOMENTUM_V1 history
        +---- ICHIMOKU_V1 history
        +---- canonical EOD READY
        |
        v
CONFIRMED_TREND_EQUALS_V2_INTRADAY
        |
        v
existing signal history persistence
```

Do not create a persisted `intraday-features` dataset solely for this first rule slice. Promote these facts later only if multiple strategies reuse them.

Persist in combined signal metadata:

- existing two daily component details;
- `intradayConfirmation` with rule output, facts, reason codes, reconciliation status, provider, trading date, and intraday dataVersion;
- exact `inputDataVersions` map;
- `modelVersion=CONFIRMED_TREND_EQUALS_V2_INTRADAY`.

## Scheduler/dependency sequence

```text
SYNC_STOCK_PRICE
  -> indicators
  -> TREND_MOMENTUM_V1
  -> ICHIMOKU_V1
SYNC_INTRADAY_EOD
  -> intraday READY
[both branches ready]
  -> CONFIRMED_TREND_EQUALS V2
```

The V2 confirmed job must wait for exact-date intraday READY. Existing component jobs remain independently queryable.

## Migration

1. Owner verifies P9-I1 manually.
2. Add Analyzer intraday input resolver and pure fact derivation.
3. Add pure intraday confirmation rule.
4. Add explicit V2 confirmed composition while preserving V1 behavior for historical interpretation/tests.
5. Extend signal metadata/storage for `intradayConfirmation` and exact input versions.
6. Add scheduler dependency/order after exact-date intraday READY.
7. Extend Query Service/Console/Telegram rendering for optional intraday explanation without recomputation.
8. Activate V2 for future rows only; historical V1 backfill is a separate owner action.

## Avoid/deprecate

- Do not copy raw intraday trades into signal metadata.
- Do not duplicate VWAP/session-return calculations in Core, Query Service, or Console.
- Do not create a strategy-specific persisted `confirmed-signal-input` dataset in this first slice.
- Do not reinterpret `components` as three equal votes; keep two daily components and a distinct `intradayConfirmation` object.
- Do not make P9-I2 bars/generic intraday features a hidden prerequisite.

## Implementation checklist

Owner instruction: do not run verification automatically. All checks are `NOT RUN`.

| Status | Check | Source evidence / target |
| --- | --- | --- |
| NOT RUN | P9-I1 owner verification accepted before implementation | `plans/roadmap/phase-9-intraday-eod.md`; baseline `9c66e3a` |
| NOT RUN | Existing equal-vote daily behavior unchanged before gate | `apps/analyzer/app/signals/strategy.py::calculate_confirmed_trend_equals` |
| NOT RUN | Intraday resolver requires exact symbol/date/provider and READY | `docs/data/002-data-lake.md`; new Analyzer resolver tests |
| NOT RUN | Reconciliation READY/WARNING accepted; REJECTED unavailable | `apps/ingestor/app/handlers/intraday_eod.py`; `libs/py-common/py_common/intraday_reconciliation.py` |
| NOT RUN | Session return, VWAP, close-vs-VWAP and final-30m facts deterministic | new pure Analyzer fact tests |
| NOT RUN | Intraday rule matrix returns CONFIRM/MIXED/NEUTRAL/UNAVAILABLE exactly | new pure rule tests |
| NOT RUN | Intraday cannot create direction from daily NEUTRAL | new V2 composition tests |
| NOT RUN | Opposing/mixed intraday suppresses directional daily result | new V2 composition tests |
| NOT RUN | Missing/stale exact-date intraday yields NO_DECISION | new handler/strategy tests |
| NOT RUN | WARNING adds explanation without silently blocking | new rule/metadata tests |
| NOT RUN | V2 persists modelVersion, intraday dataVersion and exact input versions | `apps/analyzer/app/signals/storage.py`; new persistence tests |
| NOT RUN | V1 history remains readable and unmodified | existing signal history + migration regression tests |
| NOT RUN | Scheduler orders V2 after daily components and intraday READY | Platform job seed/dependency tests |
| NOT RUN | Query/Console/Telegram display explanation without computation | existing DTO/rendering tests extended for `intradayConfirmation` |
| NOT RUN | Relevant Analyzer/Platform/Query/Console Nx targets | inspect project targets and run manually after implementation |

## Evidence inspected

- `plans/roadmap/phase-9-intraday-eod.md`: P9-I1 is `pending_owner_verification`; all verification checks remain `NOT RUN`.
- `apps/analyzer/app/signals/strategy.py`: current confirmed strategy is a two-component equal vote with stale/mismatch/NO_DECISION guards.
- `apps/analyzer/app/signals/handler.py`: current confirmed handler reads persisted component histories plus latest canonical EOD date and persists through the existing signal repository.
- `docs/data/002-data-lake.md`: Analyzer owns derived features/signals; Ingestor owns normalized intraday trades.
- `docs/reference/001-algorithm-feature-catalog.md`: VWAP, session returns and volume facts are already in the planned reusable vocabulary.
- `apps/ingestor/app/handlers/intraday_eod.py`: P9-I1 normalizes timestamp/price/volume/trade value and publishes reconciliation metadata.
- `libs/py-common/py_common/intraday_reconciliation.py`: deterministic `READY/WARNING/REJECTED` reconciliation status.

## Non-goals

- no realtime/tick confirmation;
- no ML/LLM decision;
- no arbitrary weighted ensemble;
- no sector intraday rule;
- no trading execution/advice;
- no automatic V1 historical rewrite;
- no percentage-threshold optimization before backtest evidence.
