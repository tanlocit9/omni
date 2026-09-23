# Phase 10 — Realtime Per Tick

Status: Provider-independent P10-I1/P10-I2 source is locally verified and `verification_pending`. On 2026-09-13 the owner reactivated P10-I0/P10-I3 for a VCI-first live collector plan. P10-I0 is `blocked` pending genuine realtime provider evidence, and P10-I3 is `blocked` pending P10-I0, P10-I2 delivery, and approval of its material control/transport contracts.

## Goal

Establish a strict, replayable per-tick foundation without claiming provider or runtime capabilities that have not been demonstrated.

## Outcome

Phase 10 provides a strict canonical `MarketTick` plus provider-neutral finite processing: deterministic normalized Parquet bytes, bounded micro-batches, injected immutable publication, archive compaction/rebuild, UTC event-time one-minute bars, and exact-identity comparison with P9-I1 completed-session trades. No service owns or invokes these callables yet. VCI provider discovery and the live collector runtime are reactivated but blocked: discovery requires genuine realtime evidence, and implementation additionally requires completed P10-I2 plus owner approval of the evidence-derived contracts.

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

| Field                   | Value                                          |
| ----------------------- | ---------------------------------------------- |
| id                      | P10-I0                                         |
| title                   | VCI realtime capability discovery and evidence |
| status                  | blocked                                        |
| priority                | critical                                       |
| depends_on              | []                                             |
| blocks                  | [P10-I3]                                       |
| owned_modules           | [docs/plans, docs/data, docs/flows]            |
| execution_mode          | manual                                         |
| requires_owner_decision | true                                           |
| pr                      | null                                           |
| last_verified_commit    | null                                           |

Goal: obtain VCI-specific, reproducible realtime evidence before selecting or implementing any connection/runtime behavior. The Phase 9 vnstock completed-session/history endpoint is explicitly not accepted as evidence that VCI supports live streaming or suitable realtime polling.

Evidence collected on 2026-09-13 is source inspection only. The repository declares `vnstock>=4.0.2`, and the installed Ingestor environment contained `vnstock` 4.0.2. No provider network call was made, no credentials were accessed, and no live market-session behavior was observed.

| Capability                         | Documented package behavior                                                                                                                                                                             | Repository observation                                                                                 | Provider guarantee / unknown                                                                                                                   |
| ---------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| Connection/auth                    | Installed source constructs HTTPS requests to Vietcap endpoints; inspected intraday code does not document a realtime authentication lifecycle.                                                         | P9 uses `vnstock.api.quote.Quote(..., source="VCI").intraday(...)` through an injected adapter.        | Authorized realtime protocol, environments, credentials, handshake, expiry, and failure behavior are unknown.                                  |
| Transport/subscriptions            | Installed `vnstock` 4.0.2 VCI intraday implementation performs an HTTP POST to `market-watch/LEData/getAll`; no WebSocket, SSE, or streaming subscription contract was established by inspected source. | P9 fetches finite pages for one symbol/session via `last_time`, mapped by the package to `truncTime`.  | Whether VCI offers an authorized realtime feed, polling suitability, batching, limits, session scope, and HOSE/HNX/UPCOM coverage are unknown. |
| Identity                           | The inspected response mapping exposes provider field `id`.                                                                                                                                             | P9 normalization consumes completed-session rows; it does not establish live uniqueness.               | Trade-ID presence, uniqueness scope, reuse, reset, and stability across reconnect/corrections are unknown.                                     |
| Sequence/order                     | The inspected mapping exposes `truncTime` and `id`, but no documented monotonic sequence guarantee was found.                                                                                           | P9 treats `truncTime`/`last_time` as a finite pagination cursor.                                       | Sequence presence/scope, ordering, gap semantics, and whether cursor order is safe for live resume are unknown.                                |
| Timestamps                         | The inspected mapping exposes `truncTime`; package source alone does not establish clock semantics.                                                                                                     | P9 requests a completed trading date and normalizes returned rows.                                     | Event-time source, precision, timezone, clock drift, and timestamp correction behavior are unknown.                                            |
| Reconnect/resume                   | No streaming reconnect/resubscribe contract was found in the inspected intraday implementation.                                                                                                         | P9 repeats bounded HTTP pages using the last non-empty cursor.                                         | Live resume token, replay window, snapshot/resubscribe sequence, disconnect detection, and gap recovery are unknown.                           |
| Corrections/duplicates/late events | No guarantees were found in the inspected package source.                                                                                                                                               | Provider-independent P10 code can deduplicate canonical identities, but that is not provider evidence. | Correction/cancellation representation, duplicate policy, lateness bounds, and out-of-order guarantees are unknown.                            |
| Limits/backoff/errors              | The inspected method accepts a page-size/limit value; this is not a provider limit guarantee.                                                                                                           | P9 has finite adapter behavior only.                                                                   | Connection, request, message, and subscription limits; throttling signals; required backoff; and provider error taxonomy are unknown.          |
| Conditional fields                 | Inspected mapping includes `matchPrice`, `matchVol`, `matchType`, `id`, and `truncTime`.                                                                                                                | P9 maps the completed-session subset needed for normalized trades.                                     | Mandatory/nullable semantics and live availability of ID, sequence, side/type, conditions, depth, and venue fields are unknown.                |

Evidence classification rules:

- **Documented package behavior** means reproducible inspection of the pinned installed package source; it is not an official VCI service guarantee.
- **Repository observation** means behavior visible in checked-in Omni source and tests; it is not a live-provider observation.
- **Provider guarantee / unknown** requires official VCI documentation or an authorized, reproducible market-session observation. Unknowns are never filled from the canonical model or historical endpoint behavior.
- Captured provider material must identify source/version/date and test conditions, redact credentials, account identifiers, cookies, tokens, and unnecessary customer data, and retain only the minimum representative success/failure frames needed to establish semantics.

Acceptance criteria: the evidence identifies a supported exchange/symbol matrix, captures redacted representative frames and observed failure/reconnect scenarios, distinguishes documented guarantees from observations, and resolves every item above sufficiently to design a bounded adapter. No runtime implementation may infer a guarantee from the canonical model.

Verification: manual/provider evidence and owner review are required. No Nx target can prove external provider guarantees. Evidence must identify whether VCI supplies WebSocket, SSE, streaming HTTP, or bounded polling; include redacted successful and failure frames; and record the exact observed/documented semantics for every capability above.

Blocked rule: P10-I0 remains `blocked`; current HTTP source inspection does not prove a genuine realtime capability. P10-I3 remains `blocked` and unimplemented. The next action is to obtain official VCI realtime API documentation or authorized access, then capture redacted successful, authentication-failure, disconnect/reconnect, resubscribe/resume, duplicate/out-of-order, correction/cancellation, and throttling evidence during an intended market session. Completing P10-I0 requires owner review and does not itself authorize P10-I3 implementation.

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
5. Deduplicate by `eventId`, retain the earliest `receivedAt` for same-identity retries, and replay deterministically by event time, available sequence, then event identity. Replay and archive bytes are therefore independent of retry input order; this does not claim provider ordering or invent a missing sequence.
6. Reserve one shared logical archive path; do not expose physical storage paths in Kafka.

Acceptance criteria:

- one strict canonical `MarketTick` model and canonical JSON serializer/parser exist in `libs/py-common`;
- aliases, snake_case transport fields, unknown fields, coercion, malformed timestamps, and tampered identities are rejected;
- semantically identical decimal values and changed receive times preserve identity;
- finite replay is deterministic under reordered input, exact duplicates collapse, and same-identity retries with different arrival times retain the earliest observation regardless of input order;
- shared logical archive paths are normalized and tested against canonical YAML;
- no provider adapter, WebSocket, topic, producer/consumer, Proto3 schema, generated artifact, runtime capability claim, or historical rewrite is introduced;
- canonical roadmap, supporting plan, Kafka/data/flow docs, and indexes are synchronized;
- targeted Nx checks and CI remain required before completion.

Local verification passed again on 2026-09-13 after deterministic retry selection
was added, and fresh verification passed on 2026-09-22:

```text
nx run py-common:sync
nx run py-common:lint
nx run py-common:test  # 127 passed, 8 existing deprecation warnings
nx run py-common:build
```

The focused suite proves that same-identity retries with different `receivedAt`
values retain the earliest observation regardless of input order and therefore
produce identical Parquet bytes. Earlier attributable formatting and
canonical-Parquet issues remain resolved. No source repair was required on
2026-09-22. CI/PR evidence is still required before `completed`.

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

Implemented source includes canonical tick DataFrame/Parquet representation, bounded content-addressed batches, an injected immutable publisher callable, deterministic finite compaction with exact partition checks and `eventId` collapse, deterministic earliest-arrival retention for same-identity retries, duplicate-safe UTC one-minute OHLCV/value bars, and reconciliation against one exact P9-I1 partition using existing thresholds and explicit `READY`/`WARNING`/`REJECTED` status.

Acceptance criteria: deterministic bytes/order under reordered input; no per-tick write API; duplicate-safe reruns; version-before-READY and prior READY preservation; finite rebuild rejects absent, empty, mixed, or conflicting parts; bars use UTC event time; reconciliation requires exact source/exchange/symbol/date and compares counts, volume, value, open, and close; no provider semantics or runtime is introduced.

Verification: owner-authorized local checks passed again on 2026-09-13 and fresh 2026-09-22 verification passed `py-common:sync`, lint, 127 tests with 8 existing deprecation warnings, and build. The retry-order archive, immutable publication, path, bar, and reconciliation regressions passed without source repair. CI/PR and configured object-storage evidence remain absent, so status is `verification_pending`.

## Increment P10-I3 — Provider adapter, Kafka/WebSocket, and live runtime

| Field                   | Value                                                               |
| ----------------------- | ------------------------------------------------------------------- |
| id                      | P10-I3                                                              |
| title                   | VCI live collector control, archive, and operational runtime        |
| status                  | blocked                                                             |
| priority                | critical                                                            |
| depends_on              | [P10-I0 completed, P10-I2 completed]                                |
| blocks                  | []                                                                  |
| owned_modules           | [apps/core, apps/ingestor, libs/py-common, configs, database, docs] |
| execution_mode          | approval_required                                                   |
| requires_owner_decision | true                                                                |
| pr                      | null                                                                |
| last_verified_commit    | null                                                                |

Goal: deploy an independently running, always-on Ingestor collector for VCI realtime trades. Platform owns audited desired state and visibility only; it does not cron-own or hold the provider connection.

Approved planning decisions:

1. Platform exposes a private operator-only enable/disable/status API with trusted operator identity, idempotency key, optimistic concurrency, and an immutable audit trail.
2. Platform persists desired collector state and atomically records an outbox command. The existing outbox publisher pattern sends an idempotent logical control command; browsers never publish Kafka directly.
3. Ingestor owns provider authentication, connection lifecycle, subscriptions, canonical mapping, deduplication, reconnect/resume, micro-batch buffering, READY-last archive publication, and graceful drain.
4. The collector reports heartbeat and state transitions back to Platform. Platform distinguishes desired state from observed state and marks stale heartbeats unhealthy without inventing a terminal scheduler execution.
5. The control contract carries logical collector identity, desired state, generation/version, command ID, requested-by identity, and request time—never credentials or physical object paths.
6. Market ticks use the strict P10-I1 contract. Any cross-service tick Kafka transport, if P10-I0 proves it necessary, requires a separately reviewed Proto3 or explicitly approved strict-JSON contract with producer/consumer tests and canonical Kafka documentation.
7. Archive writes use P10-I2 bounded batches and immutable publication. A shutdown/disable command stops new subscriptions, drains the accepted buffer, publishes validated parts, then acknowledges the applied generation.
8. Reconnect, resume, gap detection, corrections/cancellations, late events, and sequence handling must be implemented exactly from P10-I0 evidence. Unknown behavior fails closed and prevents completion.

Planned lifecycle:

```text
desired DISABLED -> ENABLING -> RUNNING -> DRAINING -> DISABLED
                                |    |
                                |    -> DEGRADED
                                -> ERROR
```

Only desired state is operator-controlled. Observed state and heartbeat are collector-reported. Duplicate or older generations are acknowledged without reapplying; a newer generation supersedes earlier unapplied intent.

Acceptance criteria:

- enabling/disabling is authenticated, audited, idempotent, generation-fenced, and committed with an outbox message atomically;
- collector restart converges to the latest desired generation without duplicate subscriptions or data loss within documented provider guarantees;
- heartbeat visibility reports collector instance, applied generation, observed state, last provider event time, last archive publication, reconnect count, and sanitized error category;
- provider secrets remain deployment configuration and never enter APIs, Kafka payloads, logs, manifests, or browser responses;
- canonical mapping rejects/quarantines malformed or unprovable provider frames using the P10-I1 taxonomy;
- archive buffering is bounded and backpressure behavior is explicit; no per-tick object writes occur;
- reconnect/resume/gap/correction tests derive from P10-I0 evidence, not assumptions;
- archive publication remains immutable-version-before-READY and preserves prior READY on failure;
- disable and shutdown drain accepted ticks before acknowledging the applied generation;
- configured storage integration, broker integration, control-plane concurrency, restart/recovery, provider sandbox/observed session, deployment, metrics/alerts, CI, PR, and verified commit evidence are recorded;
- canonical Kafka, data-lake, architecture, flow, deployment, API, and repository-guidance documentation is synchronized.

Stop conditions: do not implement P10-I3 until P10-I0 and P10-I2 are completed and the owner approves the evidence-derived contract. Stop if VCI lacks an authorized realtime capability, if guarantees remain unknown, if the design would use the historical endpoint as pseudo-live data, if control bypasses Platform audit/outbox, or if publication bypasses READY-last semantics.

P9-I1 remains the completed-session reference only and stays `verification_pending`; P9-I4 remains `in_progress`; P9-I2/P9-I3 remain `superseded`.

## Repository Guidance Updates

Reviewed `AGENTS.md`, `CLAUDE.md`, and `.roo/rules`. No guidance update is required: existing rules already require shared Python placement, logical dataset references, shared path builders, no generated-contract edits, graph impact/change analysis, and READY-last publication. Canonical roadmap, supporting plan, data/flow docs, and documentation indexes must be updated in this change.

## Verification

Owner-authorized P10-I1/P10-I2 local verification passed on 2026-09-13 and again
on 2026-09-22: py-common sync, lint, 127 tests, and build. CI, PR/commit evidence,
configured object-storage integration, and production verification remain unresolved
for the active foundations. P10-I0 package/repository source inspection is recorded
above, but no provider call, credential access, live-session observation, or external
capability verification occurred. P10-I0/P10-I3 therefore remain blocked.

## Acceptance Criteria

Phase 10 is reactivated but gated: P10-I1/P10-I2 remain `verification_pending`; P10-I0/P10-I3 are `blocked`. All provider-independent source and local checks are complete. P10-I0 requires genuine VCI realtime evidence and owner review. P10-I3 requires completed P10-I0/P10-I2 plus owner approval of the evidence-derived implementation contract. Phase 9 status is unchanged.
