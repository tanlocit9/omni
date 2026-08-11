# Omni Implementation Plan Standard

## Purpose

Every implementation plan must describe not only what to build, but also what usable outcome the phase produces and what analytical features become available for later algorithms.

## Mandatory Sections

Every implementation plan must include:

### Outcome

Describe the concrete capability available after implementation.

Examples:

- a stable scheduler contract;
- a queryable Parquet dataset;
- an internal observability tool;
- an operational notification route.

### Dataset Outputs

List new or modified persistent datasets and their logical paths.

If the work is operational only, explicitly write:

```text
No analytical dataset output.
```

### Algorithm Feature Outputs

List fields/features that can become direct inputs to future rule-based strategies, backtests, statistical models, or ML models.

For each feature, identify whether it is:

- `DIRECT` — persisted explicitly;
- `DERIVED` — reproducibly computed from the output dataset;
- `CONDITIONAL` — only available when the upstream provider supplies required data.

If the implementation does not create analytical features, explicitly write:

```text
No direct algorithm feature output.
```

### Algorithms Unlocked

State which later analytical capabilities the output enables, for example:

- trend/momentum scoring;
- sector rotation;
- intraday timing;
- anomaly detection;
- signal confidence scoring;
- supervised prediction labels/features.

## Data Plan Rule

Data implementation plans must prefer reusable canonical features over strategy-specific scores.

Preferred:

```text
return_5m
vwap_distance
relative_volume
realized_volatility
breadth_positive_return
```

Avoid making the storage layer own a strategy conclusion such as:

```text
BUY_SCORE_V3
```

Strategy-specific decisions should be computed downstream from stable reusable features.

## Feature Naming

Use stable snake_case field names. Include timeframe/window in the name when the meaning depends on it.

Examples:

```text
return_1m
return_5m
realized_volatility_30m
volume_ratio_20d
breadth_positive_return_5m
```

The same semantic feature must not receive different names in EOD, intraday, backtest, and realtime pipelines.

## Provider-Dependent Features

Never assume market-data fields that are not guaranteed by the provider.

Features such as the following must be marked `CONDITIONAL` unless the source contract guarantees them:

- aggressor side;
- bid/ask depth;
- order identifiers;
- trade condition;
- sequence number.

## Feature Registry Direction

As Omni grows, the sections in implementation plans should feed a central feature registry containing:

```text
feature_name
source_dataset
entity_type
frequency
dtype
description
availability
version
```

This registry can later be exposed in Internal Tools and used by analyzer jobs to validate required inputs.
