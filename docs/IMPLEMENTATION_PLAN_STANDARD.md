# Omni Implementation Plan Standard

## Purpose

Every implementation plan must describe what to build, what concrete outcome is produced, what datasets/metadata are persisted, and which analytical features become available for later algorithms.

## Mandatory Sections

Every implementation plan must include:

### Outcome

Describe the concrete capability available after implementation.

### Dataset Outputs

List new or modified persistent datasets and logical paths.

If operational only:

```text
No analytical dataset output.
```

### Metadata Outputs

For every data-producing plan, define the corresponding MinIO metadata manifest path and readiness semantics.

Preferred storage:

```text
stock-data/_metadata/datasets/...
```

Manifest should normally expose:

```text
status
path
objectCount
totalBytes
rowCount
columnCount
schemaHash
min/max date or timestamp
sourceExecutionId
generatedAt
```

Write order:

```text
write data -> validate -> write READY manifest last
```

Do not introduce PostgreSQL/Redis only to cache these dataset statistics in V1.

If the plan produces no analytical dataset:

```text
No dataset metadata output.
```

See `DATASET_METADATA_MANIFEST_IMPLEMENTATION_PLAN.md`.

### Algorithm Feature Outputs

List fields/features that can become direct inputs to future rule-based strategies, backtests, statistical models, or ML models.

Classify each as:

- `DIRECT` — persisted explicitly;
- `DERIVED` — reproducibly computed from an output dataset;
- `CONDITIONAL` — requires optional provider data.

If none:

```text
No direct algorithm feature output.
```

### Algorithms Unlocked

State which later analytical capabilities the output enables.

## Data Plan Rules

1. Prefer reusable canonical features over strategy-specific scores.
2. Keep raw/reusable data separate from final strategy decisions.
3. Make time/evaluation semantics explicit.
4. Use MinIO manifests as the default dataset readiness/freshness contract.
5. Do not scan a full object prefix merely to decide whether a known dataset partition is READY when a manifest exists.
6. Failed writes must not publish a new READY manifest.

Preferred features:

```text
return_5m
vwap_distance_pct
relative_volume
realized_volatility
breadth_positive_return_5m
```

Avoid storage-owned strategy conclusions such as:

```text
BUY_SCORE_V3
```

## Feature Naming

Use stable `snake_case` names and include timeframe/window when meaning depends on it.

Examples:

```text
return_1m
return_5m
realized_volatility_30m
volume_ratio_20d
breadth_positive_return_5m
```

The same semantic feature must use the same name in EOD, intraday, backtest and realtime pipelines.

## Provider-Dependent Features

Never assume fields not guaranteed by the provider.

Mark dependent features `CONDITIONAL`, especially for:

- aggressor side;
- bid/ask depth;
- order identifiers;
- trade condition;
- sequence number.

## Shared Placement Rule

Reusable contracts/patterns should live in shared locations when appropriate:

- Java shared/common module for reusable Java abstractions;
- `py_common` for reusable Python data/storage contracts such as `DatasetManifest` and path builders.

Do not duplicate manifest construction across individual handlers.

## Feature Registry Direction

As Omni grows, implementation-plan feature sections should feed a central registry:

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

The registry can later support Internal Tools, analyzer input validation and backtest dataset selection.
