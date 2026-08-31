# Omni Console

Omni Console is the private React operations UI for dataset metadata, read-only
queries, and Platform-owned job operations.

## Planned Dashboard

The [dataset-component dashboard plan](../../docs/plans/010-dataset-component-market-dashboard.md)
proposes making Market Dashboard the default section and composing it from
components owned by canonical logical datasets such as `eod`, `signals`, and
`sector-features`. Until that increment is implemented and verified, Dataset
Explorer remains the default section.

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

The Jobs tab reads the redacted Platform catalog, displays triggerability and
recent executions, requires a reason plus confirmation, generates a stable
idempotency key, and follows an accepted execution with bounded backoff. It does
not contain scheduler decisions, credentials, object paths, Kafka access,
force/bypass, cancellation, or runtime-parameter controls.

## Planned Dataset Explorer metadata refresh

P3-I5 plans a `Refresh Metadata` action for supported exact dataset partitions. V1
is limited to EOD exchanges HOSE, HNX, and UPCOM and remains `pending`; this action
is not implemented yet.

The confirmation shows dataset and partition and requires a reason. Console submits
logical `dataset`/`partition` parameters through the existing Platform Phase 7
trigger API, displays the returned execution ID, polls the existing execution
status with bounded backoff, and refreshes the metadata view after success. It
disables duplicate submission while pending and preserves the current metadata
view after failure.

Console does not resolve object paths, touch object storage, recompute Parquet, or
make scheduler/dependency/concurrency decisions. Unsupported partitions do not show
the action. Physical paths, buckets, manifest paths, credentials, force/bypass, and
direct browser-to-Kafka fields are never sent.

Phase 7 acceptance is verified through the defined Nx targets:

```bash
nx run omni-console:lint
nx run omni-console:typecheck
nx run omni-console:test
nx run omni-console:build
```
