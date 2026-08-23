# Omni Query Service

Private, read-only server-side query boundary for Omni Console. It resolves
logical dataset references through canonical `READY` manifests and executes
bounded SQL with native DuckDB. Object-storage credentials and physical paths
never cross the HTTP boundary.

## API

- `GET /v1/datasets`
- `GET /v1/datasets/{dataset}/partitions`
- `POST /v1/queries`
- `GET /v1/queries/{query_id}`
- `GET /v1/queries/{query_id}/result?format=json|arrow`
- `DELETE /v1/queries/{query_id}`

The service must remain private or sit behind identity-aware access. It accepts
only read-only SQL over logical aliases declared in each query request.
`POST /v1/queries` requires the trusted upstream identity header
`X-Omni-User`; anonymous or blank identities are rejected instead of being
recorded under a shared fallback actor. The identity-aware proxy must replace,
not append, this header before forwarding traffic to the private service.
