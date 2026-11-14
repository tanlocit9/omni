# I10 closure: safe backfill through Signals

Current review: [November 11 findings and acceptance gates](019-i10-review-20261111.md).
It records the frozen-attempt refresh correction, Query graceful-audit fix,
fresh local gate and read-only retained preflight. Local PASS does not close the
retained rollout gates.

The November 11 final local gate passes 14/14: Platform 911 cases (zero failures,
13 skips), Query 59, Console 34, actual worker-chain 14/14, Kafka recovery 5/5,
sharing/overlap and retained V26 BEGIN/ROLLBACK validation. The new exact-attempt
proof path preserves frozen VERSIONED input reads after rerun. Full retained
versioning/parity/cutover/rollback and owner acceptance remain unresolved.

November 11 terminal-replay implementation: the owner approved [durable audit/ACK
for terminal VERSIONED completion](017-i10-versioned-storage.md#terminal-completion-replay-audit-owner-approved-november-11).
Local DB is at V25, so the audit table is added to unapplied V26 source. Historical
13/14 failures and pending-proposal text below precede this amendment; new regression
results are recorded separately without removing the original failed evidence.

Owner scope revision November 11: automatic watchdog/expiry is disabled and moved
to [TD-018](../technical-debt/018-execution-watchdog-observation.md), nonblocking
for current I10. Legacy full Metadata scan duration is accepted outside the current
PostgreSQL-readiness observation path. Historical timeout tests/results below are
retained; the late-completion conflict and coordinated-cutover gates are not waived.

November 10 owner-approved [unified completion](018-i10-worker-completion.md) replaces
separate terminal messages for new commands. Results below predate this change
unless explicitly identified as completion verification.

Owner objective (2026-11-09): complete I10 with a working backfill chain through
SYNC_SIGNALS. This extends the Indicators-only entry point; it does not authorize
silencing provider/data/lineage errors or declaring unverified cutover complete.
Status: implementation and verification in progress.

## Request contract

```json
{
  "goal": "SYNC_SIGNALS",
  "targetDefinitionId": "<active canonical Signals definition UUID>",
  "sectorCodes": ["BANK"],
  "sectorLevel": 1,
  "dateFrom": "2026-11-02",
  "dateTo": "2026-11-06",
  "force": false,
  "rerun": false
}
```

Use the authenticated definition catalog to select the desired strategy, then POST
to /api/v1/jobs/backfills with a new Idempotency-Key. Existing request polling is
unchanged. targetDefinitionId is optional only when exactly one active canonical
definition matches the goal; a supplied inactive/alias/wrong-type ID is rejected.
Signals strategy and timeframe remain owned by the selected definition. Existing
Indicators requests without the new field remain compatible.

## Bounded implementation steps

1. Extend POST /api/v1/jobs/backfills with SYNC_SIGNALS and optional
   targetDefinitionId. Select an active canonical definition matching the goal;
   reject ambiguous or mismatched selection with 409. Strategy/configuration remain
   owned by the selected definition, not arbitrary request parameters.
2. For Signals goals, require paused/drained legacy Signals/Evaluation writers in
   addition to EOD/Indicators. Preserve the existing Indicators-only admission
   scope. Keep Symbols current snapshot and exact historical dates downstream.
3. Execute real worker/Kafka/Parquet/registration/READY regressions for trend
   momentum and Ichimoku. Confirm target membership, frozen EOD/Indicators inputs,
   polling and exact outputs. Confirmed strategies additionally require intraday
   producer/readiness coverage before any claim of support.
   Preparation rejects any ancestor without an enrolled output protocol rather
   than accepting a request that can wait forever on undeclared readiness.
4. Resolve physical write exclusion/version safety, then exercise timeout/rebalance,
   overlapping refresh and late writer cases. The owner approved physical versioning;
   see [the implemented opt-in protocol](017-i10-versioned-storage.md). P12 stays deferred.
5. Close attributable line/branch coverage gaps and execute the full I10 gate,
   including migration, shadow/parity, reader cutover/rollback and recovery evidence.
   Source/test passes alone do not complete retained-environment acceptance.

## Blast radius

Platform changes admission/definition selection and server-owned enrolled graph
preparation. Existing signal declarations already name canonical signal output;
frozen dependencies must still be bound before dispatch. Analyzer's Signals handler
is directly exercised by E2E, as are Ingestor EOD and shared Python registered
Parquet publication/read validation. Query must retain exact approved version
reads. Console has no UI change; existing polling reports the selected Signals
goal and member statuses. No protobuf/topic, storage-path, DB-schema or dataset
owner change is needed for the initial API extension. Physical writer safety is
a separate cross-service storage boundary with its own impact/approval review.

Graph was refreshed to fa0f68a before edits; initial impact remained truncated with
289 unresolved call sites. Canonical ownership/flow/Kafka/data/ADR documents and
direct source inspection are authoritative for cross-language impact.

## Verification in progress

The initial authorized Platform PostgreSQL/controller scope was recorded via
tools/check_result.py under P14-I10-signals-20261109. Existing platform:test-integration
is the real-worker disposable-infrastructure target. Current closure results are
recorded in the source-bound manifests below.
The first Signals worker-chain rerun passed 10/10 cases (eight Backfill policy/Signals,
one existing cross-service and one readiness case), no skips or failures. Both
TREND_MOMENTUM_V1 and ICHIMOKU_V1 produced enrolled Signals READY with four graph
members and two exact frozen EOD/Indicators input approvals. Only the market
provider is stubbed. No full-I10 completion is claimed. No retained DB
mutation, production provider request, feature activation, service restart or new
commit is performed by this initial source checkpoint.

Initial admission passed; the first worker-chain run passed the eight existing
cases but timed out in the two new Signals cases because the fixture did not start
the Signals consumer. The fixture now starts the actual SignalKafkaService; the
failed report is retained in artifacts/missing-signal-consumer. The passing rerun
is recorded under P14-I10-signals-chain-20261109; its compact gate is PASS (2/2).
The admission guard's JDBC JSONB
operator placeholder issue was corrected with jsonb_exists and rerun successfully.

Additional safety defect found during closure: OutputWriteAdmission skipped its
barrier when global enrollment was off despite persisted scoped backfill contracts.
It now checks persisted enrollment before deciding whether to skip; the generation
and timeout-ownership barrier applies in both modes. The existing PostgreSQL
enrollment/barrier regression is parameterized over both global flag values.
This does not solve storage-level late writes or establish a physical write fence.

Full Java/Python coverage plus contracts/query checks are being recorded separately
under P14-I10-closure-coverage-20261109; individual results must not be inferred
from the successful worker chain. The final admission suite also exercises the
unsupported-output-protocol guard added during this step.

The November 8 review remains the historical baseline. Roadmap I10 remains
in_progress until all required acceptance and cutover conditions have evidence.

## Source-bound closure evidence so far

P14-I10-closure-coverage-20261109 concludes PASS for all six executed targets:
platform:coverage (800 total cases, 789 passed/11 skipped), analyzer:coverage
(268 passed), ingestor:coverage (120 passed), py-common:coverage (420 passed,
5 Kafka integration cases skipped), contracts:test (generation/build/artifact
checks succeeded) and query-service:test (47 passed). No current protoc failure
is present. This broad run predates the final graph helper simplification below.

Graph candidate lookup now caches sector-to-symbol membership for today's static
topology; other scope pairs, including possible future symbol-to-sector edges and
GLOBAL upstreams, retain the existing generic matcher fallback. No mapping,
identity, selected membership or bound is removed. A public regression proves an
empty upstream expansion cannot return a partial graph. Focused final admission
passes 127 cases: 84 PostgreSQL, 25 controller and 18 graph, zero failures/skips
(before physical-storage changes).
Its source/coverage artifacts are retained under P14-I10-signals-final-20261109.
The expanded worker-policy portion passes 13/13: 11 policy/Signals cases plus
the two existing cross-service/readiness cases. Added scenarios prove multi-day
shared signal history, rerun without provider fetch, and forced EOD failure with
no downstream Signals publication. The final graph fixture also expands a Signals
goal over 1,357 symbols and three days: 4,071 targets, 12,215 works, 16,284 edges.
This is a planning regression, not a claim of that many executed worker jobs.

P14-I10-concurrency-20261109 also concludes PASS for platform:test-overlap and
platform:test-atomic-sharing with real disposable workers/brokers. These establish
the exercised owner-sharing/cancellation cases, not a physical stale-writer fence.

The following table is the API-extension checkpoint before physical-storage edits;
it is not current coverage for the changed storage protocol.

| Critical source              | Executed line / branch coverage | Report                        |
| ---------------------------- | ------------------------------- | ----------------------------- |
| IndicatorBackfillController  | 100% / 100%                     | Full Platform                 |
| OutputWriteAdmission         | 100% / 100%                     | Full Platform                 |
| GraphWorkRequestService      | 98.94% / 90.95%                 | Full Platform                 |
| WorkExpansion                | 97.40% / 91.08%                 | Full Platform                 |
| GraphWorkExpansion.Expansion | 96.88% / 84.62%                 | Final focused graph/admission |
| Signals handler              | 88.14% / 80.56%                 | Analyzer coverage             |
| Signals Kafka adapter        | 98.72% / 83.33%                 | Analyzer coverage             |
| Signals storage              | 92.20% / 81.63%                 | Analyzer coverage             |
| Signals evaluator            | 97.78% / 91.67%                 | Analyzer coverage             |
| Ingestor stock_prices        | 98.88% / 95.83%                 | Ingestor coverage             |
| Shared output_publication    | 97.33% / 87.50%                 | py-common coverage            |
| Shared output_readiness      | 91.19% / 84.38%                 | py-common coverage            |
| Shared Parquet storage       | 98.55% / 95.83%                 | py-common coverage            |

The dedicated owner-approved py-common:test-integration target subsequently passed
all five real Kafka replay/recovery cases under P14-I10-kafka-recovery-20261109.
Coverage skips above are not counted twice. Physical writer safety was also approved;
its source and regression status follow below. Coverage alone does not close cutover.

## Approved physical safety implementation and current blocker

The owner approved version-addressable canonical objects, frozen accepted merge
bases and exact-version readers. [V26, compatibility and the operational procedure](017-i10-versioned-storage.md)
record the implemented opt-in protocol. No live migration, bucket change, lifecycle
policy or global reader activation has been performed.

The first versioned E2E run caught an incorrect positional merge mask derived from
sorted row keys. It was corrected to select unowned rows by their actual coordinates;
the unit regression now deliberately supplies reversed physical row order.

The next E2E run passes 13/14 cases, including real Trend Momentum/Ichimoku,
range/rerun/force failure paths. In the last case, a replacement Signals request
completes after real consumer-group revocation and watchdog timeout. Query verifies
accepted EOD 777/888 and Signals proofs after the old worker physically PUTs its
previously encoded 444. The following rerun still times out: the old registration
conflicts with the replacement's replay evidence under the same immutable event ID,
pausing the registration partition. The passing physical-read phase does not close
this end-to-end recovery gate.

Historical proposal (superseded by November 11 approval below): retain raw late payloads in immutable rejection
history and ACK only when every item belongs to a VERSIONED attempt already timed
out. Preserve first registrations/READY/status; mixed batches and other poison retain
the existing pause policy. This changes persistence/event handling and is not yet
implemented or authorized. No logical conflict is normalized away.

Gate P14-I10-storage-20261109 records current contracts, Platform, Analyzer, Ingestor,
py-common, Query and worker-chain reruns. It remains unresolved while the physical
recovery test fails. Console test and changed-file formatting approvals were requested
separately; neither is inferred from earlier command approvals.

The final full Platform coverage run passed on November 10: retained XML totals
847 tests, zero failures and 13 skips, under artifacts/platform-full-20261110.
It includes the historical-baseline route, shared-wire contract and CANONICAL
enrollment rejection for already pinned partitions. Ingestor passes 120 tests.
Query passes 48; py-common passes 510 with five Kafka cases skipped in that suite
and independently executed by the dedicated integration gate. Analyzer passes 274.
Its coverage configuration now includes the real root main module: startup executes
68/68 lines and 20/20 branches; shutdown executes 20/20 lines and 18/18 branches.
The whole entrypoint file is 71.00% line / 95.83% branch, including unrelated API
routes outside this change; no whole-file coverage pass is claimed.

The physical-baseline/shadow E2E passes under artifacts/physical-baseline-shadow.
It exercises the actual fenced API, preserves bytes/logical versions/registrations,
compares two registered rows against full legacy files and finishes a versioned
Signals request through workers and Query. Initial shadow fixture bootstrap failed
because exact metadata sync requires an existing discovery document; fixture setup
now uses the canonical full synchronizer. Failed evidence remains retained.

Shadow-mode enrolled reads previously lacked the PostgreSQL work-reader adapter.
The shared reader now exposes that route, and Analyzer/Query supply their Parquet
validator for scoped byte/row/lineage comparison. The current py-common report gives
output_readiness.py 93.27% line / 87.50% branch; the changed shadow block executes
76/76 lines and 18/18 instrumented branches. These metrics do not certify unowned
history or prove retained-environment parity.

| Added behavior                                    | Source                                            | Executed evidence                                                                                                 |
| ------------------------------------------------- | ------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------- |
| Shadow frozen-input routing and scoped row parity | shared ShadowMetadataReader/read_input_document   | test_versioned_output_storage shadow routing, byte/row/column/inventory/lineage failure cases; py-common coverage |
| Analyzer reader wiring and lifecycle              | main.startup_event/shutdown_event                 | six mode/consumer combinations in test_worker_health; analyzer coverage                                           |
| Query validator wiring                            | storage.get_services                              | test_shadow_services_share_the_query_parquet_validator; Query test                                                |
| Historical pin, shadow parity and Signals chain   | baseline coordinator, private API, workers, Query | I10WorkReadinessIntegrationTest; physical-baseline check                                                          |

The final shadow graph impact reports 44 changed nodes, 106 impacted nodes and
32 additional files within two hops; results are truncated with 93 unresolved call
sites. New untracked fixture/head files remain incompletely indexed. Direct
cross-service reconciliation above is required; zero-flow output proves no isolation.

The source change does not add Console UI fields, alter ownership/path builders,
change Kafka schemas beyond the already documented optional physical coordinate,
or add another migration. Full retained-reader parity and rollback remain gates.

## Physical-safety attribution and completion audit

### Unified completion checkpoint (November 10)

Owner-approved [terminal event consolidation](018-i10-worker-completion.md) is
implemented for newly declared commands; old queued commands remain compatible.
The new shared fixture round-trips in Java/Python, new success/error processing
uses one Kafka record, and Platform transaction tests prove registration/status
rollback together. Status updates now preserve structured frozen outputCompletion
and cannot overwrite it from worker metadata. Evaluation tests exercise valid-empty,
validation failure and calculation failure through the actual Kafka adapter.

Current executed results: py-common 537 pass/5 Kafka skips, Analyzer 277 pass,
Ingestor 120 pass, contracts generate/build with 14 verified artifacts. The actual
worker chain passes 13/14: pausing the completion consumer leaves both registration
and completion status absent in DB; restarting commits the pair and permits
downstream READY. Historical physical pin, scoped shadow comparison, Signals and
Query remain passing in that run. Evidence is retained under
artifacts/unified-completion-chain. Full Platform coverage subsequently passes
897 tests, zero failures and 13 skips; retained reports are under
artifacts/unified-completion-full. The final focused run passes 159 tests, zero
failures/skips, under artifacts/unified-completion-final-focused. Its PostgreSQL
lateVersionedCompletionStillRejectsDifferentLogicalEvidenceWithoutReopeningFailure
case proves that differing logical payloads for the expired attempt still raise
Conflicting immutable eventId without reopening FAILED or creating READY/head.
This focused cause evidence is separate from the broad E2E timeout observation.

| Completion source          | Executed source-bound coverage                  | Relevant tests                                                                                        |
| -------------------------- | ----------------------------------------------- | ----------------------------------------------------------------------------------------------------- |
| WorkerCompletionEvent      | 45/45 lines; 75/90 branches (100%/83.33%)       | paired fixture, malformed identity/status/counters, success/error and metadata counters               |
| WorkerCompletionStore      | 34/34 lines; 23/26 branches (100%/88.46%)       | PostgreSQL success/error/replay, declared identity rejection and rollback after registration          |
| OutputRegistrationConsumer | 10/10 lines; 6/6 branches                       | new/legacy routing and oversize/null rejection                                                        |
| JobService                 | 444/468 lines; 204/230 branches (94.87%/88.70%) | full Platform; completion replay preserves structured server declaration and rejects worker overwrite |
| shared worker_completion   | 100% lines; 96.88% branches                     | paired fixture, one broker ACK, failure/no legacy fallback, malformed and metadata-counter cases      |
| shared output_publication  | 93.68% lines; 80.77% branches                   | legacy publication and completion staging/terminal helpers, plus real worker E2E                      |

Graph reconciliation includes Platform acceptance/status/declarations, shared ports,
all four producer adapters, contracts, DB atomicity and deployment. Query/Console
keep current APIs; no new schema migration, topic or physical path is introduced.
The bounded graph reports 149 impacted nodes/67 additional files at two hops,
truncated with 258 unresolved calls; new untracked completion files need direct
source/test inspection and are not proven isolated by graph absence.

The last E2E still fails on timed-out revoked writer recovery: accepted immutable
bytes survive, but conflicting late evidence blocks the following rerun. One terminal
record removes the two-topic join; it does not authorize accepting contradictory
evidence. The published-based watchdog deadline and retained cutover gates also
remain unchanged. No audit/ACK table, automatic retry redesign, flags or live cleanup
was added. Component counters below are the earlier physical-storage checkpoint;
they are not substituted for completion-specific attribution.

### Earlier physical-storage checkpoint

The following counters are covered/total from the retained November 10 full
Platform JaCoCo report, not aggregate project coverage. GraphWorkRequestsPostgresTest
executes the physical-head, baseline, frozen-base, strict-reader and downgrade
regressions; named cases were checked in its retained JUnit XML with no failures
or skips. The broader report includes the controller/declaration/token suites.

| Safety component            | Executed behavior                                                      | Line    | Branch  |
| --------------------------- | ---------------------------------------------------------------------- | ------- | ------- |
| OutputStorageHeads          | freeze once, monotonic promotion, immutable legacy baseline            | 33/33   | 20/22   |
| OutputCompletionGate        | policy enrollment and pinned-partition downgrade rejection             | 99/110  | 70/78   |
| OutputDeclarations          | versioned declarations and compatibility                               | 39/39   | 29/30   |
| OutputReadinessStore        | registration plus SUCCESS before pointer promotion                     | 40/40   | 15/16   |
| OutputWorkReadiness         | exact approved work and immutable-coordinate requirement               | 71/71   | 34/36   |
| OutputWriteAdmission        | live-owner serialization and safe timeout replacement                  | 8/8     | 4/4     |
| OutputRegistrationStore     | first physical coordinate on identical replay; reject logical conflict | 114/114 | 45/54   |
| OutputReadinessProvider     | bounded physical-coordinate catalog and legacy canonical route         | 133/140 | 59/68   |
| OutputBackfillService       | fence/revision, immutable baseline, invalid inventory rejection        | 87/89   | 65/77   |
| OutputBackfillController    | authenticated storage-pin request validation                           | 32/32   | 26/32   |
| TrustedOperatorHeaderFilter | separate reader/reconcile route scopes                                 | 34/34   | 24/28   |
| SchedulerOutboxService      | claim-time frozen bases and replay reconstruction                      | 224/230 | 118/136 |
| GraphWorkRequestService     | compatible policy sharing and request lifecycle                        | 372/376 | 192/212 |

All listed Java components exceed 80% line and branch. Shared Python's executed
reports cover output_publication (96.10%/83.33%), output_registration
(96.43%/90.79%), output_reconciliation (98.72%/95.45%) and Parquet storage
(98.08%/94.90%). New MinIO exact-version read/write methods are exercised at
100% line/branch by the versioned-storage tests; the whole legacy adapter is
53.45%/44.44% and is not claimed to pass the component threshold. Global metadata's
new physical-version validation executes both outcomes (2/2 instrumented branches).
Twelve added cases reject invalid model/JSON coordinates and round-trip absent,
minimum and maximum-length versions without changing logical identity. Whole-module
coverage is 79.56%/54.17%, not a component-wide pass. Root Analyzer
startup/shutdown attribution and the Query wiring limitation are recorded above.

Completion remains unproven against the complete Plan 030 requirements:

| Required gate                                            | Current evidence                                                               | Remaining requirement                                                                                        |
| -------------------------------------------------------- | ------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------ |
| Registration, enrollment, READY-last and frozen identity | full Platform, contracts generation/shared fixture, worker-chain passing cases | late-event failure below still prevents full recovery acceptance                                             |
| Exact-version writer/reader safety                       | scoped Python tests, physical-baseline/shadow/Signals/Query E2E                | no retained bucket/version-retention or deployment evidence                                                  |
| Replay, timeout and rebalance                            | five dedicated Kafka integration passes; replacement Signals survives late PUT | subsequent rerun fails after conflicting late registration pauses its partition                              |
| Backfill and shadow                                      | disposable actual API pin and two-row scoped comparison pass                   | complete retained inventory and all-reader parity are not established                                        |
| Coordinated cutover and rollback                         | source/runbook and existing local logical-rollout rehearsal                    | versioned retained cutover/rollback retaining subsequent registrations not executed                          |
| Attributable coverage and full required-check gate       | source counters above; storage scope manifest retains its failure              | Console test, Platform build and changed-file format not executed; storage manifest is not the full I10 gate |
| Documentation and delivery                               | canonical scope/status synchronized; bounded graph/static review               | no owner attestation, live activation or increment completion inferred                                       |

Platform build appears in Plan 030's proposed verification list and has not been
approved for this continuation. Console test and formatting questions remain pending.
The historical audit/ACK proposal changed schema/event policy and needed separate
approval, now recorded in the November 11 checkpoint below.
No required check is removed or converted into a passing result by this audit.

## Terminal replay checkpoint (November 11)

Owner-approved terminal handling is implemented in WorkerCompletionStore and
unapplied V26. Local Flyway history is V25; no retained migration or service restart
was performed. This amendment supersedes the historical pending audit decision and
late-completion failure above without rewriting its failed evidence.

The real disposable worker-chain target now passes 14/14 tests, zero failures or
skips, including the revoked writer's late PUT, immutable accepted Query reads,
durable TERMINAL_FAILED_LATE audit and the subsequent successful rerun. It uses real
Kafka/PostgreSQL/MinIO and Python workers with a mocked market provider. Evidence:
.agent/check-results/P14-I10-terminal-replay-20261111/artifacts/worker-chain.
Full Platform coverage passes 907 cases across 102 suites: zero failures/errors,
13 skips (894 executed passes). The final identity/concurrency cases execute in
this run. Required checkpoint P14-I10-terminal-replay-20261111 is PASS 3/3;
the prior storage-scope manifest is now PASS 10/10 after importing the actual
successful worker-chain and coverage logs, retaining its failed run history.
Neither manifest is the complete retained-environment I10 acceptance gate.

| Changed source / safety boundary                                             | Executed tests / evidence                                                                                                                                                                                                 |
| ---------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| WorkerCompletionStore terminal SUCCESS                                       | successfulVersionedReplayKeepsFirstEvidenceAndAuditsOnlyLogicalConflict; first coordinate retained, different logical evidence audited once                                                                               |
| WorkerCompletionStore terminal FAILED and audit persistence                  | lateVersionedCompletionIsAuditedWithoutReopeningFailureOrBlockingReplay; auditFailureRollsBackAndMustBeRetriedBeforeCompletionCanBeAcknowledged                                                                           |
| V26 output_completion_rejections                                             | disposable Flyway application, immutable audit UPDATE/DELETE rejection and transactional audit-write failure above                                                                                                        |
| WorkerCompletionEvent constructor wiring                                     | focused WorkerCompletionEventTest and PostgreSQL completion tests pass                                                                                                                                                    |
| Real replay/rebalance -> Signals -> Query                                    | BackfillDataPolicyIntegrationTest plus I10WorkReadinessIntegrationTest and JobRunCrossServiceIntegrationTest: 14/14 pass                                                                                                  |
| Final strict declarations, CANONICAL compatibility and concurrent completion | terminalCompletionMustValidateDeclarationsBeforeItCanBeAudited (five invalid cases), canonicalTerminalConflictRetainsStrictHandling and concurrentVersionedCompletionsAcceptOnlyOneAndAuditTheOther pass in full coverage |

Source-bound counters from the retained November 11 full JaCoCo report:
WorkerCompletionStore 62/64 lines (96.88%), 46/52 branches (88.46%);
WorkerCompletionEvent 45/45 lines, 75/90 branches (83.33%);
OutputRegistrationConsumer 10/10 lines and 6/6 branches; directly reused
OutputCompletionGate 99/110 lines (90%), 70/78 branches (89.74%). Reports and
JUnit XML are under the same checkpoint's artifacts/coverage directory. All named
safety components exceed the repository's 80% line/branch threshold.

Graph was refreshed after implementation: scoped impact recognizes 12 changed
nodes and no additional nodes at two hops. This is bounded navigation evidence,
not proof of cross-service isolation; reconciliation follows below.

Current scope reconciliation: Platform owns execution locking, strict validation,
audit persistence and ACK eligibility; persistence adds the V26 table/index/trigger.
Analyzer and Ingestor keep their existing completion payloads and are exercised by
the real chain. Query retains accepted immutable reads. Console has no API/UI change.
Shared contracts/py-common, storage paths, dataset owners, Kafka topics and runtime
configuration have no source change in this amendment; no generated output changes.
Operations must apply the amended V26 before this runtime. An environment with an
older applied V26 needs a forward migration, not history repair. Legacy records and
invalid identities remain strict; broad poison-record/DLT policy is still deferred.

Retained inventory/parity, coordinated cutover/rollback, and unexecuted Console,
Platform build and formatting gates remain open. Watchdog is owner-deferred TD-018.
I10 remains in_progress; this local checkpoint is not retained/provider acceptance.

## Documentation synchronization scope

Updated canonical areas: Plan 030, increment-registry notes (status remains
in_progress), root release notes, architecture, ADR-008, Kafka/data-lake/database,
P14 flow, deployment rollout, current identity handoff, documentation indexes and
the affected Platform/Analyzer/Ingestor/Query/shared-library guides.

Reviewed without a policy/ownership change: roadmap scheduling/dependencies and
governance retain the existing I10 acceptance gate; where-to-change ownership is
unchanged; repository AGENTS/editor guidance already requires logical references,
READY-last and coordinated cutover and links the amended canonical handoff/ADR.
Console UI contracts/source are unchanged because physical coordinates stay behind
Query's validated-buffer adapter. Shared path builders/configuration keep existing
keys; the new default-off property belongs to Platform configuration. P12, tracing
and general worker recovery remain deferred. These no-update reasons do not waive
verification or retained-environment rollout.

Static documentation review checks the new reference IDs, migration order, local
links and conservative status/approval wording. No executable documentation checker,
automatic formatting or owner attestation has been run for this checkpoint.
