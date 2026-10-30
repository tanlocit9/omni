# Omni Console

## P14 Work Operations — source delivery, verification pending

Owner selected the dashboard in Console. New **Work Operations** navigation reads
Platform atomic-work snapshots through the existing authenticated proxy. It shows
sampled distinct attempts/shared owners/upstream state, leases/retry, publication age,
request pages, work provenance and a separate old published-unfinished inventory.
No DB credentials, SQL input, polling or mutation controls. Worker timing and READY
are unknown; I10 metadata/watchdog and query tracing remain planned/reserved.
The existing Market Dashboard stays the default view. Added tests are NOT RUN.
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

The Jobs tab is read-only: catalog, search, schedule information, dependencies
and recent execution history. Operator triggering, request polling and Dataset
Explorer metadata-sync actions are deferred under TD-017. Their old Platform
routes return authenticated HTTP 410; the Console no longer calls them. Dataset
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
