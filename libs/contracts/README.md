# Omni Cross-Service Contracts

This Nx project owns Omni's versioned Protocol Buffer schemas for Kafka and other service-to-service boundaries.

## Source and generated output

- Canonical schemas: `proto/**/*.proto`
- Generated Java: `gen/java`
- Generated Python: `gen/python`

Generated files are committed so Java and Python packaging can consume deterministic artifacts in later migration increments. Never edit them manually.

The repository pins both the Buf CLI and the local `protoc` distribution in `package-lock.json`. Generation does not upload schemas to a remote plugin service.

## Commands

Run all commands from the repository root:

```text
nx run contracts:format
nx run contracts:lint
nx run contracts:test
nx run contracts:breaking
nx run contracts:generate
nx run contracts:generate-check
```

`breaking` prefers the `origin/main:libs/contracts` baseline. During this relocation, it falls back to `origin/main:contracts` until the new path is merged; if neither baseline exists, it compiles the current module as a bootstrap validation.

This increment defines contracts only. Existing JSON Kafka producers and consumers are migrated through adapters and compatibility tests in later increments.
