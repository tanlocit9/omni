# P14 diagnostics v2 — prepared, not executed

Owner: [Plan 030](../../../../docs/plans/030-static-graph-dispatch-planner.md).
This additive pack observes I9 attempts/requests under PostgreSQL 16 + V18.
Keep [I5 v1](../v1/README.md) for frozen JobRun/path/legacy diagnostics and its
historical benchmark. Owner selected an Omni Console dashboard: source now includes
a read-only Platform adapter and Console Work Operations view. No migration, grants
or scheduler flag changes.

Prepared for review on 2026-10-30. SQL, Platform API and Console dashboard are source artifacts only;
no query, browser check, regression, performance or retained-data run was performed.
See [full review](review.md). I8/I9 remain verification_pending, I10 pending.

## Files and scope

| File | Inputs | Output / bounds |
| --- | --- | --- |
| preflight.sql | None | Table/definition-column availability + server version; missing schema means unavailable, never an empty healthy system. |
| snapshot.sql | since, until, observed_at, after_id, row_limit | JSON v2 for [dashboard.html](dashboard.html); <=100 requests, <=1000 distinct attempts, <=2000 edges; separate truncation flags. |
| published_unfinished.sql | observed_at, published_before, after_id, row_limit | <=400 messages + lookahead, legacy/I8/I9; not limited by request creation window. |
| inspect_work.sql | atomic_work_id, observed_at | <=100 attempts + lookahead, <=400 owners, <=1000 upstream links; immutable identity/approval checks and one-hop provenance. |

SQL statements are SELECT-only. Run through a read-only libpq service/role with
REPEATABLE READ READ ONLY, statement_timeout=5s and lock_timeout=500ms. File
placeholders use psql quoted values (`:'name'`), not string concatenation. No
connection secrets or live configuration are included. Example commands are **not run**:

```bash
PGOPTIONS='-c default_transaction_read_only=on -c statement_timeout=5000 -c lock_timeout=500' \
psql 'service=omni-diagnostics' -X -qAt --single-transaction -v ON_ERROR_STOP=1 \
  -c 'SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY' \
  -f database/diagnostics/p14/v2/preflight.sql

PGOPTIONS='-c default_transaction_read_only=on -c statement_timeout=5000 -c lock_timeout=500' \
psql 'service=omni-diagnostics' -X -qAt --single-transaction -v ON_ERROR_STOP=1 \
  -v since='2026-10-29T00:00:00Z' -v until='2026-10-30T00:00:00Z' \
  -v observed_at='2026-10-30T00:00:00Z' -v after_id='' -v row_limit=25 \
  -c 'SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY' \
  -f database/diagnostics/p14/v2/snapshot.sql > /tmp/p14-snapshot.json
```

Use actual UTC times; since must precede until and until must be <= observed_at.
Other scripts use the same invocation with their documented parameters. To inspect
old unfinished publication, choose published_before=observed_at minus 30 minutes.
Set after_id to nextCursor for continuation, preserving filters and observation clock.
Separate exports taken while writers run are separate snapshots, not an atomic inventory.

## Interpretation

- Snapshot request creation window excludes older requests, even when still running.
  Always pair it with published_unfinished.sql when investigating stuck work.
- A page with hasMoreRequests does not represent global totals. Attempts are distinct
  within this page; the same shared attempt can appear on another request page.
  Do not sum pages or count request membership as independently executed work.
- attempt/edge truncation is explicit. Owner/upstream aggregate counts cover each
  sampled attempt's bindings, including owners outside the request page. Edge detail
  may refer outside the sampled attempt list. Partial graph is not an integrity error.
- PENDING_GUARDS_NOT_EVALUATED is not READY/admissible. No DependencyRegistry or
  dataset/bytes check runs here. workerProcessing/datasetReady are unknown.
- firstPublishedAt and age are transport evidence. Over 30 minutes is a review
  threshold only; watchdog FAILED/EXECUTION_TIMEOUT is planned I10, not implemented.
- Definition totals cover the stored catalog, outside the request window. Schedule-
  enabled presets are not terminal entries; seed expectation seven is source context,
  never a live SQL count or a fixed invariant for edited configurations.
- No flag state is inferred from tables or activity. Inspect active Platform config
  separately. Metadata-registration lag, consumer lag, actual worker start/heartbeat,
  READY and query traces cannot be derived from these tables.
- Payloads, raw errors, actor identity, raw SQL, secrets and trace values are omitted.
  Work IDs and approved logical dataset references remain operational identifiers;
  keep exports private and apply local retention/access controls.

Output bounds do not bound table/index pages scanned. Member/dependency aggregation
can inspect the full accepted graph for selected requests. Use smaller request pages,
read-only timeouts and controlled query-plan measurements before widening scope.
Empty results, invalidWindow=false, missing schema and failed queries are distinct.
Do not use the pack to claim I8/I9 acceptance or any default activation readiness.

## Dashboard in Omni Console

Open **Work Operations** in the Console navigation after deploying matching Platform
and Console source. It loads authenticated bounded snapshots through the existing
/api/platform proxy; no browser DB credentials or arbitrary SQL input. Existing Jobs,
Market Dashboard, Dataset Explorer and SQL Console remain available.

The primary view has request-window selection (1h/24h/7d), explicit refresh, per-page
counts, work/status filters, request pagination and per-work provenance. A separate
published-unfinished panel queries old legacy/I8/I9 work outside the request creation
window and has its own observation clock/pagination. No automatic polling, write,
trigger/cancel/retry, READY promotion or timeout action is introduced.

Platform GET routes under the existing authenticated /api/v1/jobs/atomic-work boundary:

| Route | Parameters / limits |
| --- | --- |
| /snapshot | hours=24 (1..168), optional paired since/until, observedAt, afterId UUID, limit=25 (1..100); explicit windows <=7 days and no later than observedAt. |
| /published-unfinished | optional observedAt/afterId, limit=50 (1..100); publication older than 30 minutes, no lower creation bound. |
| /work | atomicWorkId=work:<64 lowercase hex>; <=100 attempts, 400 owners, 1000 upstream links. |

Readers use REPEATABLE READ/read-only transactions (10s transaction, 5s JDBC query
and 500ms local lock timeout). Invalid windows/limits/identity return HTTP 400; missing
schema or database timeout returns a safe HTTP 503, never empty success. Authentication
uses existing trusted operator proxy handling; no new public/exempt route is added.
UTC clocks are returned/displayed explicitly. Page continuation pins the observation
window, but each HTTP page is still a new MVCC snapshot while writes continue.

Canonical SQL lives in [Platform resources](../../../../apps/core/src/main/resources/diagnostics/p14).
The psql entrypoints use relative include commands to execute those same files; the
API adapts only the fixed named-value placeholders. Both paths use the same aggregates
and bounds. No duplicated SQL implementation or runtime filesystem checkout dependency.

[dashboard.html](dashboard.html) remains an optional offline JSON-import companion,
not the primary dashboard. Console integration is a bounded owner-requested diagnostic
surface, not completion of Phase 13 stage instrumentation, capacity metrics or I10.

## Local verification handoff

Not run: PostgreSQL execution/fixtures, API/auth/timeout cases, Console/browser checks, SQL plans/latency, offline import checks,
existing I5 read-model regression and scoped Platform coverage. Before operational use,
exercise empty/missing-schema/invalid input, shared attempts, pagination/truncation,
expired leases, upstream failure, success-with-blocked-outbox, legacy publications,
unknown status, date boundaries and synthetic malicious JSON display. Reconcile
results with actual status/claim rules; never treat script presence as a pass.
Graph discovery/change tooling remains unavailable; refresh at implementation.

### Added regression source — NOT RUN

GraphWorkRequestsPostgresTest now includes packaged SQL pagination/shared-owner/identity
and authenticated HTTP empty/error cases. AtomicWorkDiagnosticsValidationTest covers
invalid windows/limits/identity before DB access. WorkOperations.test.tsx covers unknown
versus empty, errors, pagination/window reset and work inspection; App.test.tsx includes
navigation. Existing targeted queries still need full failure/truncation/performance and
retained-data evidence before operational acceptance.
