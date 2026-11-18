# Omni Console

[PostgreSQL-only readiness defaults](../../docs/reference/020-i10-postgres-only-cutover.md)
retire active legacy JSON discovery. Console keeps existing Query/Platform read
APIs; catalog availability now follows registered PostgreSQL evidence. Missing
catalog rows do not prove physical data absence or justify another attempt's proof.

## I10 UI alignment - November 15

Request links use `?view=work&requestId=<UUID>`. Opening/reloading a link restores
Work Operations and its request; closing removes only the request selector.
Browser history restores the selected view and request, and an empty dataset
catalog cannot override explicit operator navigation. The default view remains
the Market Dashboard when no route is supplied.

Work provenance now displays attempts, frozen approved inputs, request owners and
upstream work in separate tables, with links to request detail and upstream work.
Raw JSON remains under Advanced. Empty, unavailable and truncated evidence remain
distinct; trace context presence is not proof of propagation or query persistence.

Jobs links to bounded backfill requests and labels the stored next-run timestamp
without asserting active means schedule-enabled. Dataset Explorer exposes data
version, source execution, input lineage and further catalog pages after the first
500 entries. It aborts stale catalog reads and discards preview results after a
selection change. Frontend pagination and catalog READY are not exact-date work
coverage or per-attempt readiness. Query submissions still pin the selected
dataVersion; SQL Console exposes full accepted query versions.

Data freshness retains the existing 48-hour threshold but any stale, unavailable,
missing/invalid/future timestamp makes the banner stale. A fresh dataset cannot
hide another dataset's missing evidence. Only six rows are displayed with an
explicit total disclosure; all returned datasets are considered. This is latest
catalog-partition freshness, not an I10 readiness or all-reader parity gate.

November 16 review fixes resetting a partition filter to All without losing
catalog controls or the selected logical version. Current unit/component checks
pass 126/126 cases across 17 files; lint and
typecheck and production build pass. The first typecheck found an incomplete QueryStatus test fixture;
the fixture was completed without changing production behavior. Earlier 71/96/101
case results below are historical checkpoints. Browser/live backend, rollout and
numeric coverage are not established. Evidence is recorded in root release notes.

## I10 request detail and output evidence - source follow-up

Work Operations opens a read-only Request detail panel from the request list or
after backfill acceptance. It keeps root state separate from member execution,
settlement, failure, outbox status and review flags. Member search/filter and
50-row frontend pages limit rendering only: the existing detail endpoint still
loads its complete member/graph response. Refresh is manual for this panel.

Selecting a member opens output evidence. Current work coverage has three states
(Ready, Not ready, Unknown) and may name a different providing attempt. It never
changes the selected attempt's execution status. A separate exact-attempt lookup
shows retained proofs, logical output/version, schema/row count and frozen inputs.
Missing or unavailable proof never falls back to latest work readiness; it does
not prove no physical data exists. Physical coordinates and credentials are not
exposed. Existing operator authentication is reused.

The approved Console Nx typecheck, lint, test and build pass for this follow-up.
Vitest passes 96/96 cases across 13 files, including seven request-detail, ten
output-evidence and seven detail API cases. The first run passed 95/96; a test
selector counted the filter option as well as the two status displays and was
corrected to assert root and member state separately. The 71-test checkpoint
below remains historical. No browser/backend integration, runtime rollout,
numeric coverage or I10 completion is claimed.
See [evidence semantics](../../docs/reference/012-i10-closed-owner-reconciliation.md#additive-request-diagnostics).

## I10 backfill requests - source update, November 15

Work Operations now contains a New backfill form and request list in addition to
the read-only diagnostic panels. Goals and target definitions come from
`GET /api/v1/jobs/backfills/goals`; creation uses the existing bounded
`POST /api/v1/jobs/backfills`, never the removed definition trigger POST. The
Default/Recalculate/Refresh policies preserve the existing rerun/force contract.
Sector codes are user-entered current catalog codes, not inferred classifications.
Unknown submission outcomes retain the idempotency key for an unchanged retry.
HTTP ACCEPTED means acceptance, not completed work or READY.

The list uses `GET /api/v1/jobs/atomic-work/requests` with Active/History/All
filters. Only the first Active page polls; cursor pages preserve observedAt and
both cursor fields. Accepted work refreshes Active immediately. Request state,
member settlement and failures are separate, and shared members must not be
summed into global job counts. Missing record counts remain unknown.

Existing snapshot/provenance/publication diagnostics remain read-only and do not
gain per-member READY evidence. Request-detail/output UI was outside that first
update and is added in the follow-up above. The Jobs catalog and Market Dashboard default navigation are unchanged.
All requests retain the existing trusted private proxy identity boundary.
Owner-approved Nx typecheck, lint, test and build pass for this source update.
Vitest passes 71/71 cases in 10 files, including the form, request list, API boundary
and acceptance wiring. No numeric coverage, browser/backend integration, live
request or deployment verification is claimed. Earlier local test counts below
remain historical; I10 remains in_progress.
See [goal/create API](../../docs/reference/009-indicators-backfill.md#goal-discovery)
and [polling semantics](../../docs/reference/014-atomic-request-polling.md#ui-flow-and-pagination).

I10 READY integration (2026-11-02): **in_progress; local checks recorded, release gate unresolved**. [Current source, baseline decision and release gates](../../docs/reference/006-p14-i10-ready-integration.md). Enrollment/worker registration, PostgreSQL READY/provider/readers, fenced baseline backfill and shadow/reconciliation now have opt-in source. Exact-date admission defers missing inputs; failed attempts remain terminal. Runtime remains manifest-authoritative by default; no flags/deployment or retained migrations were activated. Durable worker recovery, tracing and P12 remain deferred.

Main-source reconciliation (2026-11-01, `4cc0011`): [commit/source mapping and remaining gates](../../docs/reference/003-p14-main-source-reconciliation.md). I10 remains planned/pending; I11 has no confirmed registry/plan scope on this baseline. Existing diagnostics do not implement PostgreSQL readiness, a watchdog or tracing.

## Historical P14 Work Operations — locally verified, activation separately gated

Owner selected the dashboard in Console. New **Work Operations** navigation reads
Platform atomic-work snapshots through the existing authenticated proxy. It shows
sampled distinct attempts/shared owners/upstream state, leases/retry, publication age,
request pages, work provenance and a separate old published-unfinished inventory.
No DB credentials, SQL input, polling or mutation controls. Worker timing and READY
are unknown; I10 metadata/watchdog and query tracing remain planned/reserved.
The existing Market Dashboard stays the default view. The November 1 closure
records 34 passing Console tests, including Work Operations. This is local test
evidence, not browser/deployment evidence. I8/I9 are locally completed; I10 is pending.
See [source-bound acceptance and limits](../../database/diagnostics/p14/v2/i8-i9-closure-20261101.md).
See [API, SQL source and local handoff](../../database/diagnostics/p14/v2/README.md).

Omni Console is the private React operations UI for dataset metadata, read-only
queries, and Platform-owned job operations.

## Planned Phase 13 order

Current source still opens on the fixed Market Dashboard. Owner-approved
[Plan 029](../../docs/plans/029-operator-trust-console.md) plans to replace that
product order with **Job Operations → Data Health → small Market Review** after the
owning increments are implemented and verified. The shell depends on P13-I2, not P13-I3; Data Health is explicitly unavailable until its API ships.

Job Operations will show truthful stage counts, daily jobs/minute as the primary
throughput unit, jobs/hour, jobs/day, duration percentiles, scheduler-outbox lag,
outstanding work, and guarded publish-drain estimates. Data Health will provide
manual bounded read-only EOD Parquet scans with exact provenance and no repair or
backfill. Scan results are cached in Query Service memory with visible scan/expiry times and exact provenance; eviction or restart is a cache miss, not proof of healthy data or durable scan history. Market Review will reuse the existing fixed code-owned widgets and bounded
Query Service contracts. Dataset Explorer remains secondary; Raw SQL is de-emphasized
and is not expanded by Phase 13.

## Existing Market Dashboard source

The current fixed, code-owned composition follows the historical
[dataset-component dashboard plan](../../docs/plans/009-dataset-component-market-dashboard.md)
and is organized around canonical datasets such as `eod`, `signals`, and
`sector-features` rather than producer services. This source is retained for the
future small Market Review; it does not override Plan 029's order.

## API configuration

- `VITE_QUERY_SERVICE_URL` selects the Query Service origin.
- `VITE_PLATFORM_API_URL` selects the Platform origin or same-origin reverse
  proxy prefix. It defaults to `/api/platform`; that proxy must rewrite the
  prefix before forwarding `/api/v1/jobs/**` to Platform.
- `SYSTEM_OPERATOR_UUID` temporarily selects the UUID sent as `X-Omni-User` on
  Platform requests. It defaults to `b252fe62-80f3-4df9-9734-5dc549705a25` and
  must match Platform's value.
- During `nx run omni-console:serve`, Vite proxies the default `/api/platform`
  prefix to `OMNI_CONSOLE_PLATFORM_PROXY_TARGET`, which defaults to
  `http://localhost:8080`. The proxy removes any incoming `X-Omni-User` value
  and injects `SYSTEM_OPERATOR_UUID`.

The browser also sends the temporary UUID so direct `VITE_PLATFORM_API_URL`
requests work during this interim configuration. This is not an authentication
boundary: production deployments with real users must use the private reverse
proxy to remove client-provided values and inject the authenticated operator
identity on both Query Service and Platform requests. See
[temporary system operator UUID technical debt](../../docs/technical-debt/003-system-operator-uuid.md).

## Jobs tab

The Jobs tab is read-only: catalog, search, stored schedule information, dependencies
and recent execution history. Generic operator triggering, legacy audit polling
and Dataset Explorer metadata-sync actions remain deferred under TD-017. The old
trigger POST is removed; the audit GET remains authenticated HTTP 410. Console
does not call either route. Bounded backfill creation and request polling live in
Work Operations as described above. Dataset
browsing, partition selection, query preview and read-only Data Health scans remain.
Future manual metadata refresh requires owner-approved trigger/API/audit support;
do not substitute direct Kafka, storage writes or scheduler state changes.

Historical Phase 7 evidence below predates the I3.d owner deferral.

Phase 7 acceptance is verified through the defined Nx targets:

```bash
nx run omni-console:lint
nx run omni-console:typecheck
nx run omni-console:test
nx run omni-console:build
```
