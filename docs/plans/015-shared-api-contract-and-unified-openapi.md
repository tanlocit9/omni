# Shared API Contract and Unified OpenAPI

## Status

Proposed implementation plan.

## Objective

Create a reusable workspace-level API contract pipeline for Python backend services and TypeScript/React clients. FastAPI/Pydantic remains the source of truth, OpenAPI is the language-neutral artifact, generated TypeScript models and clients are consumed by Omni Console, and one developer portal exposes all backend service APIs through Swagger UI.

The resulting OpenAPI documents must be directly importable into Postman, Hoppscotch, Insomnia, and other OpenAPI-compatible tools.

## Decisions

1. HTTP API contracts use OpenAPI 3.x generated from FastAPI/Pydantic.
2. `libs/contracts` continues to own protobuf Kafka/service-to-service schemas and does not own browser HTTP DTOs.
3. Python request/response models are the canonical HTTP source.
4. TypeScript transport types and clients are generated; they are not maintained manually in parallel.
5. Generation tooling is generic and registry-driven so future Python services can opt in without copying scripts.
6. Every service retains an independently importable OpenAPI document.
7. A unified developer portal presents all registered backend APIs in one Swagger UI site.
8. The unified portal must not require rewriting colliding OpenAPI operation IDs or schema names into one unsafe merged namespace.
9. CI checks deterministic generation, stale artifacts, lint, and breaking changes.
10. Development documentation may be publicly reachable only in explicitly configured local/development environments; production defaults to disabled or authenticated private access.

## Contract Boundaries

### HTTP/OpenAPI

Used by browser clients, Postman, Hoppscotch, scripts, and backend HTTP consumers.

Owned by each FastAPI service through explicit request/response Pydantic models and route declarations.

### Protobuf

Owned by `libs/contracts` for Kafka and service-to-service message evolution.

Do not duplicate HTTP DTOs in protobuf unless a real non-HTTP transport requires the same semantic contract and an explicit adapter is designed.

### Persistence

Database schemas and `_metadata/metadata.json` are internal persistence contracts. They are not returned directly as HTTP wire models.

## Target Flow

```text
FastAPI routes + Pydantic DTOs
              |
              v
 deterministic OpenAPI export
              |
      +-------+--------+
      |                |
      v                v
service openapi.json   lint/breaking checks
      |
      v
TypeScript client generation
      |
      v
React Console adapters/view models
```

The developer portal reads the registered service OpenAPI documents and offers one Swagger UI with a service selector.

## Proposed Workspace Structure

```text
tools/api-contracts/
  registry.json
  export-openapi.py
  generate-clients.mjs
  check-generated.mjs
  check-breaking.mjs
  normalize-openapi.mjs
  serve-docs.mjs
  README.md

api-contracts/
  query-service/openapi.json
  analyzer/openapi.json
  ingestor/openapi.json
  platform/openapi.json

apps/api-docs/
  project.json
  index.html
  swagger-config.js

apps/omni-console/src/generated/
  query-service/
  analyzer/
  ingestor/
  platform/
```

Only services with supported HTTP APIs are registered. Kafka-only worker internals are not given artificial HTTP APIs merely to appear in Swagger.

## Service Registry

Example `tools/api-contracts/registry.json`:

```json
{
  "version": 1,
  "services": [
    {
      "name": "query-service",
      "title": "Omni Query Service",
      "kind": "fastapi",
      "app": "apps.query-service.main:app",
      "workingDirectory": "apps/query-service",
      "schemaOutput": "api-contracts/query-service/openapi.json",
      "clientOutput": "apps/omni-console/src/generated/query-service",
      "developmentBaseUrl": "http://localhost:8001",
      "enabledInPortal": true
    }
  ]
}
```

Because Python import paths with hyphenated directories may not be directly importable, each service can alternatively expose a repository-owned export command. Registry validation must reject duplicate names, duplicate outputs, paths outside the workspace, unsupported kinds, and missing commands.

## Service OpenAPI Requirements

Each registered backend service must provide:

- stable API title and semantic contract version;
- versioned routes such as `/api/v1/...`;
- explicit Pydantic request and response models;
- stable unique `operation_id` values;
- tags and concise endpoint descriptions;
- declared authentication/security schemes;
- representative safe examples;
- bounded validation constraints;
- documented error response models;
- development server URL and optional deployed server templates;
- no secret defaults, credentials, or internal stack traces.

Do not expose internal Pydantic persistence models by using them directly as route responses. Define boundary DTOs and transformation code.

## Deterministic OpenAPI Export

The exporter must:

1. load the service application without starting a network server;
2. call `app.openapi()`;
3. normalize nondeterministic ordering and optional generated fields;
4. validate the resulting OpenAPI document;
5. serialize stable, formatted JSON;
6. write only when content changes;
7. fail on duplicate operation IDs or invalid references.

The checked artifact is useful for Postman/Hoppscotch import, review diffs, client generation, portal serving, and compatibility comparison.

No live backend should be required to generate or inspect the schema.

## TypeScript Client Generation

Use one pinned generator, wrapped by repository scripts. `@hey-api/openapi-ts` is the preferred initial option because it can generate TypeScript models and a fetch-compatible client. Record the exact version in `package-lock.json`.

Generated output rules:

- one directory per backend service;
- a stable barrel export;
- no manual edits;
- deterministic output;
- no embedded production credentials or environment-specific secrets;
- runtime base URL supplied by application configuration;
- generated transport DTOs may be mapped into handwritten frontend view models;
- handwritten code must not redefine the wire contract.

Example usage:

```ts
import type {
  DatasetMetadataResponse,
  PartitionKeyDefinition,
} from '@/generated/query-service';
```

## Unified Swagger UI

### Recommended model: one portal, multiple specifications

Build a static `apps/api-docs` application using Swagger UI's `urls` configuration:

```js
window.ui = SwaggerUIBundle({
  dom_id: '#swagger-ui',
  urls: [
    {
      name: 'Query Service',
      url: '/specs/query-service/openapi.json',
    },
    {
      name: 'Platform API',
      url: '/specs/platform/openapi.json',
    },
  ],
  deepLinking: true,
  displayRequestDuration: true,
  persistAuthorization: false,
});
```

This presents one Swagger UI site with a service selector while preserving independent schemas and avoiding collisions between common names such as `ErrorResponse`.

### Optional aggregate discovery document

Provide a small machine-readable index:

```json
{
  "version": 1,
  "services": [
    {
      "name": "query-service",
      "title": "Omni Query Service",
      "openapiUrl": "/specs/query-service/openapi.json",
      "developmentBaseUrl": "http://localhost:8001"
    }
  ]
}
```

Postman and Hoppscotch normally import an individual service OpenAPI URL/file. The portal offers download links for each specification.

### Optional merged OpenAPI

Do not make a merged specification the source of truth. A merged convenience artifact may be added later only if it:

- prefixes component schema names by service;
- preserves operation IDs and security definitions safely;
- rewrites references correctly;
- detects path/method collisions;
- remains generated from the independent service documents;
- passes OpenAPI validation and import smoke tests.

The multi-spec Swagger portal is the safer V1.

## Postman, Hoppscotch, and CLI Use

Each generated schema is available as a file and development URL:

```text
api-contracts/query-service/openapi.json
http://localhost:<docs-port>/specs/query-service/openapi.json
```

Users can:

- import the JSON file into Postman or Hoppscotch;
- import the served URL;
- download it from the unified Swagger portal;
- use it with OpenAPI-based mock, test, or code-generation tools.

Add smoke checks that parse every schema. Where practical, include a lightweight import/conversion check using a pinned OpenAPI/Postman converter, but do not commit user workspaces or secrets.

## Authentication and Environment Configuration

OpenAPI describes authentication but does not contain credentials.

- Define reusable security schemes such as bearer token, session cookie, or API key only where actually supported.
- Swagger `Try it out` uses development base URLs from configuration.
- CORS must explicitly allow the local docs portal origin in development.
- Do not persist authorization in Swagger UI by default.
- Production API docs are disabled by default or protected by the private operator boundary.
- Sanitized examples must not contain tokens, physical storage credentials, or sensitive market/account data.

## API Versioning

- Version browser-visible routes, beginning with `/api/v1`.
- Additive optional response fields may remain in v1.
- Removing fields, renaming fields, narrowing accepted values, changing types, changing requiredness incompatibly, or changing endpoint semantics requires a versioned migration.
- Deprecate before removal where practical.
- OpenAPI `info.version` identifies the service API release; it is independent from application build version and dataset schema versions.

## Compatibility Checks

Adopt a pinned OpenAPI diff tool or repository-owned compatibility checker. Compare each generated schema with the selected Git baseline.

At minimum, fail on:

- removed path or method;
- removed response property used by the contract;
- newly required request property;
- incompatible type or format change;
- removed enum value;
- incompatible response status/media type;
- changed security requirement that breaks existing clients.

Allow an explicit reviewed override for intentional versioned breaking changes. Do not silently update the baseline to make a failure disappear.

## Nx Targets

Per-service examples:

```text
nx run query-service:openapi
nx run query-service:openapi-check
nx run query-service:contract-breaking
```

Console examples:

```text
nx run omni-console:api-generate
nx run omni-console:api-check
nx run omni-console:typecheck
```

Portal examples:

```text
nx run api-docs:build
nx run api-docs:serve
nx run api-docs:test
```

Workspace commands:

```text
npm run api:generate
npm run api:check
npm run api:docs
```

The registry drives workspace commands so adding a service does not require copying orchestration logic.

## CI Pipeline

1. Validate the service registry.
2. Export every registered OpenAPI schema in a clean process.
3. Lint and validate each schema.
4. Compare generated schemas with tracked artifacts or regenerate twice and compare, according to the adopted artifact policy.
5. Run breaking-change checks against the Git baseline.
6. Generate TypeScript clients.
7. Fail if tracked generated clients are stale, or verify reproducibility if generated clients remain disposable build artifacts.
8. Type-check/build clients and Omni Console.
9. Build the unified Swagger portal.
10. Verify every portal URL resolves to valid OpenAPI JSON.
11. Run API contract and representative frontend integration tests.

## Artifact Policy

Recommended initial policy:

- Track normalized `api-contracts/<service>/openapi.json` because it is reviewable, directly importable, and useful for compatibility diffs.
- Track generated TypeScript clients only if developer/CI builds cannot reliably generate them before Console type-checking; otherwise keep them disposable and enforce deterministic generation.
- Whichever policy is selected, CI must detect stale or nondeterministic output.

Do not commit Swagger bundles copied from arbitrary CDNs. Pin dependencies and build the portal reproducibly.

## Query Service Metadata as First Consumer

The global metadata refactor introduces the first reusable API models:

- `DatasetSummaryResponse`;
- `DatasetMetadataResponse`;
- `PartitionKeyDefinition`;
- `PartitionSummary`;
- `PartitionListResponse`;
- `PartitionOptionsResponse`;
- `ResolvePartitionRequest` and `ResolvedPartitionResponse` without physical path disclosure;
- shared error response models.

Suggested endpoints:

```text
GET  /api/v1/metadata/datasets
GET  /api/v1/metadata/datasets/{dataset}
GET  /api/v1/metadata/datasets/{dataset}/partitions
GET  /api/v1/metadata/datasets/{dataset}/partition-options
POST /api/v1/metadata/datasets/{dataset}/resolve
```

Partition listing and options are bounded, typed, searchable where appropriate, and support dependent filters. Console generates controls from `partitionKeys` and imports generated DTO/client types.

## Adding a Future Python API

1. Define explicit FastAPI/Pydantic boundary DTOs.
2. Use versioned routes and stable operation IDs.
3. Add the service to `tools/api-contracts/registry.json`.
4. Run the workspace generation command.
5. Review the normalized OpenAPI diff.
6. Generate the service TypeScript client if Console consumes it.
7. Add the specification to the unified portal through registry generation.
8. Add authentication, contract, compatibility, and representative integration tests.
9. Document the service base URL and ownership.

No generator script or Swagger application should be copied into the new service.

## Testing

### Tooling tests

- Registry validation and path safety.
- Deterministic export.
- Duplicate operation ID rejection.
- Invalid OpenAPI rejection.
- Stable client generation.
- Stale-output detection.
- Breaking-change classification.
- Multiple service registration.
- Schema-name/path collision behavior for any optional merge.

### Service contract tests

- Runtime responses validate against declared DTOs.
- Declared examples validate against schemas.
- Authentication and error responses match OpenAPI.
- Internal fields are absent from public responses.
- Pagination, limits, enums, and formats are represented correctly.

### Console tests

- Generated clients compile under workspace TypeScript settings.
- Runtime configuration supplies the correct base URL.
- Request cancellation and typed errors work.
- View-model adapters handle optional additive fields.
- No handwritten duplicate transport DTOs remain for migrated APIs.

### Portal tests

- One Swagger UI loads all registered specifications.
- Service selector entries match the registry.
- Every specification URL returns valid JSON.
- `Try it out` targets configured development services.
- Authentication is not persisted unexpectedly.
- Download/import URLs work for Postman and Hoppscotch-compatible OpenAPI input.

## Security Requirements

- No credentials or tokens in schemas, examples, generated clients, portal configuration, or Git history.
- Do not expose internal-only endpoints merely for documentation completeness.
- Disable or protect docs outside approved environments.
- Bound request examples and generated mocks.
- Sanitize error schemas.
- Validate registry paths remain inside the repository.
- Pin generator, Swagger UI, validation, and diff tooling versions.
- Treat generated clients as untrusted build input until schemas pass validation.

## Implementation Increments

### Increment 1: Tooling foundation

- Add registry, deterministic exporter, validator, and normalized artifact layout.
- Register Query Service.
- Add Nx and CI schema checks.

### Increment 2: Generated Console client

- Pin the TypeScript generator.
- Generate Query Service models/client.
- Add runtime base URL adapter and replace duplicate handwritten transport types for the first migrated API.

### Increment 3: Unified developer portal

- Add static Swagger UI with registry-generated multi-spec configuration.
- Add schema downloads and discovery index.
- Add local Compose/Nx integration and development CORS documentation.

### Increment 4: Compatibility enforcement

- Add baseline OpenAPI diff checks and reviewed override workflow.
- Add reproducibility and import smoke tests.

### Increment 5: Additional backend services

- Register only real HTTP APIs.
- Normalize operation IDs, versioned routes, security, and explicit DTOs before exposing them.

## Acceptance Criteria

- One command exports all registered Python backend API contracts.
- Each service has a valid independently importable OpenAPI JSON document.
- Postman and Hoppscotch can import each document by file or development URL.
- One Swagger UI portal presents all registered backend APIs with a service selector.
- Omni Console uses generated TypeScript contracts/clients for migrated APIs.
- Future Python services can register without copying generation infrastructure.
- CI detects stale, invalid, nondeterministic, and incompatible contract changes.
- HTTP, protobuf, and persistence contract ownership remains distinct.
- Production documentation exposure follows secure defaults.

## Documentation Updates

Update together:

- `docs/architecture/001-system-overview.md`
- `docs/development/001-where-to-change.md`
- `docs/development/003-codex-control-and-tooling.md`
- `docs/README.md`
- `docs/INDEX.md`
- `libs/contracts/README.md` to clarify protobuf versus HTTP ownership
- Query Service and Omni Console READMEs
- root development commands and CI documentation
