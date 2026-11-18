# TD-020 - Continuous Rollout, Recovery and Periodic Reconciliation

## Status and owner decision

DEFERRED / owner-accepted on 2026-11-18. Priority: P3 conditional on persistent
or unattended operation; P1 for a demonstrated data-safety defect, which must be
handled immediately in a bounded slice rather than waiting for this debt.

The owner currently starts and stops Omni by session rather than operating it
24/7. Fleet-wide rollout/recovery qualification and activation of reconciliation
every 12 hours are not blocking requirements for current session-based P14-I10
acceptance. This is an explicit scope amendment, not a claim those checks passed.
No process, schedule, catch-up scan or deployment is activated by this record.

## Two deferred slices

1. **Continuous rollout and recovery:** qualify the entire deployed service and
   dataset inventory, coordinated configuration/credentials, restart and partial
   failure recovery, backup restoration, retention and referenced object versions.
   This is broader than the retained three-partition rehearsal already executed.
2. **Periodic reconciliation:** activate the existing SYNC_METADATA 12-hour
   schedule, establish exclusive/safely quiesced ownership of affected outputs,
   define missed-schedule behavior while services are offline and record actual
   scheduled-run evidence. A single read-only 59/59 comparison is not this evidence.

## Safety retained in current I10

- Each backfill still requires matched participating services, authoritative
  PostgreSQL work/attempt/version proofs, exact input bytes/date coverage and
  READY-last. API/proof/bytes failure remains fail-closed without JSON fallback.
- Existing files touched by strict VERSIONED reads/writes need their verified
  physical baseline or an already accepted version. Review the
  [56-partition checklist](../reference/020-i10-postgres-only-cutover.md#remaining-56-partitions--bounded-work-checklist).
  Deferring fleet qualification does not waive output ownership or stale-writer safety.
- Drain/stop owned workers cleanly and preserve backups, registrations, heads,
  history and all referenced versions. Per-scope accepted-byte recovery evidence
  remains required where that scope changes; dangerous restart/status/offset
  repairs are not authorized by running in sessions.
- The existing 12-hour override stays disabled. No automatic startup catch-up,
  replacement timer or new operator-trigger API is introduced. If a session needs
  diagnosis, use bounded read-only checks; any corrective operation follows the
  existing authorized maintenance path and its guards.

## Activation triggers and acceptance

Reopen the appropriate slice before approved persistent/unattended deployment,
additional independent writers/replicas, a requirement for automatic drift repair,
or observed failure that needs it. Select one bounded slice with the owner.

Continuous rollout acceptance requires exact deployed source/config inventory,
affected-partition proof/write-safety validation, matched reader/writer failure
and restart evidence, and recovery preserving post-cutover accepted results.
Periodic acceptance requires an approved cadence/timezone/missed-run policy,
quiescence/fencing evidence, successful real scheduled runs, failure/overlap
behavior, load bounds and a disable/recovery procedure. Approve exact verification
commands and record actual evidence before marking either slice complete.

## Related debt and evidence boundaries

- [TD-016](016-parquet-readiness-reconciliation.md) owns future range-aware
  historical readiness reconstruction/read optimization, not this schedule.
- [TD-018](018-execution-watchdog-observation.md) owns automatic expiry observation
  and completion-delivery gaps. Durable worker recovery and watchdog activation
  remain separately deferred; this record does not implement either.
- [TD-009](009-python-kafka-worker-throughput-and-offset-safety.md) and
  [TD-010](010-kafka-poison-record-and-dead-letter-policy.md) retain offset and
  poison-record safety scope. [TD-017](017-manual-request-rejection-audit.md)
  retains the operator-trigger deferral.
- [Current retained evidence](../reference/020-i10-postgres-only-cutover.md#november-18-retained-signals-and-readerrepair-checkpoint)
  includes the finite Signals/readers/repair gate PASS 3/3, 59/59 read-only parity
  and three pinned partitions. These dated results are not new runs for this debt.

Documentation-only decision. No production code, database, object, Kafka offset,
runtime flag, verification result or owner attestation changed.
