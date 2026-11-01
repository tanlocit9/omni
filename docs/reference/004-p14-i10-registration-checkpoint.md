# P14-I10 registration checkpoint

## Current scope and status

2026-11-01: I10 is `in_progress`, not completed or verified. This checkpoint is
the contract and shadow-inbox foundation of I10.a, not the whole of I10.a.
Canonical scope/acceptance remains [Plan 030](../plans/030-static-graph-dispatch-planner.md);
the [registry](../plans/roadmap/implementation-increments.md) owns scheduling.
Work started from `6496eae` with a clean worktree on `docs/p14-i10-i11-sync`.
No additional commit, push, migration execution, service start or flag activation
is authorized or performed by this implementation handoff.

## Source present

- [Canonical schema](../../libs/contracts/proto/omni/contracts/job/v1/output_registration.proto),
  [shared JSON fixture](../../libs/contracts/fixtures/output-registration-v1.json),
  [Python adapter/publisher](../../libs/py-common/py_common/messaging/output_registration.py)
  and [Java adapter](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/metadata/OutputRegistrationEvent.java).
- [V19](../../database/migrations/V19__output_registration_shadow.sql) adds immutable
  event/member shadow evidence, unique event/registration/execution identities,
  attempt/execution foreign keys and work lookup index. No READY pointer table,
  backfill or completion schema is supplied by this migration.
- [Store](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/metadata/OutputRegistrationStore.java)
  validates the complete envelope, checks persisted attempt/execution/work aliases,
  compares immutable JSONB payloads and commits an event and all members atomically.
  Conflicts and unknown attempts throw, rolling back the record. Late records can
  be collected as shadow evidence but cannot reopen work or change output readiness.
- [Consumer](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/metadata/OutputRegistrationConsumer.java)
  and [Kafka configuration](../../apps/core/src/main/java/com/omni/platform/modules/scheduler/metadata/OutputRegistrationKafkaConfig.java)
  are conditional on `app.output-registration.shadow-enabled=true`, absent/false
  by default. Kafka auto-commit is disabled; record ACK follows the transactional
  service return. All exceptions retry without a finite recover/discard/DLT path.
  A poison partition remains blocked and needs operator intervention. Independent
  partition progress and real rebalance/offset behavior still need Kafka evidence.
- The shared publisher exposes `publish_before_success`: verify one matching
  execution/status, await metadata ACK, then publish SUCCESS. Existing worker
  handlers do not invoke it yet. No consumer or producer is activated here.

## Frozen shadow wire boundary

Topic: `topic-register-output-metadata-v1`.
Shadow consumer group: `platform-output-registration-shadow-v1`.
These are constants, not newly provisioned infrastructure or active shared config.
The current JSON wire is independent of protobuf's standard JSON mapping; generated
contracts must be regenerated through the existing contracts target, never hand-edited.

The UTF-8 envelope is at most 262144 bytes, schemaVersion=1, a UUID eventId and
1..32 registrations. Each item has immutable UUID registrationId/executionId/
atomicAttemptId, exact atomicWorkId and the original jobType/workType/workKey/
businessDate/parameters tuple. The work hash is recomputed by existing I8 identity
helpers. No tracing or request ID enters that hash. A single-work ordering key is
atomicAttemptId; a multi-work record uses eventId. No cross-key ordering guarantee
is claimed. Future READY-pointer updates require separate output-level fencing.

Items declare up to 32 required logical outputs and the matching actual outputs.
Each output includes dataset/partition, dataVersion, schemaVersion/schemaHash,
columns (1..256), positive rowCount/totalBytes, exact sorted unique coverageDates
(1..4096), and logical inputs (0..128) with exact dataVersion. Valid-empty requires
an explicit nonempty reason and empty required/actual outputs; it grants no coverage.
No output bytes or physical storage path field is part of this contract.

Retries must reuse event/member IDs and payloads. Reusing event/member/execution
identity with changed content fails. A caller must durably retain the event before
using this seam in a worker; random IDs regenerated on command replay are not a
recovery protocol. Shadow acceptance is not proof of producer output ownership,
validated actual Parquet bytes, approved lineage, or authoritative required outputs.
Those checks and per-JobType declarations are still required for promotion.

## Impact and source-to-test matrix

Graph was initially stale on feat/P14-WIP, then refreshed to current HEAD before
impact analysis. The initial three-file impact returned 116 nodes, 14 additional
files, truncation and 167 unresolved call sites. These counts establish navigation,
not complete cross-service coverage. Post-edit change analysis must retain those
limitations; the manual boundary reconciliation below supplies missing ownership.
The first post-edit detect_changes call listed 13 files but reported zero changed
symbols; incremental refresh indexed only one tracked file. New untracked source
is not proven indexed by that output. Do not read its zero risk/gap count as safety
or coverage evidence; manual review remains necessary.

| Source/criterion | Test source | Execution/coverage |
| --- | --- | --- |
| Paired JSON identity, exact dates, invalid/partial/duplicate fields, valid-empty | `libs/py-common/tests/messaging/test_output_registration.py`; `OutputRegistrationEventTest` | NOT RUN; no numeric coverage |
| Registration ACK before status; metadata ACK failure prevents SUCCESS | Python publisher tests | NOT RUN |
| Migration/inbox replay, conflicting immutable ID, multi-member rollback, no execution mutation | `GraphWorkRequestsPostgresTest.outputRegistrationShadowIsAtomicIdempotentAndCannotChangeExecutionReadiness` | NOT RUN; real PostgreSQL required |
| Kafka offset/restart/rebalance, poisoned partition and independent progress | Required new real-Kafka fixture | MISSING |
| Existing workers actually produce validated declarations and durable retry identities | Ingestor/Analyzer handler and integration tests | MISSING; handlers not connected |
| Durable status/event correlation, output ownership, stale version fencing | Required Platform integration tests | MISSING; implementation pending |

Platform owns the new adapter/store/listener; existing JobService/DependencyRegistry
are unchanged. Database receives only forward additive shadow tables. Shared
contracts and py-common change; workers gain a reusable seam but no changed handler
behavior. Query Service, Console, storage builders and readers retain manifest
authority and existing diagnostics. Shared topic provisioning/configuration and
deployment remain unchanged; operators must provision/review shadow consumption
before enabling anything. P12 writer, generated output and tracing are untouched.

## Remaining I10 work and safety gates

1. Finish I10.a: strict cross-language compatibility (including numeric/null/unknown
   fields), output owner/lineage/declaration validation, per-worker validated-byte
   declarations, durable event retry identity, per-partition replay fixture and
   persisted completion-pending correlation in both status/event orders. Do not
   enable shadow tables as READY or treat the publisher seam as handler integration.
2. Implement I10.b provider/API and reader adapters, safely fenced backfill/shadow
   comparisons and 12-hour reconciliation. No direct Python Platform DB access.
   Mutable object paths still lack a proven stale-writer exclusion/version protocol.
   Keep affected manifest routes and the cutover gate open until that is resolved;
   this checkpoint selects no new storage architecture or automatic stale fallback.
3. Implement I10.c bounded two-minute watchdog with the thirty-minute published
   deadline, late-event fencing, normal aggregation, shared ownership diagnostics
   and no unsafe automatic rerun. Existing over-30-minute diagnostics are read-only.
4. Complete attributable coverage, all acceptance criteria, documentation and rollout
   evidence. No tracing SDK/query snapshot/P12 activation is included in I10.

## Verification and documentation disposition

No build/test/lint/format/link checker, database command or deployment ran. Proposed
owner-run commands from the workspace root, after reviewing these changes:

```text
nx run contracts:test --skip-nx-cache
nx run py-common:coverage --skip-nx-cache
nx run platform:coverage --skip-nx-cache
```

These commands are **not approved or run** by this handoff. They do not close the
missing real-Kafka and worker/completion matrix above. Record every required result
through `tools/check_result.py` for P14-I10 and confirm when the compact conclusion
is ready. Generation is pending through contracts:test/generate; no generated code
was hand-edited. Migration is source only and must be exercised on disposable and
retained-data upgrade fixtures before runtime rollout.

Plan/registry/roadmap/release notes, Kafka/database/architecture/data-lake/execution
flow/rollout, field inventory, ADR-008 and affected service guides link this checkpoint.
Other analytical flow docs, Query/Console guides, Plan 024/027 and agent guidance
retain current behavior: no reader, UI, tracing, batching or READY authority change.
The numbered index includes both source-reference reports. No new schedule or
acceptance criterion replaces Plan 030.

Static audit: mandatory Plan 030 sections and I9 -> I10 dependency retained;
I10.a/b/c remain internal checkpoints, no I11 identity invented, no completion or
deployment claim added. Direct added links and field names are reviewed statically.
Runtime compatibility, numeric coverage and production readiness remain unverified.
