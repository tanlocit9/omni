# Backfill attempt and data policy

Source change: 2026-11-07. Focused local PostgreSQL/worker E2E checks passed;
P14-I10 remains in_progress with its full coverage and cutover gate open.
Separate creating an execution attempt from refreshing provider data. Keep the
existing IN_FLIGHT_ONLY attempt policy; reuse of SUCCESS attempts is not enabled
by this checkpoint or implied by Parquet/READY existence.

## HTTP behavior

`POST /api/v1/jobs/backfills` accepts optional booleans rerun and force; both default
false. Force implies rerun, including an explicit rerun=false. The normalized
values participate in request idempotency and the 202 response, not atomicWorkId.

| Input | Attempts | EOD | Indicators |
| --- | --- | --- | --- |
| Flags absent / both false | Reuse compatible PENDING/RUNNING attempts with live requesters; otherwise create new | On new execution, reuse exact covered Parquet day; otherwise fetch | Compute on new execution |
| rerun=true, force=false | New attempts throughout the expanded chain | Reuse exact covered day; fetch if missing | Recompute |
| force=true | New attempts throughout the expanded chain | Always invoke provider; never satisfy the command from existing day rows | Recompute from approved refreshed EOD |

FAILED/BLOCKED/SUCCESS or ownerless attempts are not reused. Frozen upstream
attempts and work identity must match for sharing. Symbols still use a current-day
catalog snapshot and fetch normally on a new attempt. No new reuse-policy enum,
schema migration or status is introduced. Successful-attempt reuse would need a
separate approved policy and exact READY/upstream compatibility work.

Example body, with current active sector membership and bounded explicit dates:

```json
{
  "goal": "SYNC_INDICATORS",
  "sectorCodes": ["BANKS"],
  "sectorLevel": 2,
  "dateFrom": "2026-10-19",
  "dateTo": "2026-10-23",
  "rerun": false,
  "force": false
}
```

Use a new Idempotency-Key when changing rerun/force or retrying a terminal request.
Same-key replay does not restart execution. Existing accepted rows/messages are
not rewritten. New normalized request JSON can conflict with an older same-key
request lacking rerun; use a new key after reviewing its existing work.

## Worker compatibility and data safety

Platform emits backfillPolicy.dataPolicy=PREFER_EXISTING or REFRESH alongside
normalized rerun/force and the existing verifyEodParquet=true. New Ingestor uses
dataPolicy explicitly; unknown values fail before provider fetch or Parquet write.
PREFER_EXISTING requires actual requested-day rows, not just file existence.
REFRESH takes the existing provider/lookback path even if the day is present;
missing provider-day output fails instead of falling back to old rows.

For already queued commands without dataPolicy, preserve legacy semantics: old
force=true plus verifyEodParquet may reuse the covered day; old force=false still
uses normal provider sync. Do not reset offsets or mutate immutable payloads.
Deploy matched Platform and Ingestor before relying on the new force contract;
an older worker ignores dataPolicy and cannot guarantee forced provider refresh.
Existing scoped backfill gates and the exclusive drained legacy-writer window
remain required. No live deployment, feature activation or provider call was run.

Refresh is scoped to requested business days, not deletion/reconstruction of all
history. Existing merge/dedup preserves unrelated dates. Reused EOD still validates
and registers for its new attempt; fresh EOD must prove the date before writing.
READY-last, output ACK before SUCCESS, exact frozen lineage, canonical metadata
ownership and analytical work hashes remain unchanged.

## Impact and verification inventory

| Production boundary | Regression source / intended branches | Executed now |
| --- | --- | --- |
| IndicatorBackfillController | Controller tests: omitted/false flags, rerun, force overriding rerun, normalized policy/idempotency response | PASS: 21 controller cases |
| GraphWorkRequestService and WorkExpansion | PostgreSQL policy cases: in-flight sharing, distinct rerun/force attempts with unchanged work IDs, same-key policy conflict, persisted worker policy | PASS: 68 PostgreSQL + 9 expansion cases |
| Ingestor stock_prices | covered/missing EOD across legacy-force, legacy-normal, PREFER_EXISTING and REFRESH; changed fetched value; unknown policy rejects without fetch/write | PASS: 40 focused cases |

Graph impact over four source files was truncated to 60/72 impacted nodes and had
979 unresolved calls. Cross-service reconciliation: Platform owns the HTTP/attempt
policy and payload; Ingestor applies provider refresh; Analyzer still computes
Indicators from frozen EOD with no algorithm change. Query and Console have no
source/UI change. Shared Python and protobuf schemas are unchanged; metadata is
additive. Existing storage paths, database schema/reuse_policy, dataset ownership
and configuration defaults remain. Operations require matched workers and keep
the existing writer-drain/reader readiness boundaries.

### Actual cross-service policy execution

BackfillDataPolicyIntegrationTest adds six HTTP/Kafka/Python worker/MinIO Parquet/
registration/READY cases to platform:test-integration, alongside the existing
JobRunCrossServiceIntegrationTest and I10WorkReadinessIntegrationTest. Only the
external market provider is stubbed. The fixture records provider calls and reads
actual canonical Parquet before/after the real handler; it does not substitute
PostgreSQL readiness for a file read or replace computation/publication code.

| Case | Observed result |
| --- | --- |
| Default, covered day and no PostgreSQL READY for that work | SUCCESS and new exact-day EOD/Indicators READY; zero provider calls despite a provider-failure switch |
| rerun, covered day and no prior PostgreSQL READY | Same Parquet reuse behavior with rerun policy; no provider call |
| File exists but requested day is absent | Exactly one fetch, day row appears and both outputs become READY; unrelated rows preserved |
| force with an existing day | Exactly one fetch, canonical close changes 177 to 777; unrelated row digest preserved; new exact-day READY |
| force provider failure | Expected FAILED request; cached row remains 777, no new-attempt READY and Indicators not published |
| force provider response lacks requested day | Same fail-closed behavior; cached day is not used as fallback |

All six new cases and the two existing cross-service cases pass: 8/8, no skips.
Seven request probes (one seed plus six cases) and five provider invocations
include the seed, missing-day fetch, successful refresh and two expected failures.
Initial fixture failures (generic reflection overload and missing symbol sector
classification) are retained separately; the final fixture uses explicit Object
targets and provider-supplied classification. Production guard semantics were
not relaxed to make the tests pass.

The owner-authorized manifest `P14-I10-policy-integration-20261107` concludes PASS,
required=3/pass=3 for integration/postgres-policy, test/ingestor-policy and
integration/kafka-chain. Executed target scopes:

```text
npx.cmd nx run platform:test --skip-nx-cache --args="--tests com.omni.platform.GraphWorkRequestsPostgresTest --tests com.omni.platform.modules.scheduler.controllers.IndicatorBackfillControllerTest --tests com.omni.platform.modules.scheduler.expansion.WorkExpansionTest"
npx.cmd nx run ingestor:test --skip-nx-cache --args="-k atomic_stock_work --no-cov"
npx.cmd nx run platform:test-integration --skip-nx-cache
```

Both Platform commands additionally used --rerun-tasks and the ignored
`--init-script ../../.agent/operations/i10-policy-isolated-build.gradle`, redirecting
build output away from the live devtools classpath. All test databases/brokers/
object stores were disposable. JUnit, focused JaCoCo, source snapshots, provider
call logs and Parquet proofs are retained under the manifest's ignored artifacts.
Initial failure reports are separate from the final e2e results.

Focused Java class coverage: controller 100% line/100% branch; graph service
98.10%/86.50%; WorkExpansion 91.34%/78.98%. WorkExpansion class branch coverage
remains below the default 80% threshold; focused policy execution does not close
full-I10 attributable coverage. Python ran without coverage instrumentation;
no Python numeric pass is inferred. No lint/format, standalone build target,
live provider call, migration, production backfill or deployment was run.

Registry, Plan 030, reference/009, release notes, flow, Kafka/deployment and service
guidance describe the policy separation. Architecture/ADR/data-lake/database and
agent rules need no new ownership/schema decision; they retain all activation and
READY safeguards. The closed-owner checkpoint now has focused PostgreSQL evidence;
its complete acceptance/cutover gate remains open.
Static changed-scope review is not I10 acceptance, deployment or live evidence.
