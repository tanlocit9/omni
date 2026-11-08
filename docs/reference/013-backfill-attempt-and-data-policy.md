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
