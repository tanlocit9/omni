# I10 delivery checkpoints - 2026-11-07

November 8 follow-up delivery adds separate source checkpoints for closed-owner
reconciliation, rerun/force policy, policy E2E fixtures, graph indexing, root
target counters and compact request polling, then documentation/review handoff.
The combined selected Java regression now passes 100 cases; older policy E2E
evidence and its limits remain dated. See [the current review](015-i10-review-20261108.md)
and [polling contract](014-atomic-request-polling.md). These do not alter I10 status
or claim each intermediate commit was independently tested.

Subsequent source checkpoint: [closed-owner settlement and attempt/output
diagnostics](012-i10-closed-owner-reconciliation.md) and [rerun/force policy](013-backfill-attempt-and-data-policy.md)
now have focused local PostgreSQL/worker E2E evidence. Full I10 coverage/cutover
remains open; the delivery history below remains dated to its original task.

The owner requested bounded commits without rerunning verification for each
checkpoint. These are delivery checkpoints under P14-I10, not new roadmap
increments. P14-I10 remains in_progress; committing is not acceptance or cutover.

The owner reports that some jobs are currently blocked after service startup.
That observation is unresolved and has not been queried or diagnosed in this
delivery task. No DB/Kafka mutation, service restart, retry or flag change is
included. Do not interpret the commit series as remediation of those blocked jobs.

The existing runtime checkpoint is `2bbfe38`. Its scoped local-test and isolation
limits remain in [the runtime handoff](010-runtime-fixes-20261107.md).

## Checkpoint scopes

### Backlog tooling

Add the shared read-only backlog skill and its tool/agent guidance. No job cleanup or active-definition changes are included. No application, persistence, Kafka contract, data-lake, deployment, Query or Console behavior changes.

No test, lint, build, format or executable acceptance gate was run for this
checkpoint. Existing dated evidence is historical and does not verify this
isolated commit.

### Scheduler candidate budget

Use one multiplier of three in planner, dispatcher and fallback paths. With batch size ten the candidate limit is thirty; this is not a worker concurrency limit. Only Platform dispatch selection and its regression expectations change. No schema, worker, message, storage, Query, Console or deployment change.

No test, lint, build, format or executable acceptance gate was run for this
checkpoint. Existing dated evidence is historical and does not verify this
isolated commit.

### I10 migration packaging

Split the consolidated readiness schema into registration, completion/watchdog, READY projection, baselines and scoped-work stages. Final schema semantics stay unchanged; function declarations remain replaceable. Migration expectations at this checkpoint stop at V24; V25 belongs to the later backfill checkpoint. Platform persistence/install order is affected; Python/Query readers keep the final table contract. This does not run Flyway or authorize a retained-environment migration.

No test, lint, build, format or executable acceptance gate was run for this
checkpoint. Existing dated evidence is historical and does not verify this
isolated commit.

### I10 dispatch and frozen-input guards

Require exact dated EOD/symbol-feature/sector-feature inputs; reject malformed declarations and missing frozen upstream enrollment. Validate frozen signal history once before evaluation and preserve other rows/models. Discover the two existing feature datasets without inventing historical lineage. Platform, Analyzer and shared Python discovery/read code change; Ingestor/Query retain their existing protocol, Console and storage paths are unchanged. The guard reference records historical executed tests and the historical contracts failure, not a new verification result.

No test, lint, build, format or executable acceptance gate was run for this
checkpoint. Existing dated evidence is historical and does not verify this
isolated commit.

### I10 bounded Indicators backfill

Add the default-off Indicators-only sector/date API and V25 BACKFILL mode. Expand current Symbols then dated EOD and Indicators; enroll and promote this chain without globally activating realtime I10. Force creates fresh attempts and checks actual Parquet EOD-day coverage before reusing data. Platform API/enrollment, Ingestor EOD policy and shared Python enrolled reads change; Analyzer/Query fixtures exercise the chain. Existing logical Kafka fields and storage paths remain; Console has no UI change. Matched workers, authenticated private readers and exclusive drained legacy writers remain operational prerequisites.

No test, lint, build, format or executable acceptance gate was run for this
checkpoint. Existing dated evidence is historical and does not verify this
isolated commit.

## Delivery traceability

| Checkpoint | Commit | Scope |
| --- | --- | --- |
| Earlier runtime repair | `2bbfe38` | Lifecycle, settlement, date merge and weekend validation |
| Backlog tooling | `e27f816` | docs(ops): add read-only job backlog workflow |
| Scheduler candidate budget | `80a0466` | fix(scheduler): limit dispatch candidates to three times batch size |
| I10 migration packaging | `864f9f7` | refactor(db): split I10 readiness schema into V19-V23 stages |
| I10 dispatch and frozen-input guards | `2144c30` | fix(p14): enforce dated dependencies and frozen input reads |
| I10 bounded Indicators backfill | `07ae4d1` | feat(p14): connect bounded Indicators backfill to scoped READY |

The final documentation commit links this handoff from the index, roadmap,
increment registry, Plan 030 and release notes. No new roadmap increment or
verified_commit/owner-attestation field is created. The final schema includes
V25; the migration-only checkpoint intentionally retained the V24 expectations
until that later addition. No migration was applied during delivery.

## Verification boundary

The owner explicitly waived rerunning verification for this delivery. No tests,
lint, build, format, CI or executable documentation checker were run, and no
check_result manifest was weakened or given fabricated passing results.
Pre-commit hooks performed code-graph analysis only. Graph output includes the
combined working tree, unresolved relationships and truncated impact results;
it is not per-checkpoint coverage or proof of isolation.

Existing combined-tree evidence remains local and dated: lifecycle Platform
744 passed/5 skipped; latest py-common 420 passed/5 skipped, Analyzer 268 passed,
Ingestor 100 passed, and the forced paired Kafka integration 2/2 passed. The
earlier runtime handoff explains that its isolated commit was not retested.
No checkpoint in this delivery has newly executed independent verification.

Earlier contracts-generation failures in the November 4 handoffs remain
historical. The owner subsequently reported the toolchain repaired; this delivery
does not rerun generation or claim a current compiler failure or contracts pass.

## Documentation and cross-service reconciliation

- Platform: registration/enrollment guards, request lifecycle, backfill API,
  candidate budget and persistence checkpoints are reflected in Plan 030,
  database, flow, deployment and service guidance.
- Analyzer/Ingestor/shared Python: frozen reads, exact date coverage, feature
  discovery, worker receipt, force-EOD policy and matched enrolled readers are
  reflected in Kafka/data-lake, flow and service guidance.
- Query: integration fixtures cover scoped reading; this series does not
  establish global reader cutover. Console has no source/UI change and retains
  existing proxy/read boundaries. Contracts are not changed by this series.
- Architecture and ADR-008 retain Platform readiness ownership and coordinated
  cutover; configuration remains default-off for the scoped backfill feature.
  No storage paths, writer ownership or physical paths in Kafka are introduced.
- The canonical registry still has one in_progress P14-I10 after completed I9.
  Plan 028 remains unscheduled/on hold; TD-017 keeps generic manual routes
  retired. Source commits do not activate P12 or deferred recovery/tracing.
- Docs README/INDEX, registry, roadmap, release notes, ownership and service
  references link the current handoffs. Existing agent rules already cover
  ownership, exact-date and verification gates; no AGENTS/CLAUDE/Roo change is
  required. The backlog skill changes only read-only operational guidance.

Static review of this changed scope found the new reference numbers, direct
paths, unchanged increment status and evidence wording consistent. This is a
bounded documentation review, not a full-repository audit or acceptance gate.

## Remaining work and return-to-session handoff

1. Diagnose the owner's currently blocked jobs using a fresh read-only snapshot:
   distinguish dependency WAITING, outbox BLOCKED, failed upstream, incomplete
   registration and closed request ownership. Capture request/execution identity
   and the persisted reason before selecting a recovery action. None was queried
   in this delivery; no cause or recovery success is claimed.
2. Retest the final source and reconcile attributable coverage with every I10
   acceptance criterion, including contracts generation, error/retry/concurrency,
   READY-last and reader compatibility. Existing local counts alone are not a
   complete feature/source/test matrix for acceptance.
3. Verify matched retained-environment deployment, writer drain, historical
   backfill/shadow/parity and coordinated reader cutover before retiring legacy
   authority. File existence or accepted registration alone is not READY.
4. Review remaining producer/reader scope. Feature discovery additions do not
   establish feature-output enrollment or a global PostgreSQL reader cutover.

Business-job FAILED attempts still require an explicit new request key rather
than automatic retry. Do not rewrite terminal histories or reset Kafka merely
because a job is blocked. Additional durable worker recovery, tracing and P12
remain deferred. The earlier DevTools startup failure remains unproven.

No service restart, live API submission, database/Kafka mutation, push or pull
request was performed by this checkpoint delivery.

### Canonical documentation handoff

Synchronize release notes, registry, roadmap, architecture/ADR, data, flow, deployment and service guidance. Preserve dated checks and in_progress status; record owner-reported blocked jobs without claiming diagnosis or recovery.

No test, lint, build, format or executable acceptance gate was run for this
checkpoint. Existing dated evidence is historical and does not verify this
isolated commit.
