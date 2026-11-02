# P14 main source reconciliation ? 2026-11-01

I10 checkpoint (2026-11-01): `in_progress`; [shadow registration source and remaining gates](004-p14-i10-registration-checkpoint.md). Source is unverified, consumer disabled by default, worker handlers not connected. Manifest READY remains authoritative; no watchdog/cutover/tracing activation. Earlier pending statements describe the pre-implementation baseline.

## Baseline and evidence boundary

Reviewed fetched `origin/main` at `4cc0011` on `docs/p14-i10-i11-sync`.
The initial worktree was clean. `feat/P14-WIP` was not used as a baseline.
This is static source/document reconciliation, not increment verification.
No build, test, lint, format, database, provider or deployment command ran.
Graph tools were unavailable in the session; impact and post-edit review are manual.

## Commit and increment mapping

| Main commit | Source/decision | Increment and evidence state |
| --- | --- | --- |
| `b49c916` | Atomic work expansion and worker envelope | I8 source; retain separately recorded historical local acceptance |
| `3ce4060`, `652d416` | Shared atomic attempts, graph preparation, definition consolidation | I9 source; retain separately recorded historical local acceptance |
| `b191507` | Console WorkOperations, AtomicWorkDiagnosticsService, GraphWorkOperationsController, SQL diagnostics; ADR-008 and I10 plan | Diagnostics source present; I10 planned, not implemented |
| `66e5c49`, `a271245`, `c34914d` | Preset/lock-order, uncovered schedule/coverage, stale classification and I8/I9 closure | I8/I9 follow-up; does not implement I10 |
| `bde2f41`, `4cc0011` | Formatting/verification documentation and isolated Python CI tooling | No I10 readiness/watchdog or I11 implementation established |

`3661e5e` and `b191507` have identical trees (`git diff` has no file changes).
Use `b191507` for the main history mapping; do not infer the original hash itself
is an ancestor or treat its commit message as acceptance evidence.

No P14-I11 entry or scope was found in the main registry, Plan 030 or main commit
subjects. I11 is **unconfirmed**, not a newly registered pending increment.
Reserved tracing fields do not establish an I11 implementation or approved scope.

## Runtime versus target

| Boundary | Source present at baseline | Remaining I10 acceptance |
| --- | --- | --- |
| Kafka/readiness | `configs/shared/topics.yaml` has the existing `topic-sync-metadata` command; ADR-008 explicitly retains current manifest authority | Separate versioned registration topic, bounded arrays, shared schema/adapters, Platform inbox/consumer, PostgreSQL readiness and read-provider cutover |
| Write and completion | Existing output/manifest and terminal status paths; shared `JobStatusKafkaService.publish_status` publishes status | Validated output ? acknowledged metadata publication ? terminal status; durable correlation in either arrival order, replay, duplicate/conflict and partial-output handling |
| Watchdog | Diagnostics select published unfinished work older than 30 minutes, read-only | Actual scheduled timeout mutation, late status/event fencing, shared-attempt ownership, safe retry and 12-hour reconciliation after cutover |
| Storage | Current mutable Parquet paths and canonical manifest discovery remain | Preserve prior READY, exact-byte/version validation, write exclusion or version addressability; DB fencing alone cannot stop a stale storage writer |
| Query provenance | Query API already exposes `queryId` and `dataVersions`; manifest-based reads exist | Durable query-level snapshots of actually read inputs linked to producing output versions/work/attempts; optional trace context is a reservation, not instrumentation |
| Writer | I8/I9 work context is reusable; Plan 027/P12 remains deferred | Separate work identity, output `writeKey`, operation `intentId` and registration event/item identity; preserve every contributing work/attempt through batching |

The target ordering and failure recovery above are requirements, not descriptions
of an implemented registration protocol. Existing metadata reconciliation is not
the new output-registration consumer. No new PostgreSQL readiness migration or
reader cutover is established by V17 shared work or V18 definition consolidation.

## Identity and Console boundary

- `atomicWorkId` is immutable work identity. Persisted `workIdentity` is its alias,
  not another ID. Plan 030 requires alias mismatch rejection at future I10 boundaries.
- `atomicAttemptId` identifies one attempt; `GraphWorkRequestService` persists it
  alongside work context. `graphRequestId` identifies a request, not shared work.
  A shared attempt can have multiple request owners; never substitute one root.
- `traceContext` remains optional planned diagnostic context, outside work hashes,
  business parameters, versions, write keys and intent identity. No tracing SDK,
  exporter, collector or durable cross-service query trace is established here.
- `GraphWorkOperationsController` exposes snapshot, published-unfinished, work,
  request and consolidation reads under `/api/v1/jobs/atomic-work`.
  `AtomicWorkDiagnosticsService` uses read-only repeatable-read transactions,
  bounded windows/pages and SQL timeouts. It never mutates READY or execution.
- `WorkOperations.tsx` reports sampled/page counts and truncation, explicitly says
  worker processing and dataset READY are unknown, and labels metadata/watchdog
  planned and tracing reserved. Publication ACK is not worker start; RUNNING can
  mean preparation. The over-30-minute list is not an implemented watchdog.

## Manual cross-service impact and documentation disposition

Platform owns existing graph/status/diagnostic reads and future registration DB
transactions. Ingestor and Analyzer would publish registrations after output
validation; shared Python/contracts/configs need coordinated future schema and
publisher changes. Query Service and Console would migrate discovery/readiness
through Platform APIs, never direct worker DB credentials. Persistence needs future
inbox/version/coverage and completion correlation; V17/V18 are not that schema.
Operations needs backfill, shadow parity, cutover, late-write protection and rollback.
Tests need attributable execution evidence for every boundary; file presence is not
coverage. No runtime, generated output, migration, flag or configuration is changed.

Plan 030, registry, roadmap, release notes, field inventory, ADR-008, architecture,
Kafka/database/data-lake, execution flow, rollout, ownership and service guides link
this reconciliation. Existing I10 acceptance and identity/writer/query requirements
remain intact. Other analytical flow documents need no semantic rewrite: their
runtime still uses current manifests and this change introduces no dataset or flow.
Plan 024 and Plan 027 retain deferred tracing/writer ownership. Agent guidance and
indexes retain their current manifest/ADR-008 distinction and canonical ownership;
no new policy or independently scheduled implementation plan is introduced.

## Static consistency review and outstanding gates

Registry I10 stays `pending` after completed I9; no status or dependency is promoted.
No I11 row is invented. Historical I8/I9 acceptance and dated evidence remain intact.
Plan 030 retains its mandatory sections and unchecked I10 acceptance criteria.
Linked source/document paths and direct reconciliation links were inspected statically;
this is not an executed link-checker or a runtime verification pass. Graph impact
and change detection remain unavailable; no graph success is claimed.

I10 still needs implementation, attributable source/criterion/test mapping, focused
failure/retry/order/concurrency/authorization/lineage/READY-last tests and coverage,
complete required-check gate, backfill/shadow/cutover and operational safety evidence.
I11 needs an identifiable approved plan/source history before scope can be recorded.
Deployment, activation and provider verification are not established by merge or by
this documentation commit. Preserve the existing independent I8/I9 activation gates.

Owner verification handoff: record required checks with `tools/check_result.py`
and confirm when the conclusion is ready; consume only the compact conclusion.
No checks or owner attestation are supplied by this static audit.
