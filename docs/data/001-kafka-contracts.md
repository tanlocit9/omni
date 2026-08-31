# Kafka Contracts

Kafka is the asynchronous contract boundary between Platform, Ingestor, and Analyzer.

## Proto3 contract foundation

Canonical versioned schemas live under [`libs/contracts/proto`](../../libs/contracts/proto). Java and Python outputs under `libs/contracts/gen` are ignored, disposable build artifacts generated through the `contracts` Nx project. Contract tests generate them automatically, deterministic checks compare independent clean generations, and future consumer build/package targets must depend on `contracts:generate` before compiling these types.

The initial `omni.contracts.common.v1` and `omni.contracts.job.v1` schemas define `DatasetRef`, `DatasetOutput`, `ExecutionStatus`, `JobCommand`, and `JobStatusEvent`. Dataset references are logical and do not expose bucket names or physical object paths. Persisted dataset manifests remain JSON.

This foundation does not change the active wire format. Existing producer/consumer pairs continue using their documented JSON payloads until adapter and dual-read increments add compatibility tests and a rollout-safe migration.

Canonical topic names live in [`configs/shared/topics.yaml`](../../configs/shared/topics.yaml). This document explains ownership and purpose; the YAML file remains the source of truth for literal topic values.

## Contract Rule

Do not update a producer or consumer in isolation. When changing a Kafka topic, payload field, field meaning, serialization rule, validation rule, or error-handling contract, update all of these in the same change:

1. Producer code.
2. Consumer code.
3. Shared payload/config abstractions.
4. Producer and consumer tests.
5. This document.
6. Flow docs that reference the contract.

## Topic Map

```mermaid
flowchart LR
  Platform["Platform / Core"]
  Ingestor["Ingestor"]
  Analyzer["Analyzer"]
  Kafka["Kafka"]

  Platform -->|topic-sync-stock-prices| Kafka
  Platform -->|topic-sync-symbols| Kafka
  Kafka -->|topic-sync-stock-prices| Ingestor
  Kafka -->|topic-sync-symbols| Ingestor
  Ingestor -->|topic-sync-job-status| Kafka
  Ingestor -->|topic-upsert-symbols| Kafka
  Ingestor -->|topic-upsert-sectors| Kafka
  Kafka -->|topic-sync-job-status| Platform
  Kafka -->|topic-upsert-symbols| Platform
  Kafka -->|topic-upsert-sectors| Platform

  Platform -->|topic-sync-indicators| Kafka
  Platform -->|topic-sync-signals| Kafka
  Platform -->|topic-evaluate-signals| Kafka
  Platform -->|topic-precompute-symbol-features| Kafka
  Platform -->|topic-precompute-sector-features| Kafka
  Platform -->|topic-sector-rotation-backtest| Kafka
  Platform -->|topic-sector-transition-analyze| Kafka
  Platform -->|topic-sector-transition-evaluate-outcomes| Kafka
  Kafka -->|analytical jobs| Analyzer
  Analyzer -->|topic-sync-job-status| Kafka
  Analyzer -->|topic-signal-notifications| Kafka
  Kafka -->|topic-signal-notifications| Platform
```

## Topics

### topic-sync-stock-prices

| Field           | Value                                                 |
| --------------- | ----------------------------------------------------- |
| Topic key       | `topic-sync-stock-prices`                             |
| Producer        | Platform scheduler job producer                       |
| Consumer        | Ingestor stock-price handler                          |
| Purpose         | Request EOD stock-price synchronization for a symbol. |
| Related flow    | [Stock sync](../flows/002-stock-sync.md)              |
| Related storage | [`eod`](002-data-lake.md#eod)                         |

Expected payload shape includes required generic `workType=SYMBOL` and `workKey`,
source, the domain command field `symbolKey`, optional time bounds, and metadata.
`symbolKey` tells the stock-price worker which symbol to process; it is not a status
fallback or a second execution identity. The payload must not include S3 bucket or
object path routing fields.

### topic-sync-symbols

| Field           | Value                                                       |
| --------------- | ----------------------------------------------------------- |
| Topic key       | `topic-sync-symbols`                                        |
| Producer        | Platform scheduler job producer                             |
| Consumer        | Ingestor symbols handler                                    |
| Purpose         | Request symbol metadata synchronization by exchange/source. |
| Related flow    | [Stock sync](../flows/002-stock-sync.md)                    |
| Related storage | [`symbols`](002-data-lake.md#symbols)                       |

### topic-upsert-symbols

| Field            | Value                                                                      |
| ---------------- | -------------------------------------------------------------------------- |
| Topic key        | `topic-upsert-symbols`                                                     |
| Producer         | Ingestor                                                                   |
| Consumer         | Platform scheduler symbol upsert consumer                                  |
| Purpose          | Send symbol snapshot/upsert results back to Platform-owned database state. |
| Related database | [Symbols](003-database.md#symbols)                                         |

### topic-upsert-sectors

| Field            | Value                                                                      |
| ---------------- | -------------------------------------------------------------------------- |
| Topic key        | `topic-upsert-sectors`                                                     |
| Producer         | Ingestor                                                                   |
| Consumer         | Platform scheduler sector upsert consumer                                  |
| Purpose          | Send sector snapshot/upsert results back to Platform-owned database state. |
| Related database | [Sectors](003-database.md#sectors)                                         |

### topic-sync-job-status

| Field            | Value                                                          |
| ---------------- | -------------------------------------------------------------- |
| Topic key        | `topic-sync-job-status`                                        |
| Producer         | Ingestor and Analyzer workers                                  |
| Consumer         | Platform scheduler job status consumer                         |
| Purpose          | Report child job completion/failure metrics to Platform.       |
| Related flow     | [Job execution](../flows/001-job-execution.md)                 |
| Related database | [Job execution history](003-database.md#job-execution-history) |

Status payloads must carry enough identity to update the correct child execution
and aggregate parent state. After P1-I4 the canonical fields are
`jobDefinitionId`, `executionId`, optional `parentExecutionId`, required
`workType`, required `workKey`, status, metrics, duration, `metaJson`, and optional
error details.

P1-I4 is an explicit breaking cutover: no backward-compatible `symbolKey` status
field, alias, fallback, or dual-write remains. Drain old outbox/topic messages,
snapshot PostgreSQL, manually clear execution history, then deploy Java and Python
producers/consumers together. Domain command payloads and signal notification content may retain
`symbolKey` when it has genuine symbol meaning, but status correlation uses only
`executionId` plus canonical `workType`/`workKey`.

Every scheduler job payload also carries the same required `workType` and
`workKey`. Workers copy those fields unchanged into terminal status messages;
they do not derive execution identity from `symbolKey`, `exchange`, `sectorCode`,
strategy, or arbitrary metadata. Platform rejects a child status whose work
identity does not match the persisted child execution.

Worker error statuses must preserve useful request context in `metaJson`. Do not replace failure metadata with only `recordsProcessed = 0`; include safe orthogonal fields such as timeframe, strategy, evaluation date, sector universe/focus, prediction horizons, and the actual error message when available. Generic status builders must not copy `symbolKey`, `exchange`, sector codes, or arbitrary key fields into execution identity extras.

The implementation remains `verification_pending` until the final pushed PR #16
head has successful exact-head CI and all repository gates are green. The
maintenance-window drain/manual-cleanup/deploy sequence is documented in the
[P1-I4 hard-cutover runbook](../deployment/001-p1-i4-hard-cutover.md); implementation
verification did not deploy or modify production.

### topic-sync-indicators

| Field           | Value                                                                      |
| --------------- | -------------------------------------------------------------------------- |
| Topic key       | `topic-sync-indicators`                                                    |
| Producer        | Platform scheduler indicator job producer                                  |
| Consumer        | Analyzer indicator worker                                                  |
| Purpose         | Compute technical indicators from EOD Parquet and write indicator Parquet. |
| Related flow    | [Indicator and signal](../flows/003-indicator-signal.md)                   |
| Related storage | [`indicators`](002-data-lake.md#indicators)                                |

### topic-sync-signals

| Field           | Value                                                                 |
| --------------- | --------------------------------------------------------------------- |
| Topic key       | `topic-sync-signals`                                                  |
| Producer        | Platform scheduler signal job producer                                |
| Consumer        | Analyzer signal worker                                                |
| Purpose         | Compute signal history/current-state records from EOD and indicators. |
| Related flow    | [Indicator and signal](../flows/003-indicator-signal.md)              |
| Related storage | [`signals`](002-data-lake.md#signals)                                 |

### topic-evaluate-signals

| Field        | Value                                                                   |
| ------------ | ----------------------------------------------------------------------- |
| Topic key    | `topic-evaluate-signals`                                                |
| Producer     | Platform scheduler signal-evaluation job producer                       |
| Consumer     | Analyzer signal evaluation worker                                       |
| Purpose      | Evaluate signal outcomes after forward-return windows become available. |
| Related flow | [Indicator and signal](../flows/003-indicator-signal.md)                |

### topic-signal-notifications

| Field        | Value                                                            |
| ------------ | ---------------------------------------------------------------- |
| Topic key    | `topic-signal-notifications`                                     |
| Producer     | Analyzer                                                         |
| Consumer     | Platform notification module                                     |
| Purpose      | Publish signal transition notifications for downstream delivery. |
| Related flow | [Indicator and signal](../flows/003-indicator-signal.md)         |

### topic-precompute-symbol-features

| Field           | Value                                                                    |
| --------------- | ------------------------------------------------------------------------ |
| Topic key       | `topic-precompute-symbol-features`                                       |
| Producer        | Platform scheduler sector-wave producer                                  |
| Consumer        | Analyzer sector-wave worker                                              |
| Purpose         | Precompute symbol-level features used by sector aggregation and ranking. |
| Related flow    | [Sector wave](../flows/004-sector-wave.md)                               |
| Related storage | [`symbol-features`](002-data-lake.md#symbol-features)                    |

### topic-precompute-sector-features

| Field           | Value                                                 |
| --------------- | ----------------------------------------------------- |
| Topic key       | `topic-precompute-sector-features`                    |
| Producer        | Platform scheduler sector-wave producer               |
| Consumer        | Analyzer sector-wave worker                           |
| Purpose         | Aggregate symbol features into sector-level datasets. |
| Related flow    | [Sector wave](../flows/004-sector-wave.md)            |
| Related storage | [`sector-features`](002-data-lake.md#sector-features) |

### topic-sector-rotation-backtest

| Field           | Value                                                                     |
| --------------- | ------------------------------------------------------------------------- |
| Topic key       | `topic-sector-rotation-backtest`                                          |
| Producer        | Platform scheduler sector-rotation backtest producer                      |
| Consumer        | Analyzer sector-wave worker                                               |
| Purpose         | Run sector rotation backtests from precomputed sector features.           |
| Related flow    | [Sector wave](../flows/004-sector-wave.md)                                |
| Related storage | [`sector-rotation-backtests`](002-data-lake.md#sector-rotation-backtests) |

### topic-sector-transition-analyze

| Field           | Value                                                                                                                                                                                                                                                   |
| --------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Topic key       | `topic-sector-transition-analyze`                                                                                                                                                                                                                       |
| Producer        | Platform scheduler Sector Transition analysis producer                                                                                                                                                                                                  |
| Consumer        | Analyzer Sector Transition analysis worker                                                                                                                                                                                                              |
| Purpose         | Generate T-anchored Sector Transition predictions, probabilities, and private decisions.                                                                                                                                                                |
| Related flow    | [Sector wave deferred research](../flows/004-sector-wave.md#deferred-research-sector-transition-and-recommendation)                                                                                                                                     |
| Related storage | [`sector-transition-predictions`](002-data-lake.md#sector-transition-predictions), [`sector-transition-decisions`](002-data-lake.md#sector-transition-decisions), [`sector-transition-probabilities`](002-data-lake.md#sector-transition-probabilities) |

Expected payload fields are job identity, `source`, `evaluationDate`, resolved-universe `sectorCodes`, resolved `focusSectorCodes`, `sectorLevel`, `timeframe`, `strategy`, `predictionHorizons`, and `metadata`. `sectorCodes = []` means all eligible sectors at `sectorLevel` only in Platform Sector Transition job config; Platform resolves the concrete universe before publishing. `focusSectorCodes = []` resolves to the full universe, while a non-empty focus must be a subset of the resolved universe. The payload must not carry bucket names or object paths. Decisions produced by this job are `PRIVATE_INTERNAL` research outputs.

Sector Transition failure statuses must preserve enough metadata for actionable Platform notifications: `evaluationDate`, `sectorCodes`, `focusSectorCodes`, `sectorLevel`, `timeframe`, `strategy`, `predictionHorizons`, `recordsProcessed = 0`, and the actual analyzer error message. Platform renders failed analysis notifications through a job-specific notification policy rather than through scheduler producer logic.

### topic-sector-transition-evaluate-outcomes

| Field           | Value                                                                                                               |
| --------------- | ------------------------------------------------------------------------------------------------------------------- |
| Topic key       | `topic-sector-transition-evaluate-outcomes`                                                                         |
| Producer        | Platform scheduler Sector Transition outcome-evaluation producer                                                    |
| Consumer        | Analyzer Sector Transition outcome-evaluation worker                                                                |
| Purpose         | Evaluate realized outcomes for prior Sector Transition predictions without rewriting them.                          |
| Related flow    | [Sector wave deferred research](../flows/004-sector-wave.md#deferred-research-sector-transition-and-recommendation) |
| Related storage | [`sector-transition-outcomes`](002-data-lake.md#sector-transition-outcomes)                                         |

Expected payload fields match `topic-sector-transition-analyze`, including resolved-universe `sectorCodes` and resolved `focusSectorCodes`. Outcome evaluation reads stored focused predictions and appends realized outcome rows to the outcomes dataset without recalculating a focus-only model or rewriting historical prediction probabilities. Failure statuses follow the same metadata-preservation rule as analysis jobs.

## Shared Configuration

| Config                          | Path                                                                                                                                                   |
| ------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Topic names                     | [`configs/shared/topics.yaml`](../../configs/shared/topics.yaml)                                                                                       |
| Java producer/consumer messages | [`apps/core/src/main/java/com/omni/platform/modules/scheduler/messaging`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/messaging) |
| Java producers                  | [`apps/core/src/main/java/com/omni/platform/modules/scheduler/producers`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/producers) |
| Java consumers                  | [`apps/core/src/main/java/com/omni/platform/modules/scheduler/consumers`](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/consumers) |
| Python shared messaging         | [`libs/py-common/py_common/messaging`](../../libs/py-common/py_common/messaging)                                                                       |
| Python Kafka helpers            | [`libs/py-common/py_common/kafka`](../../libs/py-common/py_common/kafka)                                                                               |

## Payload Boundary Rules

- Use stable job identity fields so Platform can update execution state.
- Keep dataset routing out of Kafka payloads; use shared S3 path builders instead.
- Keep field names and semantics compatible across Java and Python.
- Add validation at the consumer boundary.
- Include error details in status events without leaking credentials or provider secrets.
