# Omni Console

Omni Console is the private React operations UI for dataset metadata, read-only
queries, and Platform-owned job operations.

## API configuration

- `VITE_QUERY_SERVICE_URL` selects the Query Service origin.
- `VITE_PLATFORM_API_URL` selects the Platform origin or same-origin reverse
  proxy prefix. It defaults to `/api/platform`; that proxy must rewrite the
  prefix before forwarding `/api/v1/jobs/**` to Platform.

The browser intentionally does not send `X-Omni-User`. The private reverse proxy
must remove any incoming value and inject the authenticated operator identity on
both Query Service and Platform requests.

## Jobs tab

The Jobs tab reads the redacted Platform catalog, displays triggerability and
recent executions, requires a reason plus confirmation, generates a stable
idempotency key, and follows an accepted execution with bounded backoff. It does
not contain scheduler decisions, credentials, object paths, Kafka access,
force/bypass, cancellation, or runtime-parameter controls.

Verification remains through the defined Nx targets:

```bash
nx run omni-console:lint
nx run omni-console:typecheck
nx run omni-console:test
nx run omni-console:build
```
