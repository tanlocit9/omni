# Omni Cross-Service Contracts

This Nx project owns Omni's versioned Protocol Buffer schemas for Kafka and other service-to-service boundaries.

## Source and generated output

- Canonical schemas: `proto/**/*.proto`
- Generated Java build output: `gen/java`
- Generated Python build output: `gen/python`

Generated files are disposable, ignored build artifacts and must not be committed or edited manually. `nx run contracts:generate` recreates both language trees from the canonical schemas. `nx run contracts:test` depends on generation automatically, and consumer build/package targets must depend on `contracts:generate` when they begin compiling these types.

The repository pins the Buf CLI and `grpc-tools` compiler in `package-lock.json`. Generation runs locally through the repository-owned wrapper and Buf's `protoc_builtin` Java/Python generators. It requires no remote generation service; output is reproducible from a clean dependency installation.

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

`generate-check` performs two clean generations and fails if their output hashes differ. It compares generated trees directly without relying on a repository snapshot of derived output.

`breaking` prefers the `origin/main:libs/contracts` baseline. During this relocation, it falls back to `origin/main:contracts` until the new path is merged; if neither baseline exists, it compiles the current module as a bootstrap validation.

This increment defines contracts only. Existing JSON Kafka producers and consumers are migrated through adapters and compatibility tests in later increments.
