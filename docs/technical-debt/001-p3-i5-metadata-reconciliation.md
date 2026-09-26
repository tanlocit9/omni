# P3-I5 Metadata Reconciliation Technical Debt

## Summary

P3-I5 automatic metadata reconciliation has source present but is canonically
`superseded`, not `verification_pending`. The scheduler definition, Platform producer,
Analyzer Kafka consumer, and deterministic global metadata reconstruction path exist.
The remaining gaps are status-contract correctness, expected-object classification,
manual-trigger configuration documentation, integration evidence, and removal of
claims tied to the superseded per-partition READY-pointer design.

This debt does not block P1-I4 verification. P3-I5 declares no downstream increments
in its `blocks` field, and P1-I4 does not depend on P3-I5. P1-I4 verification may
therefore proceed independently while P3-I5 remains `verification_pending`.

This classification does not mark P3-I5 complete and does not waive any roadmap
completion gate.

## Current Implementation

- Platform seeds one weekday 20:00 `SYNC_METADATA` definition for Analyzer.
- Platform dispatches the definition through the existing scheduler claim/outbox and
  Kafka producer boundary.
- Analyzer consumes `topic-sync-metadata` and invokes `EodMetadataSynchronizer`.
- The synchronizer discovers canonical EOD Parquet objects, calculates checksums and
  deterministic `dataVersion` values from persisted bytes, publishes immutable
  manifests before READY pointers, and publishes the catalog last.
- The same definition can use Phase 7 manual triggering when deployment explicitly
  allow-lists `SYNC_METADATA:ANALYZER`.

## Technical Debt

### Status Contract

Analyzer emits `PARTIAL_SUCCESS` when reconciliation publishes usable manifests but
also encounters skipped or failed objects. Platform's persisted execution status enum
does not currently accept `PARTIAL_SUCCESS`, so Platform can ignore that terminal
message and leave the execution non-terminal.

Required follow-up:

1. Define whether `PARTIAL_SUCCESS` is a first-class terminal Platform status or maps
   to an existing terminal status while retaining partial-result metrics.
2. Apply the decision consistently to execution persistence, status DTOs, polling,
   parent aggregation, notifications, and sanitization.
3. Add a Platform regression test using the actual Analyzer status payload.

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

Static source and roadmap inspection identified this debt. Existing local Python
checks are recorded in the Phase 3 roadmap and execution log. Platform integration,
workspace formatting, final affected checks, and exact-head CI remain required.
