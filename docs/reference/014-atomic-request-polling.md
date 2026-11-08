# Atomic request discovery and polling

P14-I10 source checkpoint, 2026-11-08. This read-only API lets a UI discover work
started by another tab/client or an earlier session, without remembering a POST
response. It does not start, retry, cancel or reconcile work. I10 remains in_progress.

## API

```http
GET /api/v1/jobs/atomic-work/requests?status=active&limit=20
X-Omni-User: <trusted operator>
```

The existing trusted-operator boundary applies; identity comes from the trusted
proxy. No reader-token bypass is added. Operators see operational requests across
operators, matching existing request-detail diagnostics; this is not a tenant API.

status accepts active (default), history or all, case-insensitively. Active means
PREPARING/PREPARED; history means SUCCESS/FAILED/CANCELLED. There is no PENDING
request state in the current database schema. Member PENDING is separate.
limit defaults to 20 and must be 1..100. Invalid filters/cursors return HTTP 400.

The camelCase response is:

```json
{
  "observedAt": "2026-11-08T00:00:00Z",
  "items": [{
    "requestId": "f7534016-a06c-42bc-9bbc-2ce4013f610b",
    "executionId": "251b14e2-48d7-434f-9d7c-d1f45ca52fc3",
    "targetDefinitionId": "c7ad4cb3-a53a-418c-81b1-aeb24558fef3",
    "goal": "SYNC_INDICATORS",
    "mode": "BACKFILL",
    "state": "SUCCESS",
    "createdAt": "2026-11-07T16:47:46.254292Z",
    "startedAt": "2026-11-07T16:47:46.254292Z",
    "finishedAt": "2026-11-07T16:56:07.696666Z",
    "elapsedMs": 501442,
    "totalMembers": 603,
    "memberCounts": {"SUCCESS": 603},
    "unfinishedMembers": 0,
    "isSettled": true,
    "hasFailure": false,
    "jobs": [
      {"jobType": "SYNC_INDICATORS", "totalMembers": 300, "memberCounts": {"SUCCESS": 300}},
      {"jobType": "SYNC_STOCK_PRICE", "totalMembers": 300, "memberCounts": {"SUCCESS": 300}},
      {"jobType": "SYNC_SYMBOLS", "totalMembers": 3, "memberCounts": {"SUCCESS": 3}}
    ],
    "recordsSynced": null,
    "recordsSkipped": null
  }],
  "hasMore": false,
  "nextCursor": null
}
```

The example uses the observed historical request, not a deployment claim for this
new endpoint. Its old root counters remain null; new finalization aggregates target
counters as described in [the backfill guide](009-indicators-backfill.md#root-execution-counters).
Counter null means unknown, not zero. Counts are execution members, not records.
Shared attempts count once per owning request; do not sum requests to infer unique
physical executions. Ancestor work is included, auxiliary run parents/root are not.
Absent statuses mean zero. jobs is grouped by job type, not definition alias.

elapsedMs is wall-clock time from root startedAt to finishedAt, or observedAt while
unfinished, floored to milliseconds and clamped at zero. Root startedAt currently
means orchestration acceptance, including queue/dependency wait; it is not the first
worker receipt. Per-job intervals overlap and must not be added to root duration.
isSettled/hasFailure describe members independently of request state. A CANCELLED
request can still have unfinished shared or uncertain attempts; cancellation is not
proof of worker termination. A PREPARING request with no visible members has zero
unfinished members; only a terminal request state proves request closure.

## UI flow and pagination

1. On opening the page, fetch active requests. Poll the first page every 3-5 seconds
   with a fresh observedAt (or omit it). Keep requestId as the row identity.
2. Use status=all/history for completed requests. A request disappearing from active
   requires refresh from history or request detail, not an assumption of SUCCESS.
3. For the next page, send the response's observedAt plus both nextCursor fields
   beforeCreatedAt/beforeId unchanged. Requests sort by createdAt DESC, id DESC.
4. Open the unchanged GET /api/v1/jobs/atomic-work/requests/{requestId} only for
   graph/member diagnosis. GET /api/v1/jobs/executions/{executionId} remains a
   single-execution status endpoint.

The cursor is a created-time high-water boundary with a UUID tie-breaker; it is not
a durable point-in-time snapshot across calls. Member states and filter membership
can change between pages. Fresh polls must reset the cursor/window. observedAt
cannot be in the future; beforeCreatedAt/beforeId must be supplied together and
the cursor date cannot exceed observedAt.

Only limit+1 request rows have their member statistics aggregated. One SQL statement
runs in a read-only repeatable-read transaction, with 5-second query and 500ms lock
timeouts. Database query failures are reported as unavailable, never as an empty
successful page. Existing request creation indexes are unchanged; large historical
inventories may need a separately measured index/query tuning change. No total
history count, graph JSON, raw payload or per-member READY HTTP lookup is returned.

## Impact, evidence and documentation

Platform owns the new query service, DTOs, route and regression cases. Persistence
is read-only over existing request/member/attempt/execution/definition tables;
there is no migration or new table. The existing detail endpoint and old naming
remain compatible. Console has no source change; a future UI can consume the route.
Analyzer, Ingestor, Query Service, shared Python/contracts, Kafka, Parquet, dataset
ownership, default feature flags and deployment configuration have no change.
No READY, dispatch, retry, writer-exclusion or lineage rule is altered by polling.

The owner approved the selected Platform test command recorded through
tools/check_result.py under P14-I10-polling-20261108. The source/test matrix is:

| Changed behavior | Regression |
| --- | --- |
| Controller delegation and bounded query forwarding | GraphWorkOperationsControllerTest.forwardsBoundedPollingParametersWithoutLoadingFullGraph |
| Invalid filters, limits, cursor pairing/window | GraphWorkOperationsControllerTest.rejectsInvalidPollingQueriesBeforeDatabaseAccess |
| Query errors and operator boundary | GraphWorkOperationsControllerTest.unavailableDatabaseIsReportedAsUnavailableRatherThanEmpty; pollingUsesExistingTrustedOperatorBoundary |
| Real SQL, tied cursor ordering, high-water exclusion, shared membership counts, camelCase and no graph | GraphWorkRequestsPostgresTest.pollingPagesHaveStableTieBreakersAndExcludeGraphsAndLaterRequests |
| Live failure, settlement/history filtering, finished duration | GraphWorkRequestsPostgresTest.pollingReflectsMemberFailureSettlementAndHistoricalRootTimes |
| Cancellation versus shared attempt progress and successful history counters | GraphWorkRequestsPostgresTest.pollingSeparatesCancelledOwnersFromProgressOfSharedAttempts |
| Root target aggregates and unknown counters | GraphWorkRequestsPostgresTest.rootCountsOnlyTargetsAndPreservesUnknownCounters |
| Large graph identity, exact dates and definition bindings | GraphWorkExpansionTest.indexesLargeSymbolUniverseWithoutRepeatedFullCandidateScans |

Manifest PASS: 100 cases (73 PostgreSQL, 11 controller/validation/operator checks,
16 graph), zero failures/errors/skips. Query service coverage is 94.23% line and
97.30% branch. [Review and remaining coverage limits](015-i10-review-20261108.md)
distinguish this selected local run from older worker E2E evidence. This is not
full-I10 acceptance, hosted CI, retained DB migration, live activation or cutover.

Documentation reconciliation covers Plan 030, registry, roadmap, release notes,
API/backfill reference, Platform guide and indexes. Architecture/ADR, Kafka/data
lake/database, deployment, development ownership, other service guides and agent
instructions need no ownership, schema, flag or protocol amendment for this
read-only route. Existing I10 coverage and coordinated-cutover gates remain open.
