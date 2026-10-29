# Omni Cross-Service Contracts

Current P14 status (2026-10-30): **I8 verification_pending; I9 in_progress**.
I9.a adds read-only graph/atomic planning; it does not acquire or dispatch work.
Common preparation now persists atomic dated work and sends the additive
`jobType`/`businessDate`/`parameters`/`atomicWorkId` envelope. V16 protects accepted
snapshots; matching Java/Python rollout is required even on the legacy scheduler.
Consumers preserve other dates and retain lookback; catalog/metadata are current
snapshots only. Writer and shared ancestors remain deferred. EOD-to-indicator
execution binding is implemented; broader version/concurrency guarantees remain
deferred.
The original I8 delivery ran no checks; later verification and repair evidence
is recorded in Plan 030. This supersedes earlier design-only I8/I9 statements.
See [contract, impact matrix and local handoff](../../docs/plans/030-static-graph-dispatch-planner.md#i8-source-delivery-and-local-verification-handoff--2026-10-29).

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

`breaking` requires `CONTRACTS_BASE_SHA` to contain a full, nonzero commit SHA.
CI supplies the PR base SHA or the previous push SHA; manual dispatch requires an
explicit baseline. For local use, resolve the intended previous revision first
and set that environment variable before running the target. The check looks for
`libs/contracts` and then the historical `contracts` directory at that exact SHA.
A missing commit or module fails the check; schema compilation is never reported
as compatibility validation. The target is uncached because the baseline changes
independently of the working tree. See the [CI policy](../../docs/development/004-ci-quality-gate.md).

This increment defines contracts only. Existing JSON Kafka producers and consumers are migrated through adapters and compatibility tests in later increments.
