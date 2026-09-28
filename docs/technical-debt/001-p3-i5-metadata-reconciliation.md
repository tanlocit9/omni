# P3-I5 Metadata Reconciliation Technical Debt

## Summary

P3-I5 automatic EOD metadata reconciliation is implemented but remains
`verification_pending`. The scheduler definition, Platform producer, Analyzer Kafka
consumer, and deterministic READY-last metadata reconstruction path exist. The
remaining gaps are bounded correctness, integration-test, configuration-documentation,
and plan-cleanup work.

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

### READY Read Safety

The synchronizer currently treats every `ManifestError` while reading an existing
READY pointer as if the pointer were absent. A corrupt manifest or transient storage
failure can therefore be masked before replacement is attempted.

Required follow-up:

1. Treat only `ManifestNotFoundError` as an absent READY pointer.
2. Preserve and report corrupt, invalid, or unreadable READY state without replacing
   that partition.
3. Add tests for corrupt READY content, storage read failure, and previous-pointer
   preservation.

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

### Superseded Console Proposal

The Phase 3 plan still contains the earlier `REBUILD_DATASET_METADATA` exact-partition
Dataset Explorer proposal, while the implemented P3-I5 scope is the parameterless
bulk `SYNC_METADATA` definition exposed through the existing Jobs tab.

Required follow-up:

1. Move the exact-partition proposal to a separately identified future increment or
   archive it as superseded design history.
2. Update Omni Console and roadmap documentation to describe the implemented Jobs-tab
   trigger accurately.
3. Do not add runtime parameters or a Dataset Explorer action under P3-I5 without a
   new approved increment.

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

## Removal Criteria

This debt is resolved only when:

1. Platform handles Analyzer partial outcomes as a documented terminal result.
2. End-to-end Platform/Analyzer tests cover success, partial, and error status flows.
3. READY read failures cannot silently replace corrupt or unreadable metadata.
4. Expected internal objects do not degrade otherwise healthy recurring runs.
5. The Phase 7 allow-list key and opt-in deployment behavior are documented and tested.
6. Superseded Dataset Explorer scope is removed from active P3-I5 requirements.
7. Targeted and affected Nx checks, formatting, builds, and exact-head CI pass.
8. Roadmap verification evidence is recorded before P3-I5 is marked `completed`.

## Verification Status

Static source and roadmap inspection identified this debt. Existing local Python
checks are recorded in the Phase 3 roadmap and execution log. Platform integration,
workspace formatting, final affected checks, and exact-head CI remain required.
