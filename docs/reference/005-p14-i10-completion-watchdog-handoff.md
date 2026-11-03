# P14-I10 completion and watchdog continuation

Source-state superseded by [READY integration](006-p14-i10-ready-integration.md). Earlier NOT RUN and missing-source statements below describe this historical checkpoint; its open runtime and migration gates remain.

## Delivery branch

The owner authorized creating and publishing `codex/p14-i10-snapshots` on
2026-11-02. The branch contains the reproduced registration checkpoint and local
I10 continuation from checkout baseline `4cc0011`. This delivery does not modify
the existing remote `p14-i10` branch or mark I10 complete. Verification remains
NOT RUN, immutable snapshots are approved, and additional durable worker recovery
remains deferred. Git is authoritative for the resulting commit and publication.

## Owner scope revision — immutable snapshots only

On 2026-11-02 the owner approved immutable output snapshots and explicitly deferred
additional durable publication recovery. This supersedes the earlier combined
proposal below. Do not add a recovery journal, automatic replay/recovery service,
new writer service, batching or concurrency activation to this continuation.
Existing Kafka duplicate/idempotency checks, terminal fences, failure reporting and
retention of previously validated READY remain required. Durable Platform
status/registration correlation is still in scope; it is not a new worker recovery
subsystem.

### Snapshot source

- `output_snapshots.py` derives a byte-version address from dataset, sorted logical
  partition hash and canonical dataVersion. StockDataPaths owns the configured
  `_output-snapshots/{dataset}/{partition_hash}/{data_version}.parquet` layout.
- ParquetStorage describes the exact encoded buffer, computes the same canonical
  dataVersion used by current metadata, writes/reads back the snapshot before
  replacing canonical bytes, and returns an OutputSnapshot descriptor. Replacement
  snapshots use the final canonical identity, not a temporary filename.
- The version reader checks logical identity, bytes, checksum/length, schema and
  persisted lineage. Existing conflicting/corrupt snapshots are rejected rather
  than overwritten. Conforming concurrent writers for a version can only publish
  equal bytes; this is a content-addressed application protocol, not an S3 object
  lock/WORM or ACL guarantee.
- Shared output_lineage.py supplies the existing metadata-sync lineage extraction,
  avoiding a second version/hash rule. Worker integration currently covers EOD,
  indicators, signal history and signal outcome replacements. Sector datasets and
  the existing intraday publication protocol are not changed by this addition.
- OUTPUT_SNAPSHOTS_ENABLED defaults false. Worker wiring and configured path
  injection are present; no environment flag was turned on. Snapshot failure
  propagates through existing worker error handling and cannot report SUCCESS.
- Snapshot APIs create no latest/READY pointer and write no canonical metadata.
  Kafka registration handlers/enrollment and PostgreSQL reader cutover still need
  integration. Query Service/admission do not switch authority merely because the
  snapshot option is enabled.
- ACK loss can leave an orphan snapshot and a reported failure. This is accepted
  as a visible manual-resolution boundary for the deferred recovery scope; no
  hidden repair loop/journal is added. No automatic deletion/GC of snapshots.

Snapshot regression source (NOT RUN): shared test_output_snapshots covers late
mutable writers, duplicate publication, concurrent versions, conflicting/corrupt
snapshots, read-back/write/ACK failure, replacement staging identity, exact lineage
and unsafe path config. Ingestor test_stock_prices, Analyzer test_indicators and
test_signals cover opt-in identities and rejection/failure propagation. No numeric
coverage, integration, immutability-under-external-writers or rollout pass is claimed.

## Current state — 2026-11-02

P14-I10 remains **in_progress**. This continuation is not implementation-complete,
verified, deployed, or authorized for reader cutover. I10.a/b/c remain checkpoints
of the same registry increment; no I11 or P12 scope is activated.

The current clean checkout started at `4cc0011`. The GitHub connector supplied the
complete tracked `4cc0011...dc424b7` change, reproduced locally without creating a
branch/commit or changing the Git index. Direct Git fetch failed because the
configured proxy was unreachable. No prior uncommitted worktree was available.
In particular, the V20/CompletionGate/Watchdog mentioned in the automation prompt
were **not recovered**: the V20 and classes here are new source reconstructed in
this continuation. Historical claims of 49 Python/34 Java passes do not verify it.

## Source delivered

- [V20](https://github.com/tanlocit9/omni/blob/5204939adf6d486811ab647bcdaa06fbdaf0020f/database/migrations/V20__output_completion_and_timeout.sql): additive
  completion contracts, first-SUCCESS inbox, immutable timeout evidence, normalized
  per-output provenance/exact-date coverage, composite attempt/execution integrity
  and a published-deadline index. No historical work is implicitly enrolled; no
  READY pointer/backfill is created.
- [OutputCompletionGate](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/metadata/OutputCompletionGate.java):
  a server-owned required-output/valid-empty contract is frozen before dispatch.
  SUCCESS waits durably for registration. Matching registration first and SUCCESS
  first are both supported; the first accepted SUCCESS payload is replayed rather
  than replaced by later timestamps. Enrollment after shadow evidence or possible
  dispatch is rejected. There is **no production enrollment caller yet**.
- [OutputRegistrationStore](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/metadata/OutputRegistrationStore.java):
  locks execution rows in UUID order, validates enrolled declarations, commits all
  event members/output/coverage evidence atomically, then emits synchronous internal
  completion signals in the transaction. Shadow-only executions cannot acquire
  completion authority from an event. Registration is still not storage validation.
- [JobService](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/services/JobService.java):
  production status transitions own the same execution lock; work and parent
  identity are checked before pending SUCCESS is recorded. Enrolled completion
  reuses normal parent aggregation/notification behavior. Terminal guards reject
  reopening by late events/statuses; non-enrolled work retains legacy behavior.
- [ExecutionTimeoutWatchdog](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/metadata/ExecutionTimeoutWatchdog.java):
  disabled by default, two-minute scan, thirty-minute published deadline,
  bounded candidate/transaction batches and execution locks with SKIP LOCKED.
  Unpublished/partially published work is excluded. It rechecks the deadline and
  terminal state, records UNCERTAIN write ownership and routes FAILED through
  JobService. No rerun, storage deletion or ownership-release claim is performed.
- [OutputCompletionDiagnostics](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/metadata/OutputCompletionDiagnostics.java):
  authenticated read-only `GET /api/v1/jobs/output-registrations/executions/{id}`
  distinguishes unenrolled/pending streams/terminal state and exposes actual
  publication age/timeout evidence. It explicitly reports manifest authority and
  never claims that registration establishes dataset READY.
- Java/Python registration adapters reject numeric coercion/overflow, abbreviated
  UUIDs and timestamp/epoch coercion of calendar dates. Python output counters use
  signed-64-bit bounds, schema versions signed-32-bit bounds and strict nullability.
  Shared topic configuration now includes the frozen registration topic. Existing
  topic provisioning and worker handler behavior are unchanged.

## Remaining implementation and release blockers

1. **Worker registration integration:** snapshot publication is wired for EOD,
   indicators and signals, but handlers do not yet publish registration. Freeze the
   per-JobType declarations and attempt/event identities before dispatch, validate
   actual output bytes/schema/dates/approved lineage, then connect enrollment.
   On publication uncertainty report failure/pending; additional durable worker
   recovery is deferred by the owner and is not a required implementation blocker.
2. **Storage safety:** current EOD/indicator/signal paths are mutable. A timed-out
   worker can still overwrite them. A DB fence/timeout cannot prove exclusion.
   The owner now permits physical immutable snapshot addresses inside I10, with
   no new writer service. Snapshot source is present, but executed version-reader
   safety, registration/provider integration and all affected writer/readers must
   still be proven before moving affected readers. This is an architectural prerequisite, not a test to waive.
3. **I10.b:** implement the authenticated READY provider/API, all reader/admission
   adapters, validated/fenced backfill and shadow comparison, coordinated cutover
   and 12-hour reconciliation. None is supplied by the completion evidence API.
   Current SYNC_METADATA remains the canonical JSON writer; no stale JSON fallback
   is introduced after a future cutover.
4. **Migration identity:** the previous uncommitted V20 content/checksum is unknown.
   Compare the original checkpoint and any retained Flyway history before selecting
   a release migration identity. Do not run this candidate on an environment that
   may already have another V20; never use Flyway repair to hide a mismatch.
5. **Verification:** no build/test/lint/format, migration, Kafka/MinIO/PostgreSQL
   runtime or deployment was executed. Source/test presence is not a passing gate.
   Real-Kafka offset/rebalance/poison-partition isolation, crash/ACK failure,
   shared-attempt workflows, complete impact/coverage and rollout remain open.

## Cross-service blast radius

### Historical combined proposal — partially approved above

The concrete recommended extension is addressable immutable output publication
inside the current Ingestor/Analyzer processes, **not** a P12 writer service,
batching engine or worker-concurrency activation:

1. Keep logical dataset/partition ownership and business-date semantics. Add an
   explicitly configured physical version-snapshot layout derived from the logical
   output and canonical dataVersion; never put physical paths in Kafka business
   messages. Keep canonical version hashing tied to trusted logical object identity,
   and use exactly the same rule in every byte-validating reader.
2. Freeze output declarations, attempt IDs and publication IDs in Platform before
   dispatch. Serialize overlapping output ownership/generations. Validate complete
   bytes/schema/actual dates/approved lineage, persist a bounded immutable recovery
   intent and snapshot, then publish the unchanged registration before status.
   Replay loads that intent rather than rerunning with newly generated identities.
3. Promote a DB READY pointer only for the eligible producing generation and after
   both completion streams commit. Late attempts cannot replace it. Readers address
   the immutable version and revalidate exact bytes; failure preserves prior READY.
4. Migrate all writers/readers with backfill/shadow parity, drain existing writers,
   then coordinated cutover. A failed prerequisite leaves the current route guarded;
   there is no default switch or stale-manifest fallback. Conditional/create-only
   storage support and recovery need executable evidence, not an operator assertion.

The owner approved the immutable-snapshot physical-storage extension and deferred
worker durable recovery. Only that explicit scope revision supersedes this proposal;
no new increment ID or P12 activation is implied.

Graph MCP/CLI was not exposed/installed in this environment. Pre-edit discovery
and post-edit discovery cannot substitute for graph impact/change analysis; that
gate remains unavailable. Targeted source and canonical-document reads establish
the following manual boundary reconciliation, not executed coverage.

| Area | Impact / boundary |
| --- | --- |
| Platform | Registration validation/persistence, enrolled status correlation, child locks, timeout failure, authenticated diagnostics; normal parent/request aggregation remains owner. |
| Ingestor | EOD has opt-in snapshot publication through shared ParquetStorage. Intraday/catalog protocols are unchanged; registration/enrollment still needs integration. |
| Analyzer | Indicators and signal history/outcome writes gain opt-in snapshots. Shared lineage is reused by metadata sync. Sector and intraday output protocols are unchanged; registration/READY integration is still open. |
| Query Service | No runtime/source change; continues manifest discovery/exact-byte safeguards. PostgreSQL reader migration is missing. |
| Console | No runtime/source change; no UI integration of the new diagnostics endpoint, no READY-authority claim. |
| Shared contracts/libraries | New snapshot descriptors/publisher/read API, optional ParquetStorage arguments/result field, shared lineage extraction and configured address builder. Registration schema/wire unchanged by snapshots; no generated artifacts edited. |
| Persistence | Candidate V19/V20 are additive source; normalized coverage uses actual registered dates, not automatic work-date inference. Migration/upgrade/retained-data evidence is missing. |
| Configuration | Topic key and snapshot layout added; OUTPUT_SNAPSHOTS_ENABLED, shadow and watchdog default false. Timeout PT30M, delay 120000 ms, batch 100. No running environment flags changed. |
| Operations | Poison event still retries without discard; watchdog marks write ownership uncertain. No deployment, automatic repair, rerun or reader cutover. |

## Feature/source/test matrix

All tests below are **NOT RUN**, with no attributable numeric coverage claim.

| Criterion | Source / test source |
| --- | --- |
| Strict cross-language counters/nullability/UUID/calendar dates | OutputRegistrationEvent/Store; OutputRegistrationEventTest; py-common test_output_registration |
| Either arrival order, duplicate status/event, first-status durability | OutputCompletionGate/Listener/JobService; GraphWorkRequestsPostgresTest.completionRequiresBothStreamsInEitherOrderAndRetainsReplay |
| Concurrent stream acceptance | Same components; concurrentRegistrationAndStatusConvergeOnOneCompletion |
| Server declarations and approved valid-empty | Gate/Store; workerCannotReplaceServerRequiredOutputs; validEmptyCompletesOnlyForAnApprovedServerReason |
| Invalid parent leaves no pending SUCCESS | JobService; invalidParentCannotCreatePendingCompletionEvidence |
| Immutable provenance and exact date rows | V20/Store; completionRequiresBothStreamsInEitherOrderAndRetainsReplay |
| Published deadline, unpublished exclusion, deadline edge, repeat scan, late event/status | Watchdog/JobService; watchdogUsesPublishedDeadlineAndLateStreamsCannotReopenTimeout |
| Whole-envelope rollback/idempotency and immutable history | V19/V20/Store; existing outputRegistrationShadowIsAtomicIdempotentAndCannotChangeExecutionReadiness |
| Diagnostics authorization, API/database failure, multi-instance watchdog contention, deployment defaults | Required additional test/evidence; not claimed covered |
| Real workers -> durable outputs -> Kafka -> completion -> READY -> Query | Missing implementation/evidence, not satisfied by the PostgreSQL tests |

## Verification handoff

The exact initial command list was presented to the owner for approval. Until a
reply authorizes execution, all commands remain proposed/NOT RUN:

```text
nx run contracts:test --skip-nx-cache
nx run py-common:coverage --skip-nx-cache
nx run platform:coverage --skip-nx-cache
nx run ingestor:coverage --skip-nx-cache
nx run analyzer:coverage --skip-nx-cache
nx run query-service:test --skip-nx-cache
```

These declared targets are only an initial check set, not the full I10 acceptance
gate. In particular they do not establish complete real-Kafka or READY cutover
evidence. Record required checks via `tools/check_result.py`; never owner-attest
an incomplete gate. Retained migration/service deployment requires separate review.

## Documentation review

Plan 030 and registry retain in_progress/dependency I9; roadmap/index/release notes,
ADR-008, architecture/execution flow, Kafka/data-lake/database, rollout/developer
ownership and Platform/shared-contract service documentation point here. Historical
checkpoint 004 is retained with an explicit supersession link.
Ingestor/Analyzer/Query/Console guides retain their current runtime boundaries and
receive a continuation link. Governance/cross-phase rules, CLAUDE and Roo already
require verified authority cutover; no policy or scope relaxation is warranted.
AGENTS receives a factual source-state clarification only.

Static consistency review follows verify-document-consistency: current scope,
registry ID/dependency, source paths, gate wording and authority boundaries were
reviewed manually. No executable checker ran. Graph and behavioral acceptance
remain unresolved; documentation review cannot mark the increment completed.
