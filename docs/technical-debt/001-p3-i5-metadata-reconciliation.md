# P3-I5 Metadata Reconciliation Technical Debt

## Temporary warning policy — 2026-10-26

Owner superseded the conservative partial-to-ERROR policy below. A returned
`MetadataSyncResult.is_partial` now emits terminal SUCCESS so partial discovery
does not fail the execution. Analyzer logs a warning with execution identity and
retains `metaJson.result=PARTIAL_SUCCESS`, warning text and all counters for
Platform persistence. Healthy results remain SUCCESS without warning fields.
This is an explicit temporary operational tolerance, not a claim of complete data.

Exceptions (invalid Parquet, empty full rebuild, invalid target, storage/publication
failure) still follow ERROR/FAILED. The synchronizer still rejects failed physical
validation before replacing discovery; no corrupt/empty publication is enabled.
Dataset EXISTS/READY and exact approved-version checks remain enforced separately.
No new top-level PARTIAL_SUCCESS status, enum or migration is introduced. Historical
ignored messages remain unchanged. The subsequent
[worker/entry follow-up](../deployment/004-job-run-admission-rollout.md#worker-recovery-and-terminal-entries--2026-10-26)
also makes Ingestor symbol warnings complete SUCCESS with persisted diagnostics.

Verification uses the shared Python/Java warning payload, parameterized
skipped/failed/healthy results and warning-log checks, plus an auxiliary-object
partial scan through real Kafka, MinIO and PostgreSQL. The same integration retains
the invalid-Parquet FAILED/prior-discovery-preservation check. The current local
gate is `P14-metadata-warning`; earlier gates below describe their original policy.
TD-001 stays open for expected-object classification and remaining scope.

## Conservative terminal mapping — 2026-10-26

Historical policy, superseded by the temporary warning policy above.

Owner approved the narrow partial-to-ERROR fix. `SYNC_METADATA` now emits ERROR
when `MetadataSyncResult.is_partial` is true, with a count-bearing error message;
non-partial results remain SUCCESS. All object/partition counters and published
record counts are preserved. Existing Platform `JobService.applyStatus()` maps
ERROR to FAILED, preserving its failure notification, parent aggregation and
success-only barrier semantics. No enum, schema/migration or generated contract
changes are required; the Python enum retains its compatibility member.

This is deliberately conservative: skipped auxiliary objects can cause a failed
execution even after valid discovery publication. It does not imply publication
was rolled back. Expected auxiliary versus missing/invalid required objects still
need classification; do not infer complete data from a successful object write.

Local evidence under `P14-metadata-status`: 147 Analyzer tests, including shared
wire-fixture equality, healthy SUCCESS, skipped/failed partial outcomes, empty
sync and status-publication failure. `metadata/kafka.py` and `metadata/handler.py`
have 100% line/branch coverage. A Java regression deserializes the same fixture
and exercises the real `applyStatus()` with a mocked repository, checking FAILED,
the recorded error, counters and save. Focused JobService/planner regressions
cover existing status and barrier behavior. This is not PostgreSQL persistence,
broker delivery, deployment or complete JobRun E2E verification.

The [subsequent disposable cross-service scenario](../deployment/004-job-run-admission-rollout.md#disposable-cross-service-integration--2026-10-26)
executes healthy and corrupt-Parquet scans through actual Kafka/MinIO and
PostgreSQL, including FAILED persistence and preservation of previous discovery.
This adds bounded real integration evidence to the mocked contract checks; it
does not cover expected auxiliary-object classification or every partial-result
branch across services.

The status mismatch slice is locally resolved. TD-001 remains open for expected
object classification and remaining integration scope; P3-I5 remains superseded. Historical
messages already ignored by Platform are not replayed or rewritten by this fix.

## Review — 2026-10-07 (historical)

| Field           | Assessment                                                                                    |
| --------------- | --------------------------------------------------------------------------------------------- |
| Type            | correctness                                                                                   |
| Status          | OPEN / source mismatch confirmed                                                              |
| Priority        | P1                                                                                            |
| Static evidence | Analyzer still emits PARTIAL_SUCCESS; Platform enum has no matching terminal state.           |
| Activation      | Partial metadata status leaves executions non-terminal; approve a coordinated status mapping. |

Refs: [apps/analyzer/app/metadata/kafka.py](../../apps/analyzer/app/metadata/kafka.py), [apps/core/src/main/java/com/omni/platform/modules/scheduler/entities/JobExecutionHistory.java](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/entities/JobExecutionHistory.java).

Priority and review status: [technical-debt index](README.md). [Mermaid priority source](priority-order.md). This review adds no runtime verification or completion claim; preserved material below is historical unless reconciled here.

## Summary

P3-I5 automatic metadata reconciliation has source present but is canonically
`superseded`, not `verification_pending`. The scheduler definition, Platform producer,
Analyzer Kafka consumer, and deterministic global metadata reconstruction path exist.
The remaining gaps are expected-object classification, whole-chain status evidence,
manual-trigger configuration documentation, integration evidence, and removal of
claims tied to the superseded per-partition READY-pointer design.

This debt does not block P1-I4 verification. P3-I5 declares no downstream increments
in its `blocks` field, and P1-I4 does not depend on P3-I5. P1-I4 verification may
therefore proceed independently while P3-I5 remains `superseded`.

This classification does not mark P3-I5 complete and does not waive any roadmap
completion gate.

## Current Implementation

- Platform seeds one weekday 20:00 `SYNC_METADATA` definition for Analyzer.
- Platform dispatches the definition through the existing scheduler claim/outbox and
  Kafka producer boundary.
- Analyzer consumes `topic-sync-metadata` and invokes `MetadataSynchronizer`.
- The synchronizer discovers canonical EOD Parquet objects, calculates checksums and
  deterministic `dataVersion` values from persisted bytes, rebuilds the canonical global discovery document. Immutable publication used by other datasets is a separate boundary.
- The same definition can use Phase 7 manual triggering when deployment explicitly
  allow-lists `SYNC_METADATA:ANALYZER`.

## Technical Debt

### Status Contract

Current policy and local evidence are recorded above. The following source
assessment and decision options describe the pre-fix state on October 25.

Source rechecked on 2026-10-25: `MetadataSyncResult.is_partial` is true when
`objects_skipped > 0` or `objects_failed > 0`. Analyzer maps that result to
`PARTIAL_SUCCESS` to distinguish a published discovery document from a wholly
successful scan. However, the actual `MetadataSynchronizer._sync_locked()` raises
when validation failures are counted, before replacing the discovery document;
Analyzer publishes ERROR in that path. A normally returned partial result therefore
comes from skipped objects, rather than successful publication after validation
failure. The mocked worker test with both skipped and failed counts tests the
mapping, not that production synchronizer behavior.

`_scan_dataset()` counts any listed object whose path does not match the dataset's
trusted Parquet path pattern as skipped, without classifying expected auxiliary
objects separately. For example, a non-data file beneath an EOD prefix can coexist
with valid partitions, a successful metadata replacement and a partial result.
Such a skip alone does not prove that required financial data is missing.

The contract mismatch is that Python defines and publishes this terminal label,
but Platform's `JobExecutionHistory.JobStatus` does not define it.
`JobService.applyStatus()` calls `resolveStatus()`, receives null and returns before
updating execution/parent state. Kafka may acknowledge the status while its JobRun
step remains unfinished. Manual offset commits fix input-delivery ownership, not
this semantic rejection. This is source-confirmed behavior; an actual Analyzer ->
Platform persistence/JobRun test is still required.

Required follow-up:

1. Define whether `PARTIAL_SUCCESS` is a first-class terminal Platform status or maps
   to an existing terminal status while retaining partial-result metrics.
2. Apply the decision consistently to execution persistence, status DTOs, polling,
   parent aggregation, notifications, and sanitization.
3. Add a Platform regression test using the actual Analyzer status payload.
4. Classify expected auxiliary objects versus invalid/missing required objects
   before deciding which skipped objects warrant a degraded result. Retain
   `objectsSkipped`/`objectsFailed` metrics. A blanket partial-to-SUCCESS mapping
   would hide incomplete scans; adding an enum alone would not define parent,
   dependency barrier, notification or API behavior. This review changes no status
   contract or persisted status values.

### Phase 7 Manual Trigger Configuration

Manual triggering is secure by default and disabled when the allow-list is empty.
Deployment examples do not currently demonstrate the stable allow-list key for this
job.

Required follow-up:

1. Document `SYNC_METADATA:ANALYZER` as the explicit opt-in key.
2. Add catalog, authorization, allow-list, trigger, producer, and terminal-status
   integration coverage for this exact definition.
3. Keep scheduler execution independent from manual-trigger configuration.

### Global Metadata Publication Safety

The current implementation rebuilds and replaces one global metadata discovery
document. The earlier per-partition READY-pointer concern no longer describes this
code path. Publication still requires explicit failure-preservation evidence so a
read, validation, or replacement failure cannot publish a partial discovery document.

Required follow-up:

1. Test read, validation, and replacement failures against the current global metadata
   publication boundary.
2. Prove a failed synchronization does not replace the previous complete discovery
   document with partial state.
3. Keep READY-last requirements scoped to datasets that use immutable publication;
   do not reintroduce the obsolete per-partition reconciliation design.

### Expected Object Classification

Noncanonical objects under `eod/`, including expected internal version/backfill
artifacts, currently count as skipped. Any skipped object makes the complete run
`PARTIAL_SUCCESS`, which can make healthy recurring runs permanently partial.

Required follow-up:

1. Exclude known internal prefixes from outcome severity and metrics, or report them
   separately as expected exclusions.
2. Reserve skipped/failed outcome severity for malformed canonical candidates and
   genuine reconciliation failures.
3. Add tests covering internal prefixes, empty canonical objects, corrupt canonical
   objects, and mixed valid/error runs.

### Manual Trigger Scope Reconciliation

The earlier Dataset Explorer proposal remains superseded, but current Platform source
supports typed `SYNC_METADATA` targets for all datasets, one dataset, or one exact
partition through the existing operator API. Therefore the old claim that the job is
parameterless is also stale.

Required follow-up:

1. Keep Dataset Explorer UI scope archived as superseded design history.
2. Document the implemented Jobs/API trigger and its supported typed targets.
3. Do not add a Dataset Explorer action or broaden target semantics without a new
   approved increment.

## Contract Impact

- Kafka/service-to-service protobuf: no planned schema change; follow-up aligns status
  semantics across the existing JSON command/status boundary.
- Object-storage JSON manifest: unchanged; immutable-before-READY publication and
  deterministic identity remain required.
- Storage path/dataset ownership: unchanged; physical paths stay internal and EOD
  remains the only automatic reconstruction target.
- Public Java/Python APIs: only status handling and narrowed error classification may
  change.
- Configuration/environment: document the optional
  `APP_SCHEDULER_MANUAL_TRIGGER_ALLOW_LIST=SYNC_METADATA:ANALYZER` deployment value.

## Non-Blocking Decision

This technical debt is isolated from P1-I4 verification:

- P3-I5 depends on P3-I1 and P7-I2 and declares `blocks: []`.
- P1-I4 depends on P1-I2 and blocks P2-I2, P4-I1, and P8-I1.
- The increments share runtime modules but no P1-I4 verification criterion requires
  P3-I5 completion.
- P1-I4 verification must use its existing committed implementation and must not
  absorb P3-I5 code or documentation changes into its completion evidence.

If P1-I4 verification reveals a shared status-contract regression caused by P3-I5,
record that result explicitly rather than expanding P1-I4 scope.

## Current Source Assessment

- **Current:** Analyzer can emit `PARTIAL_SUCCESS`, while Platform has no matching
  persisted terminal status.
- **Current:** noncanonical objects are counted as skipped and can affect run outcome.
- **Current:** deployment examples do not show the exact
  `SYNC_METADATA:ANALYZER` allow-list value.
- **Stale:** P3-I5 is no longer `verification_pending`; the canonical registry marks it
  `superseded`.
- **Stale:** per-partition READY-pointer reconciliation and parameterless-only trigger
  claims do not match the current global metadata and typed-target implementation.
- **Evidence-dependent:** integration, failure-preservation, and exact-head CI results
  are not established by static source presence.

## Recommended Actions

1. Decide and document how `PARTIAL_SUCCESS` maps to Platform terminal state before
   reactivating this work.
2. Update reconciliation severity so expected internal objects are classified
   separately from malformed canonical candidates.
3. Add the exact manual-trigger allow-list example and typed-target documentation.
4. Replace obsolete per-partition READY tests with global-document atomicity and
   previous-document preservation tests.
5. Keep the record superseded until a canonical increment is explicitly reactivated;
   source presence alone is not completion evidence.

## Removal Criteria

This debt is resolved only when:

1. Platform handles Analyzer partial outcomes as a documented terminal result.
2. End-to-end Platform/Analyzer tests cover success, partial, and error status flows.
3. Global metadata publication failures preserve the previous complete discovery
   document.
4. Expected internal objects do not degrade otherwise healthy recurring runs.
5. The Phase 7 allow-list key, typed targets, and opt-in deployment behavior are
   documented and tested.
6. Superseded Dataset Explorer and per-partition reconciliation claims are removed
   from active requirements.
7. Targeted and affected Nx checks, formatting, builds, and exact-head CI pass.
8. A new canonical increment is approved before this superseded debt is scheduled or
   represented as completed.

## Verification Status

Static source and roadmap inspection identified this debt. Existing historical local
Python checks are recorded in the root [`ReleaseNotes.md`](../../ReleaseNotes.md), while
current status is owned by the canonical increment registry. Platform integration,
workspace formatting, final affected checks, and exact-head CI remain required where
an approved reactivation plan declares them.
