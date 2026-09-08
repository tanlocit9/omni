# Confirmed Trend Equals MVP Implementation Plan

Status: Scheduled supporting detail for P8-I4
Canonical status owner: [`plans/roadmap/implementation-increments.md`](../../plans/roadmap/implementation-increments.md)

## Goal

Add one simple, explainable combined daily signal named `CONFIRMED_TREND_EQUALS`, make signal history queryable by symbol and strategy, and make the Telegram notification strategy configurable without introducing an operator-managed ensemble control plane.

## MVP Decisions

- Components are fixed to `TREND_MOMENTUM_V1` and `ICHIMOKU_V1`.
- Both components have equal weight; no runtime weight field exists in MVP.
- Map `BULLISH = +1`, `NEUTRAL = 0`, and `BEARISH = -1`.
- The combined score is the arithmetic mean of both mapped values.
- A score greater than or equal to `0.5` produces `BULLISH`.
- A score less than or equal to `-0.5` produces `BEARISH`.
- Other valid scores produce `NEUTRAL`.
- Missing, stale, date-mismatched, or `NO_DECISION` input produces `NO_DECISION`.
- The combined result stores both component results for explanation and audit.
- Existing T+5/T+10/T+15/T+20 outcome evaluation applies to combined-signal history.
- Dashboard and Telegram select from the three known strategies; arbitrary combinations are post-MVP.

## Decision Matrix

| Trend Momentum | Ichimoku               | Average | Combined result |
| -------------- | ---------------------- | ------: | --------------- |
| BULLISH        | BULLISH                |   `1.0` | BULLISH         |
| BULLISH        | NEUTRAL                |   `0.5` | BULLISH         |
| BULLISH        | BEARISH                |   `0.0` | NEUTRAL         |
| NEUTRAL        | BULLISH                |   `0.5` | BULLISH         |
| NEUTRAL        | NEUTRAL                |   `0.0` | NEUTRAL         |
| NEUTRAL        | BEARISH                |  `-0.5` | BEARISH         |
| BEARISH        | BULLISH                |   `0.0` | NEUTRAL         |
| BEARISH        | NEUTRAL                |  `-0.5` | BEARISH         |
| BEARISH        | BEARISH                |  `-1.0` | BEARISH         |
| Any            | Missing or NO_DECISION |     n/a | NO_DECISION     |

## Architecture Boundary

Analyzer owns calculation and persistence. Platform schedules work and selects which strategy produces Telegram notifications. Query Service reads persisted READY signal partitions. Omni Console presents existing strategies and never computes a combined signal in the browser.

The MVP must not add Kafka/Proto3 migration, database-backed combination configuration, arbitrary weights, component enable flags, immutable activation workflows, rollback APIs, or dynamic calculation in Query Service.

## Analyzer Work

- Add `CONFIRMED_TREND_EQUALS` to the supported signal strategy vocabulary.
- Implement a pure combiner that accepts typed component results and returns a normal signal result plus component details.
- Require both components to refer to the same symbol, timeframe, and signal date.
- Read the two persisted component histories only after both component calculations complete.
- Persist combined history under its own strategy partition.
- Store an explicit model identifier such as `CONFIRMED_TREND_EQUALS_V1` and component details sufficient to reconstruct the decision.
- Reuse the existing single-writer history boundary and outcome evaluator.
- Add a scheduled/precompute step after Trend Momentum and Ichimoku daily jobs.

Suggested component metadata per combined row:

```json
{
  "modelVersion": "CONFIRMED_TREND_EQUALS_V1",
  "components": [
    {
      "strategy": "TREND_MOMENTUM_V1",
      "signal": "BULLISH",
      "mappedValue": 1,
      "score": 4,
      "signalDate": "2026-09-05",
      "reasonCodes": ["PRICE_ABOVE_MA50"]
    },
    {
      "strategy": "ICHIMOKU_V1",
      "signal": "NEUTRAL",
      "mappedValue": 0,
      "score": 1,
      "signalDate": "2026-09-05",
      "reasonCodes": ["PRICE_INSIDE_CLOUD"]
    }
  ]
}
```

## Dashboard and Query Service

Generalize the existing `GET /v1/dashboard/signal-history` endpoint:

- add a validated `strategy` parameter;
- allow `TREND_MOMENTUM_V1`, `ICHIMOKU_V1`, and `CONFIRMED_TREND_EQUALS`;
- default to `CONFIRMED_TREND_EQUALS`;
- retain `exchange`, `symbol`, and `limit`;
- resolve only the selected strategy's READY partition;
- return selected strategy and component details when present;
- return a truthful unavailable response when the selected partition is not READY.

Update the existing Console signal-history widget with a three-option strategy selector. Preserve the current exact symbol filter and exchange/limit controls. Combined rows expose expandable component details without changing single-strategy rows.

## Telegram Selection

Add a bounded Platform setting for the default signal-notification strategy:

```text
TELEGRAM_SIGNAL_STRATEGY=CONFIRMED_TREND_EQUALS
```

Allowed values are the same three known strategies. Default to `CONFIRMED_TREND_EQUALS`. Only the selected strategy publishes user-facing immediate and digest signal notifications; component jobs still calculate and persist their histories. Failed jobs remain operational notifications.

Combined Telegram messages show:

- `CONFIRMED_TREND_EQUALS` as the strategy;
- the combined signal and average score;
- each component strategy and signal;
- existing bilingual signal/reason presentation;
- no inferred target price or investment instruction.

Changing the setting affects future notifications only and does not rewrite history.

## Likely Files

- `apps/analyzer/app/signals/strategy.py`
- `apps/analyzer/app/signals/handler.py`
- `apps/analyzer/app/signals/storage.py`
- Analyzer signal message/job wiring and tests
- Platform signal job configuration, producer, notification policy, and configuration binding
- `apps/query-service/app/dashboard.py`
- `apps/query-service/app/api.py`
- `apps/query-service/app/models.py`
- `apps/omni-console/src/datasets/signals/`
- signal flow/data documentation

## Acceptance Criteria

- The decision matrix is implemented exactly and deterministically.
- Invalid or unavailable component input yields `NO_DECISION` and does not fabricate a directional signal.
- Combined rows retain both exact component decisions and model version.
- Repeating the same inputs produces the same combined result and persistence identity.
- Existing single-strategy histories remain unchanged and queryable.
- Dashboard can select any of the three strategies and filter by exact symbol.
- Telegram sends only the configured strategy and defaults to `CONFIRMED_TREND_EQUALS`.
- Combined signal outcomes are evaluable through existing forward-return windows.
- No arbitrary combination/version management is introduced.

## Required Tests

- Full nine-row decision matrix plus missing/NO_DECISION cases.
- Component date, symbol, and timeframe mismatch rejection.
- Deterministic component metadata and persistence tests.
- Job ordering and combined-history publication tests.
- Existing outcome-evaluation regression with combined history.
- Query Service strategy validation, READY selection, symbol filter, and component response tests.
- Console strategy-selector and symbol-filter tests.
- Platform configuration binding and notification-selection tests.
- Telegram immediate/digest rendering tests for combined component details.
- Relevant Analyzer, Platform, Query Service, and Console Nx targets.

## Stop Conditions

Stop if implementation requires arbitrary runtime combinations, mutable historical meaning, Query Service calculation, browser-to-Kafka access, cross-service contract expansion outside the current JSON boundary, inferred target prices, or automatic trading advice.

## Rollback

Disable the combined job and set `TELEGRAM_SIGNAL_STRATEGY` to `TREND_MOMENTUM_V1` or `ICHIMOKU_V1`. Existing component histories remain authoritative and combined history may remain read-only for investigation.

## Post-MVP Extension

The full operator-managed weighted ensemble, immutable versioning, automatic historical precompute, atomic activation, rollback, and per-component enablement design is deliberately deferred and documented in [`docs/technical-debt/004-post-mvp-roadmap-work.md`](../technical-debt/004-post-mvp-roadmap-work.md).
