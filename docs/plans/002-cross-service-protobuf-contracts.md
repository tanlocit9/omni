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

Implemented structure:

```text
libs/contracts/
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
└── gen/                               # ignored build output
    ├── java/
    └── python/
```

Canonical source:

```text
libs/contracts/proto/**/*.proto
```

Generated code is local, disposable, reproducible build output. It is ignored by Git and must never be committed or edited manually.

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
contracts:format
contracts:lint
contracts:breaking
contracts:generate-check
contracts:test
```

Generation must be deterministic. `contracts:generate-check` compares two independent clean local generations, and consumer build/package targets must depend on `contracts:generate` before compiling generated types.

## Tooling

Use Buf as the protobuf workflow layer:

- `buf lint` for schema conventions;
- `buf breaking` against the default-branch/baseline schema;
- `buf generate` for Java/Python code generation;
- `buf format` for deterministic formatting.

Pin Buf and the local `grpc-tools` compiler so developer, CI and Docker builds produce the same result. Buf invokes its `protoc_builtin` Java/Python generators through that repository-local compiler; no Buf remote generation plugin is used.

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
docs/data/001-kafka-contracts.md
docs/architecture/001-system-overview.md   # when contract ownership/boundaries change
docs/flows/001-job-execution.md            # command/status serialization flow
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

## Active Blockers, Residual Risks, and Mitigation Plan

These items do not reopen the completed P2-I1 implementation scope. They define the delivery gate or the increment that owns each mitigation.

| Item                                                                | Classification        | Impact                                                                                                           | Required mitigation and owner                                                                                                                                                                                                                                                                          |
| ------------------------------------------------------------------- | --------------------- | ---------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| PR #9 is still draft and unmerged                                   | Delivery blocker      | The canonical contracts and updated roadmap are not yet available on `main`.                                     | Owner reviews and merges PR #9 after green CI. Do not start P2-I2 from a branch that does not contain the merged contract baseline. Because P3-I1 and this PR both touch `libs/py-common`, start P3-I1 after merge unless module isolation is explicitly verified.                                     |
| Production boundaries still use JSON                                | Compatibility risk    | Schema drift remains possible until producers and consumers use generated adapters.                              | P2-I2 adds explicit Java/Python adapters and golden cross-language fixtures. P2-I3 uses consumer-first dual-read, decode metrics, rollback thresholds, and an owner-approved producer switch.                                                                                                          |
| Scheduler outbox delivery is at-least-once                          | Correctness risk      | A broker acknowledgement followed by a process crash can replay the same logical event.                          | P2-I2 preserves stable message identity through adapters and adds duplicate-delivery/idempotent consumer-boundary tests. P2-I3 observes duplicate counts during the compatibility window before cutover.                                                                                               |
| READY manifest and exact lineage are not yet canonical              | Data integrity risk   | A valid message can still reference stale, incomplete, or schema-incompatible data.                              | P3-I1 defines immutable `dataVersion`, deterministic schema/data hashes, exact upstream versions, and READY-pointer semantics. P3-I2 publishes READY last and proves failed writes cannot replace the current READY dataset. A `DatasetOutput` is not consumable until its manifest resolves as READY. |
| Sector transition has multiple potential writers for shared outputs | Data consistency risk | Concurrent executions can overwrite or publish conflicting sector datasets.                                      | P1-I3 establishes one canonical sector universe and one logical shared-output writer before P3-I3 or P9-I3 migrates sector publication.                                                                                                                                                                |
| Telegram deduplication is process-local                             | Operational risk      | Restarts clear the cache and multiple Platform replicas can still deliver duplicates.                            | Treat the current cache as rate limiting only. P8-I2 owns durable delivery identity, retry state, provider message tracking, and cross-instance idempotency.                                                                                                                                           |
| AI/analysis consumers can use data without provenance               | AI/data risk          | Downstream explanations or recommendations can be generated from stale or incompatible inputs without detection. | Do not promote AI-facing analytical features until P3 manifests expose readiness, freshness, schema identity, and exact data versions. AI result metadata must retain the input manifest/data versions used for generation.                                                                            |

### Ordered remediation

1. Review and merge P2-I1 with green CI.
2. Complete P3-I1 manifest identity/readiness foundations.
3. Complete P1-I4 event ownership, then P2-I2 adapters, fixtures, and idempotency coverage.
4. Complete P1-I3 before sector manifest publication.
5. Use P2-I3 only after owner approval of the pilot boundary, observation window, rollback thresholds, and legacy JSON removal timing.
6. Keep distributed notification deduplication in P8-I2 so it does not delay the contract/data critical path.

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
