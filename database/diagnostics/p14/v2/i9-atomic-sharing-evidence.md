# I9 shared-attempt cross-service scenario

Owner: [Plan 030](../../../../docs/plans/030-static-graph-dispatch-planner.md).
Source: `JobRunAtomicSharingIntegrationTest`, opt-in `OMNI_P14_E2E=1`.
The original many-step `JobRunCrossServiceIntegrationTest` remains a separate V1/I8
regression. Its PASS does not prove I9 atomic request membership or attempt sharing.

## Scenario and assertions

The fixture creates independent PostgreSQL 16, Kafka and MinIO containers and uses
the real Platform, Ingestor, Analyzer and Query Service adapters. Only external
market data is supplied by the existing deterministic provider. Graph expansion is
explicitly enabled; definition consolidation is disabled. Legacy fixtures explicitly
disable graph expansion, independent of ambient application flags.

1. Retain one active preset per required type, one exchange and one symbol in the
   disposable database. Create three differently keyed indicator graph requests
   before dispatch. Each request binds the same three immutable attempt IDs: symbol
   catalog, stock EOD and indicator. Assert three attempts, three commands, nine
   request memberships, three one-step JobRuns and one child/message per run.
2. Assert stock admission is false while its actual catalog ancestor is unfinished.
   Cancel the third request through the owner-scoped service; keep the other two
   owners active. No worker is killed and no execution status is fabricated.
3. Dispatch actual workers. After stock SUCCESS, the atomic barrier is satisfied
   but indicator publication still waits for global metadata. Run actual metadata
   sync, then pause the single admitted indicator command inside the real handler.
4. Assert accepted EOD approval and exact request-key replay with no new attempt or
   command. Release the worker. Both active requesters must aggregate to SUCCESS;
   the cancelled requester remains CANCELLED/BLOCKED. All atomic runs close SUCCESS.
5. Replay the original bound indicator payload through the actual publisher port.
   Require a second actual SUCCESS ACK, one dated output row with the same approved
   input, and unchanged attempt/history/outbox counts. Broker log ends prove one
   catalog command, one stock command and two indicator deliveries including replay;
   the indicator consumer must commit offset 2.
6. Refresh discovery through another real metadata request and query through the
   Query Service fixture, requiring one indicator row with its normal lineage checks.

## Verification

Approved combined check, recorded under `P14-I9-atomic-sharing-20261031`:

```bash
OMNI_P14_E2E=1 nx run platform:test --skip-nx-cache --args="--tests *JobRunAtomicSharingIntegrationTest --tests *JobRunCrossServiceIntegrationTest"
```

The standalone target `nx run platform:test-atomic-sharing` sets the opt-in flag for
future runs. The first invocation lost its flag through shell interpolation and
skipped both classes; that result is not behavioral evidence. The corrected combined
run must report two executed tests with zero skips before a passing gate is used.
Final local gate **PASS P14-I9-atomic-sharing-20261031, required=1/pass=1**.
Read through `python tools/check_result.py conclusion --increment P14-I9-atomic-sharing-20261031`.
The corrected run executed both classes: **2 tests, zero failures/errors/skips**.
Seven source bindings were unchanged across that run; JUnit, JaCoCo, workflow
result and source hashes are saved in the ignored directory
`.agent/check-results/P14-I9-atomic-sharing-20261031/`. The initial skipped reports
are preserved separately and are not included in the passing test counts.

Observed result: three shared initial attempts, three owners per attempt, one step
per atomic run, first/second request SUCCESS, third request CANCELLED, actual worker
and replay SUCCESS ACKs, and one Query Service output row.

| Criterion                              | Exercised production boundary                                                           | Executed scenario evidence                                                                                                      |
| -------------------------------------- | --------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------- |
| Materialization and shared acquisition | GraphWorkRequestService.prepare/acquire; V17 attempt/member guards                      | Three requests reuse the same three attempts, nine memberships and three one-step runs; request replay adds no command/attempt. |
| Exact upstream and manifest barriers   | atomic_work_admissible; SchedulerOutboxService.claimEligible; existing dependency guard | Stock claim rejected before catalog completion; indicator remains unpublished until actual metadata sync.                       |
| Ownership and completion               | GraphWorkRequestService.cancel/reconcile; JobService.applyStatus                        | Wrong owner cannot cancel; cancelled owner stays terminal while both other requesters complete through real statuses.           |
| Bound command replay                   | SchedulerOutboxService.boundPayload; actual Analyzer handler/publisher                  | Two actual SUCCESS ACKs and committed offset 2; unchanged histories/attempts/outbox, one dated row, preserved approved input.   |
| Storage/read lineage and compatibility | Metadata worker and Query Service fixture; original many-step worker workflow           | Query returns one row with normal lineage checks; the graph-disabled regression also passes.                                    |

JaCoCo for GraphWorkRequestService in this two-story run: **86.0% line (135/157)**,
**60.5% branch (52/86)**. This is attributable local execution evidence, below the
full-increment branch threshold; error/explicit-retry/inspection and other branches
still require their focused suites. No broader critical-component coverage pass is
claimed. Build/lint/format, CI, providers and retained deployment were not run.

Those bindings and percentages describe the initial two-story capture. The later
[review-repair gate](i9-review-fixes-evidence.md) reruns this workflow after source
repairs and fixture formatting; use that gate's bindings for the subsequent source.

## Impact and limits

Platform: test-only route selection and real graph preparation, acquisition, barrier,
request reconciliation, cancellation and status consumers. Analyzer/Ingestor: reuse
their existing worker/provider/clock/ACK fixtures, with no application changes.
Query Service: reuse the existing read-only query/lineage fixture. Console: no API or
UI change. Contracts/libraries/storage: existing atomic envelope, manifest readiness,
bound inputs and canonical paths remain authoritative; no new schema or dataset.
Persistence: all destructive setup occurs in fresh containers using existing
migrations. Configuration: one new opt-in Nx test target; live flags are untouched.
Operations: no live data, broker offsets, provider call, service restart or rollout.

This scenario proves matching in-flight work sharing for equivalent requests;
different target types, concurrent acquisition, different-output write exclusion,
lease expiry, worker crash recovery and consolidation rollback are separate cases.
The subsequent [review repairs](i9-review-fixes-evidence.md) address preset-parameter
mapping and UUID lock order with their own regressions. This story does not certify
retained V16 upgrades or close I8/I9/I10. No numeric coverage
threshold, full increment gate, CI or production acceptance follows from one story.

Documentation disposition: this report, Plan 030, root release notes and Platform
test instructions describe the new scenario. Registry/roadmap statuses remain
unchanged. Architecture, ADR, flow, Kafka/data-lake/database/deployment ownership,
service contracts and agent policy need no semantic updates because only fixtures
and an opt-in test target change. The diagnostics README links this report.
