# Cross-Service Proto3 Contracts Implementation Plan

## Goal

Introduce a language-neutral, versioned contract layer for communication between Platform, Analyzer, Ingestor and future realtime services.

Use **Protocol Buffers proto3** as the canonical contract for Kafka/service-to-service messages. Keep persisted object-storage dataset manifests as JSON because they are operational artifacts intended to remain human-readable and directly inspectable in S3/R2/Internal Tools.

## Outcome

After this phase Omni has:

- one canonical source for cross-service message schemas;
- generated Java and Python types from the same `.proto` definitions;
- compile/build-time protection against producer/consumer schema drift;
- automated protobuf linting and breaking-change detection;
- typed `JobCommand`, `JobStatusEvent`, `DatasetRef` and `DatasetOutput` contracts;
- a contract foundation for future realtime `MarketTick` events;
- no physical S3/R2 path dependency embedded into business Kafka messages.

## Dataset Outputs

No analytical dataset output.

## Metadata Outputs

No dataset metadata output.

Dataset manifests remain JSON under `_metadata/` and are not replaced by protobuf.

## Algorithm Feature Outputs

No direct algorithm feature output.

## Algorithms Unlocked

No trading algorithm directly. This phase makes downstream signal, intraday and realtime algorithms safer by ensuring all services interpret orchestration and market event payloads consistently.

## Source-of-Truth Layout

Recommended structure:

```text
contracts/
├── project.json
├── buf.yaml
├── buf.gen.yaml
├── proto/
│   └── omni/
│       ├── common/v1/
│       │   ├── dataset.proto
│       │   └── execution.proto
│       ├── job/v1/
│       │   ├── job_command.proto
│       │   └── job_status.proto
│       └── market/v1/
│           └── market_tick.proto      # later/realtime phase
└── gen/
    ├── java/
    └── python/
```

Canonical source:

```text
contracts/proto/**/*.proto
```

Generated code is derived output and must never be edited manually.

## Nx Contract Project

Create an Nx project named `contracts` so repository rules remain consistent.

Expected targets:

```text
nx run contracts:format
nx run contracts:lint
nx run contracts:generate
nx run contracts:breaking
nx run contracts:test
```

Do not invoke Buf/protoc directly when an equivalent Nx target exists.

CI should run at least:

```text
contracts:lint
contracts:breaking
contracts:generate
contract integration tests
```

Generation must be deterministic. CI should fail when generated outputs are stale when generated files are committed, or when service packaging did not regenerate them when generated files are build artifacts.

## Tooling

Use Buf as the protobuf workflow layer:

- `buf lint` for schema conventions;
- `buf breaking` against the default-branch/baseline schema;
- `buf generate` for Java/Python code generation;
- `buf format` for deterministic formatting.

Pin Buf and code-generation plugin versions so developer, CI and Docker builds produce the same result.

No Buf Schema Registry is required in V1.

## Core Contracts

### DatasetRef

A logical dataset reference must not expose physical object-storage paths.

```proto
syntax = "proto3";

package omni.contracts.common.v1;

message DatasetRef {
  string name = 1;
  map<string, string> partition = 2;
}
```

Example logical value:

```text
name = sector-features
partition:
  timeframe = 1d
  sector_level = 2
  sector_code = BANKS
  date = 2026-08-11
```

Path resolution remains owned by shared dataset/path configuration.

### DatasetOutput

```proto
message DatasetOutput {
  DatasetRef dataset = 1;
  string manifest_key = 2;
  optional string data_version = 3;
}
```

`manifest_key` is an output reference/traceability field, not a routing input for downstream business logic.

### JobCommand

```proto
message JobCommand {
  string execution_id = 1;
  optional string parent_execution_id = 2;
  JobType job_type = 3;
  JobContext context = 4;
  WorkItem work = 5;
  oneof payload {
    SyncStockPriceCommand sync_stock_price = 10;
    SyncIndicatorsCommand sync_indicators = 11;
    SyncSignalsCommand sync_signals = 12;
  }
}
```

Prefer typed `oneof` job payloads over a generic arbitrary JSON/`Struct` bag for new contracts.

Migrate existing job families incrementally; do not attempt to model every future job in the first change.

### JobStatusEvent

```proto
message JobStatusEvent {
  string execution_id = 1;
  ExecutionStatus status = 2;
  repeated DatasetOutput outputs = 3;
  optional string error_code = 4;
  optional string error_message = 5;
}
```

Workers publish logical dataset outputs after successfully publishing the corresponding READY manifest.

## Dependency Contract

`DatasetRef` is shared with the dependency system, but the object-storage `DatasetManifest` remains JSON.

The Platform dependency guard resolves:

```text
DatasetRef
   -> manifest key
   -> JSON DatasetManifest
   -> readiness conditions
```

If a job needs dependency conditions in a cross-service payload later, add a typed proto message instead of serializing an ad-hoc map.

See `JOB_DEPENDENCY_GUARD_IMPLEMENTATION_PLAN.md`.

## Proto Evolution Rules

Mandatory rules:

1. Never change or reuse an existing field number.
2. Deleted field numbers and names must be `reserved`.
3. Every enum must define an `*_UNSPECIFIED = 0` value.
4. Prefer additive optional fields over modifying existing semantics.
5. Do not rename/move packages casually because generated API compatibility matters.
6. Package namespaces are versioned, e.g. `omni.contracts.job.v1`.
7. Create `v2` only for a real breaking semantic change, not for every additive field.
8. Do not silently reinterpret the meaning/unit/timezone of an existing field.
9. Timestamp/date semantics must be documented at the field/message level.
10. Run breaking-change checks for every proto change.

## Java Integration

Platform should:

- serialize/deserialize generated protobuf types at the Kafka boundary;
- map generated messages to application/domain commands through explicit adapters;
- avoid spreading protobuf generated types through unrelated domain/service code;
- keep reusable adapters/codecs in an appropriate shared/common package when they are genuinely cross-module abstractions.

Do not maintain handwritten DTO copies of canonical protobuf messages.

## Python Integration

Analyzer/Ingestor should:

- consume generated Python protobuf types;
- keep translation/adaptation helpers in `py_common` when shared by both services;
- convert protobuf boundary messages into domain-friendly internal models where needed;
- never hand-edit generated modules.

## Kafka Migration Strategy

Do not flip all producers and consumers blindly in one deployment.

Preferred migration:

```text
1. add proto schemas + generated types
2. add consumer compatibility path for protobuf
3. deploy consumers first
4. switch producers to protobuf
5. verify payload/error metrics
6. remove temporary JSON compatibility after all services are migrated
```

Use a short-lived content-type/version header or explicit compatibility adapter during migration if needed.

Do not create new topics solely because an additive protobuf schema field was introduced.

## Market Tick Direction

Future `market-ticks.raw` should start with protobuf from day one because it is high-volume and cross-service.

Do not mix order-book snapshots into the trade-tick contract. Introduce a separate contract family if order-book data becomes available.

## Documentation / Contract Map Updates

Implementation must update:

```text
docs/data/kafka-contracts.md
docs/architecture/system-overview.md   # when contract ownership/boundaries change
docs/flows/job-execution.md            # command/status serialization flow
```

## Repository Guidance Updates

This phase changes repository-wide development rules, so implementation must update:

```text
AGENTS.md
CLAUDE.md                               # agent-specific tool/workflow summary when relevant
.roo/rules/                             # Zoo Code workspace rules
```

Required guidance topics:

- proto source of truth;
- generated-code no-edit rule;
- producer/consumer impact workflow;
- Buf/Nx verification targets;
- JSON manifest vs protobuf boundary;
- contract compatibility/evolution rules.

## Verification

- [ ] `nx run contracts:format`
- [ ] `nx run contracts:lint`
- [ ] `nx run contracts:breaking`
- [ ] `nx run contracts:generate`
- [ ] Java producer/consumer serialization tests.
- [ ] Python producer/consumer serialization tests.
- [ ] Cross-language golden payload round-trip test.
- [ ] Kafka integration test for migrated topics.
- [ ] `nx affected` for shared contract changes.
- [ ] `detect_changes` and impact-radius review for producer/consumer changes.

## Acceptance Criteria

- `.proto` files are the canonical cross-service Kafka contract source.
- Java and Python use generated types from the same schema.
- Buf lint and breaking checks run through Nx/CI.
- Generated files are never hand-edited.
- Existing producer/consumer pairs migrate without an unsafe flag-day deployment.
- Dataset manifests remain JSON in object storage.
- Kafka business messages reference logical datasets rather than physical S3/R2 paths.
- `AGENTS.md`, Claude guidance and Zoo Code rules are synchronized with the contract architecture.
