# Temporary System Operator UUID Technical Debt

## Summary

`SYSTEM_OPERATOR_UUID` temporarily identifies unauthenticated system activity and
Omni Console job operations with one shared UUID. Platform uses it as the JPA
auditor fallback, while Omni Console sends it as `X-Omni-User` for Platform
requests.

Default development value:

```text
SYSTEM_OPERATOR_UUID=b252fe62-80f3-4df9-9734-5dc549705a25
```

## Scope

- [`.env.example`](../../.env.example) and [`.env.deploy.example`](../../.env.deploy.example)
  define the shared configuration contract.
- [`JpaAuditConfig.auditorProvider()`](../../apps/core/src/main/java/com/omni/platform/shared/entities/JpaAuditConfig.java#L23)
  parses the value as a UUID and uses it when no authenticated auditor exists.
- [`platformRequest()`](../../apps/omni-console/src/api.ts#L90) sends the value on
  Platform API requests; [`triggerJob()`](../../apps/omni-console/src/api.ts#L200)
  sends it on the manual trigger request.
- [`vite.config.ts`](../../apps/omni-console/vite.config.ts) exposes the value to the
  browser bundle and overwrites the header on local proxied requests.
- [`docker-compose.services.yaml`](../../docker-compose.services.yaml) passes the
  value into Platform.

## Contract Impact

- Kafka/service-to-service protobuf: unchanged.
- Object-storage JSON manifests: unchanged.
- Storage paths/dataset ownership: unchanged.
- Public Java/Python APIs: unchanged.
- Configuration/environment contract: changed by adding `SYSTEM_OPERATOR_UUID`.
  The value must be a valid UUID and must be consistent between Platform and the
  Console build/proxy environment.

## Risk

This value does not identify the real operator. Every temporary Console action
and unauthenticated system audit fallback is attributed to the same UUID. A
client-visible UUID/header is not authentication and must not be treated as an
authorization boundary. This mechanism is unsuitable for production operation
with individual users.

## Removal Criteria

1. A trusted identity layer supplies a per-user UUID to the reverse proxy.
2. The reverse proxy removes client-provided `X-Omni-User` and injects the
   authenticated UUID.
3. Omni Console stops embedding and sending `SYSTEM_OPERATOR_UUID`.
4. Platform retains a separately named system-only auditor identity if
   background writes still require one.
5. Security and audit tests prove user actions and background actions are
   attributed independently.

## Repository Guidance

No agent-rule update is required: existing guidance already requires trusted
proxy identity, prohibits hard-coded credentials, and forbids browser-to-Kafka
bypasses. This document records a bounded temporary configuration exception, not
a new production architecture.

## Verification Status

Static source and configuration inspection performed. Build, test, lint, format,
and runtime trigger verification were not run because they require explicit
approval under the repository verification gate.
