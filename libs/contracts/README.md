# Omni Cross-Service Contracts

Owner identity revision (2026-11-03): enrolled work READY and direct upstream
approvals use canonical atomicWorkId (type/key/day/parameters), with attempt and
output version kept separate. Partition pointers remain physical/catalog projections;
they cannot substitute for work identity. Candidate V23 and the private work lookup
are documented in the [current identity handoff](../../docs/reference/007-p14-i10-work-identity.md). I10 remains in_progress;
old source/checkpoint statements below are historical for this revised scope.

I10 READY integration (2026-11-02): **in_progress; local checks recorded, release gate unresolved**. [Current source, baseline decision and release gates](../../docs/reference/006-p14-i10-ready-integration.md). Enrollment/worker registration, PostgreSQL READY/provider/readers, fenced baseline backfill and shadow/reconciliation now have opt-in source. Exact-date admission defers missing inputs; failed attempts remain terminal. Runtime remains manifest-authoritative by default; no flags/deployment or retained migrations were activated. Durable worker recovery, tracing and P12 remain deferred.

I10 checkpoint (2026-11-01): `in_progress`; [shadow registration source and remaining gates](../../docs/reference/004-p14-i10-registration-checkpoint.md). Source is unverified, consumer disabled by default, worker handlers not connected. Manifest READY remains authoritative; no watchdog/cutover/tracing activation. Earlier pending statements describe the pre-implementation baseline.

Current local acceptance (2026-11-01): **I8/I9 completed**. Canonical proto is
unchanged; pinned generation/artifact checks are recorded in the
[closure evidence](../../database/diagnostics/p14/v2/i8-i9-closure-20261101.md).
I10 remains planned/pending, and current runtime transport remains JSON.

Historical P14 source delivery (2026-10-30): **I8/I9 verification_pending**.
I9.a/b/c adds opt-in JobType graph preparation, independent atomic JobRuns with
shared in-flight attempts and exact dependency barriers, plus reversible scheduled
preset consolidation in the existing definitions catalog. V17/V18 are forward-only;
existing V1 paths and I8 wire/worker semantics remain. New read-only authenticated
work diagnostics are additive; writer and broader output/version exclusion remain
deferred. Prior indicator approval/byte-binding evidence remains historical.
No verification ran for this continuation; defaults remain unactivated. See
[full contract, impact and review handoff](../../docs/plans/030-static-graph-dispatch-planner.md#i9-full-source-delivery-and-review-handoff--2026-10-30).

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

## I10 paired private HTTP fixture

`fixtures/output-readiness-page-v1.json` is shared by Platform
`OutputReadinessProviderTest` and py-common `test_output_readiness`. It freezes the
private readiness page shape, exact coverage, snapshot/canonical identity and NULL
historical provenance. Pages allow at most 200 items and 4 MiB UTF-8 bytes;
`nextOffset` equals request offset plus returned item count. Byte-bounded pages may
be shorter than the requested limit. Keep revision fixed across pages and fail
closed on invalid/non-progress cursors. This fixture is an internal HTTP JSON
contract, separate from Kafka/protobuf; it does not change registration bounds.
`contracts:test` also requires generated OutputRegistrationEvent.java and
output_registration_pb2.py. Never hand-edit generated artifacts.
