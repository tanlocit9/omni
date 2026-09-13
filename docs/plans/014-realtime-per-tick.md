# Realtime Per-Tick Market Data Implementation Plan

Status: P10-I1 strict contract and P10-I2 provider-independent archive/rebuild are locally verified and `verification_pending`. On 2026-09-12 the owner moved P10-I0 provider discovery and P10-I3 provider/Kafka/WebSocket/live runtime to technical debt as `superseded` increments. Canonical schedule lives in [`plans/roadmap/phase-10-realtime-per-tick.md`](../../plans/roadmap/phase-10-realtime-per-tick.md).

## Goal

Create a deterministic per-tick foundation without assuming undocumented provider behavior or implementing a live transport before provider evidence exists.

## Outcome

Shared Python now provides the strict canonical `MarketTick` and finite provider-neutral archive processing: normalized deterministic Parquet, bounded content-addressed micro-batches, injected immutable publication, deterministic compaction/rebuild, duplicate-safe UTC one-minute bars, and exact-partition completed-session reconciliation against P9-I1 normalized trades. No WebSocket/API adapter, Kafka producer/consumer, configured service writer, signal runtime, or provider capability is implemented or claimed.

If provider/live work is explicitly reactivated, provider discovery is its first gate. It must establish connection/authentication, subscription limits, exchange coverage, identity, sequence/order, timestamps, reconnect/resume, corrections, duplicates, late events, rate limits, and conditional fields. This deferred scope does not block P10-I1/P10-I2 evidence reconciliation.

## Dataset Outputs

The shared logical archive route is:

```text
realtime/ticks/source={source}/exchange={exchange}/trading_date={trading_date}/symbol={symbol}/archive_version={archive_version}/part={part}.parquet
```

The shared callable prepares immutable micro-batch parts, never per-tick writes. It accepts the existing `ImmutableDatasetPublisher` rather than application settings, validates persisted canonical Parquet, publishes a content-addressed version manifest, and replaces `READY.json` last. Failure before that final replacement preserves the previous pointer. No service invokes or owns this publication runtime yet.

## Metadata Outputs

When explicitly invoked with injected ports, publication emits an immutable per-partition version manifest and `READY.json`. It never writes canonical `_metadata/metadata.json`; `SYNC_METADATA` remains its only writer.

## Algorithm Feature Outputs

- `DIRECT`: canonical finite ticks and one-minute UTC event-time open/high/low/close, volume, trade value, and tick count.
- `DERIVED`: count, volume, value, open, and close reconciliation differences with aggregate `READY`/`WARNING`/`REJECTED` status.
- `CONDITIONAL`: aggressor side, depth, conditions, corrections, and other provider-dependent features remain absent.

## Algorithms Unlocked

The foundation makes deterministic replay/deduplication independently testable. Live bars, short-horizon momentum, intensity, sector features, anomaly detection, and realtime confirmation remain pending provider/runtime work and are not claimed as available.

## Canonical MarketTick JSON

Exact field set:

```json
{
  "schemaVersion": 1,
  "eventId": "mt_<sha256>",
  "source": "provider-a",
  "exchange": "HOSE",
  "symbol": "HPG",
  "marketTimestamp": "2026-09-11T02:15:01.123456Z",
  "receivedAt": "2026-09-11T02:15:01.148456Z",
  "price": "28.5",
  "volume": "100",
  "tradeId": "optional-provider-trade-id",
  "sequence": 10
}
```

Rules:

- JSON is strict: exact camelCase names, no aliases, no unknown fields, no coercion, and schema version 1 only.
- `source` is normalized lowercase; exchange and symbol are normalized uppercase before model construction.
- event and receive timestamps are timezone-aware UTC instants serialized at microsecond precision.
- price and volume are positive exact decimals serialized canonically as strings.
- `tradeId` and `sequence` are optional because provider guarantees are unknown; absence is explicit, not synthesized.
- `eventId` is SHA-256 over canonical source, exchange, symbol, event time, price, volume, trade ID, and sequence. Receive time is excluded so redelivery does not create a new identity.
- a supplied identity that does not match canonical facts is rejected.

Boundary rejection categories are `INVALID_JSON`, `INVALID_SHAPE`, `MISSING_FIELD`, `UNKNOWN_FIELD`, `INVALID_VALUE`, `INVALID_TIMESTAMP`, and `IDENTITY_MISMATCH`. Provider adapters must map provider frames to the canonical constructor before serialization; raw provider payloads never pass as permissive canonical ticks.

Finite replay deduplicates by event identity and sorts by event timestamp, available sequence, then event identity. This is a deterministic rebuild rule, not a claim of provider delivery order and not a substitute for a future correction/resume policy.

## Provider Capability Gate

P10-I0 is deferred technical debt. After explicit reactivation, owner-reviewed evidence must answer:

| Capability                         | Required evidence                                                                  |
| ---------------------------------- | ---------------------------------------------------------------------------------- |
| Connection/auth                    | protocol, endpoints/environments, credential lifecycle, handshake/failure behavior |
| Subscriptions                      | limits, batching, exchange/symbol coverage, session scope                          |
| Identity                           | trade-ID presence, uniqueness scope, reuse/reset behavior                          |
| Sequence/order                     | presence, scope, monotonicity, gaps, ordering guarantees                           |
| Timestamps                         | source clock, precision, timezone, correction behavior                             |
| Reconnect/resume                   | cursor/token, replay window, snapshot/resubscribe, gap detection                   |
| Corrections/duplicates/late events | message representation and provider guarantees                                     |
| Rate limits                        | connection/message/subscription limits and required backoff                        |
| Conditional fields                 | exact mandatory/optional semantics for IDs, sequence, side, conditions, depth      |

Evidence must include redacted representative frames and failures, separate written guarantees from observations, and cover the intended exchange/symbol matrix. Unknowns remain blockers; they are not filled with assumptions in shared code.

## Provider-Independent Archive and Rebuild

Canonical archive rows preserve exact decimal values as canonical strings, UTC microsecond timestamps, optional trade/sequence values, and exact source/exchange/symbol/trading-date identity. Input ordering and exact duplicates cannot alter Parquet bytes. Batches are bounded by tick count and identified from their bytes. Compaction accepts finite non-empty parts only, rejects mixed identities or conflicting duplicate `eventId` rows, collapses exact duplicates, and sorts by event time, sequence availability/value, then identity.

One-minute bars floor `marketTimestamp` in UTC and use deterministic event ordering for open/close. Completed-session reconciliation requires exact source/exchange/symbol/trading date and reuses existing intraday relative thresholds for counts, volume, value, open, and close. This is a finite comparison callable, not a provider correction or live completeness policy.

## Deferred Provider and Runtime Sequence

The following sequence is technical debt and is inactive until explicit owner reactivation:

1. Complete and approve P10-I0 evidence.
2. Verify P10-I1 shared contract/path tests and CI.
3. Refresh P10-I2 acceptance criteria from actual provider guarantees.
4. Implement one bounded provider adapter and explicit boundary mapping.
5. If Kafka is selected, update topic config, producer, consumer, tests, and canonical Kafka docs together; carry logical identity only.
6. Implement immutable micro-batch archive publication with READY last.
7. Add reconnect/resume/gap/correction/duplicate/late-event integration tests.
8. Rebuild and reconcile a completed session against the P9-I1 normalized-trades source contract.

P9-I1 is only the locally verified completed-session batch reference. It remains `verification_pending`; P9-I4 remains `in_progress`; P9-I2 and P9-I3 stay `superseded`. No Phase 9 completion or reactivation is implied.

## No-Legacy Policy

“Remove legacy” applies only to compatibility introduced by Phase 10. Phase 10 introduces no alias fields, fallback topic/path, dual-read DTO, permissive parser, or historical rewrite. It does not remove existing Phase 9 or unrelated signal, indicator, or Parquet compatibility.

The superseded P2-I3 Proto3 dual-read migration is not reactivated. No tick proto is added because current repository evidence does not establish a canonical tick-proto route independent of P2-I3. Strict JSON remains valid. Generated files under `libs/contracts/gen` are never edited.

## Contract Impact

| Contract area                        | Decision                                                                                                                                                                       |
| ------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Kafka or service-to-service protobuf | Unchanged in this increment. No topic or runtime payload exists. If later added, both sides and docs change atomically and messages carry logical identity, not storage paths. |
| Object-storage JSON manifests        | Unchanged in this increment. Future archive publication must preserve immutable-version-before-READY semantics and prior READY on failure.                                     |
| Storage path/dataset ownership       | Changed by reserving one canonical logical archive-part path in shared config/builder. Runtime ownership remains pending.                                                      |
| Public Java/Python API               | New public shared Python domain/parser/replay API; no Java API change.                                                                                                         |
| Configuration/environment contract   | Shared logical path configuration changes. No environment variable, secret, credential, provider, topic, or deployment configuration is added.                                 |

## Repository Guidance Updates

Reviewed `AGENTS.md`, `CLAUDE.md`, and `.roo/rules`. No guidance change is required because existing guidance already mandates shared Python placement, shared logical path builders, no physical paths in Kafka, no generated-contract edits, impact analysis, and READY-last publication. Canonical roadmap, Kafka/data/flow documentation, and docs indexes are synchronized.

## Verification

P10-I1 focused tests cover strict validation/rejection, canonical identity, decimal normalization, receive-time-independent deduplication, replay ordering, duplicate collapse, alias rejection, and shared YAML path composition.

P10-I2 adds focused tests for deterministic bytes/order, bounded batches, reordered parts, duplicate collapse, empty/mixed rejection, repeated publication identity, pre-READY failure safety, UTC one-minute bars, and reconciliation ready/warning/rejected/date mismatch.

Owner-authorized local verification passed on 2026-09-12:

```text
nx run py-common:lint
nx run py-common:test  # 125 passed, 8 existing deprecation warnings
nx run py-common:build
```

The first lint attempt found attributable line-length and formatting issues. They were corrected and the complete sequence passed on rerun. CI, PR/commit, configured archive-publication, and production evidence remain unresolved for P10-I1/P10-I2. Provider capability and Kafka/runtime evidence move with P10-I0/P10-I3 to technical debt. Code-review-graph impact and post-edit change detection are static analysis, not substitutes for active delivery gates.

## Acceptance Criteria

- P10-I1 and P10-I2 are `verification_pending`; P10-I0 and P10-I3 are `superseded` technical debt and ineligible for automation.
- P10-I2 local checks pass but it remains incomplete until CI/PR evidence exists.
- One strict canonical JSON model rejects aliases, extras, coercion, invalid UTC timestamps, and identity tampering.
- Identity and replay behavior are deterministic and duplicate-safe without inventing provider guarantees.
- One canonical logical archive path is configured and built by shared Python code.
- No provider adapter, WebSocket, topic, producer/consumer, proto, generated edit, configured archive runtime, provider capability, or historical rewrite is claimed.
- Phase 9 statuses and unrelated compatibility remain unchanged.
- Roadmap, supporting plan, canonical data/flow docs, and documentation indexes agree.
- Required Nx checks and CI/PR evidence are recorded before P10-I1/P10-I2 completion; deferred provider/runtime evidence is required only after explicit reactivation of P10-I0/P10-I3.
