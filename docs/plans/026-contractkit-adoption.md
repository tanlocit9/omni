# ContractKit Adoption for Omni

## Status

Deferred developer-platform adoption plan. This plan is not roadmap-scheduled and does not block Plan 024, Plan 025, or the current Proto3 migration.

Omni already owns working Buf-based Nx targets for format, lint, build, breaking checks, Java/Python generation, artifact tests, and deterministic generation checks. ContractKit adoption is justified only after an external release reproduces those guarantees without weakening the existing contract boundary.

`ContractKit`, package names, configuration schema, and registry adapters remain provisional until registry ownership and a separate source repository are confirmed.

## Goal

Adopt a generic contract-lifecycle CLI in Omni so Protobuf contracts can be formatted, validated, compatibility-checked, generated, verified, and optionally published through one workspace-agnostic interface.

Omni is the first reference consumer. Buf remains the Protobuf engine, Git remains the canonical schema source, Nx remains the task orchestrator, and Karapace remains an optional runtime registry rather than a build dependency.

## Outcome

After adoption:

- `contract-kit format`, `lint`, `breaking`, `generate`, and `verify` delegate to pinned Buf and generator tooling;
- the Nx adapter preserves the existing `contracts:* ` target names and output semantics;
- `contract-kit.toml` can declare explicit contract inputs and generated outputs, while workspace adapters can infer safe defaults when those paths are omitted;
- Java and Python output remains disposable under `libs/contracts/gen`;
- CI detects invalid, breaking, stale, or nondeterministic contract output;
- an optional Karapace adapter can check and register an approved schema without making Karapace the source of truth;
- the generic CLI remains usable without Nx or Omni.

## Existing Omni Baseline

Current canonical source:

```text
libs/contracts/proto/**/*.proto
```

Current disposable outputs:

```text
libs/contracts/gen/java
libs/contracts/gen/python
```

Current Nx targets:

```text
nx run contracts:format
nx run contracts:format-write
nx run contracts:lint
nx run contracts:generate
nx run contracts:generate-check
nx run contracts:breaking
nx run contracts:test
```

Current tooling already uses:

- `libs/contracts/buf.yaml`;
- `libs/contracts/buf.gen.yaml`;
- the pinned Buf CLI and `grpc-tools` from the npm lockfile;
- repository-owned generation, reproducibility, artifact, and Git-baseline wrappers.

ContractKit must not replace this baseline until it provides equal or stronger deterministic behavior and actionable failure output.

## Ownership Boundary

| Concern                                                  | Owner                                        |
| -------------------------------------------------------- | -------------------------------------------- |
| Canonical Kafka/service schemas                          | `libs/contracts/proto` in Git                |
| Contract lifecycle orchestration                         | ContractKit core                             |
| Protobuf compile, format, lint, breaking, and generation | Buf and pinned protoc plugins                |
| Nx target inference/execution                            | ContractKit Nx adapter                       |
| Generated Java/Python files                              | Disposable `libs/contracts/gen` output       |
| Producer/consumer behavior                               | Owning Omni applications                     |
| Contract compatibility policy                            | Omni contract configuration and CI           |
| Runtime schema IDs and registered versions               | Optional Karapace deployment                 |
| Generated package publication                            | Future Maven/PyPI/npm release workflow       |
| HTTP/OpenAPI lifecycle                                   | Plan 016 through a later ContractKit adapter |

ContractKit must not become a Protobuf compiler, runtime serializer, Kafka proxy, schema registry, business-message mapper, or replacement for Buf.

## Proposed Package Boundary

Initial package names:

```text
@tanlocit9/contract-kit
@tanlocit9/contract-kit-nx
```

Target generic commands:

```text
npx contract-kit format
npx contract-kit lint
npx contract-kit breaking --against main
npx contract-kit generate
npx contract-kit generate --input libs/contracts/proto --output java=libs/contracts/gen/java --output python=libs/contracts/gen/python
npx contract-kit verify
```

Target Nx commands remain stable:

```text
npx nx run contracts:format
npx nx run contracts:lint
npx nx run contracts:breaking
npx nx run contracts:generate
npx nx run contracts:generate-check
npx nx run contracts:test
```

Omni must consume pinned releases. Do not depend on a moving Git branch, unpublished local path, or unverified binary URL in the committed contract workflow.

## Configuration Contract

Proposed root configuration:

```toml
schema = 1

[workspace]
manager = "nx"

[[modules]]
name = "omni-kafka"
kind = "protobuf"
root = "libs/contracts"
source = "libs/contracts/proto"
buf_config = "libs/contracts/buf.yaml"
generation_config = "libs/contracts/buf.gen.yaml"

[modules.baseline]
provider = "git"
ref = "origin/main"
path = "libs/contracts"

[modules.outputs]
java = "libs/contracts/gen/java"
python = "libs/contracts/gen/python"

[modules.verification]
clean_generate = true
repeat_generate = true
compare_output_hashes = true

[registry]
provider = "none"
```

The exact schema belongs to ContractKit. Omni owns module paths, compatibility policy, output selection, and registry adoption. Paths must remain repository-relative and resolve inside the workspace.

### Input and output resolution

ContractKit must support both explicit paths and workspace-aware defaults.

Explicit configuration may select a file, directory, or glob and may declare one or more language outputs:

```toml
[[modules]]
name = "omni-kafka"
kind = "protobuf"
inputs = ["libs/contracts/proto/**/*.proto"]

[modules.outputs]
java = "libs/contracts/gen/java"
python = "libs/contracts/gen/python"
```

CLI overrides are intended for local experiments and CI diagnostics:

```text
npx contract-kit generate \
  --input libs/contracts/proto \
  --output java=libs/contracts/gen/java \
  --output python=libs/contracts/gen/python
```

A minimal Nx-aware module may omit physical paths:

```toml
[workspace]
manager = "nx"

[[modules]]
name = "omni-kafka"
kind = "protobuf"
project = "contracts"
```

The Nx adapter resolves the project through the Nx project graph, then reads the project root, source root, target metadata, and declared target outputs. ContractKit conventions may fill remaining values, for example `{projectRoot}/proto` for Protobuf input and `{projectRoot}/gen/{language}` for generated output.

Resolution precedence is deterministic:

1. CLI `--input` and `--output` overrides;
2. explicit module configuration;
3. workspace adapter metadata, including Nx project and target outputs;
4. ContractKit conventions.

Inferred values must be printed by `contract-kit inspect` and included in diagnostic output. ContractKit must fail when inference is ambiguous, the inferred input does not exist, multiple Nx projects match, an output overlaps handwritten source, or a resolved path escapes the workspace. It must not silently scan the entire repository or choose the first matching directory.

Explicit and inferred configurations that resolve to the same paths must produce the same cache key, generated tree, and verification result. Workspace inference is an adapter feature; the generic core remains usable with explicit paths and without Nx.

TypeScript generation is not required until a real TypeScript service or client consumes the Protobuf contract.

## Contract Lifecycle

### Format

- Check canonical formatting without mutation in CI.
- Provide an explicit write command for local use.
- Delegate formatting to Buf for Protobuf modules.

### Lint and build

- Run Buf lint using the committed module policy.
- Compile the complete module before generation.
- Return source locations and stable check identifiers where possible.

### Breaking changes

- Compare against the selected Git or registry baseline.
- Fail closed when a required baseline is unavailable.
- Never replace or update the baseline automatically to hide a failure.
- Support an explicit reviewed override only for a versioned migration.

### Generation

- Use committed, pinned generator configuration.
- Clean only declared output directories.
- Generate Java and Python outputs deterministically.
- Never modify handwritten sources or paths outside the workspace.

### Verification

- Generate from a clean state at least twice.
- Compare output hashes or trees.
- Validate expected artifacts and language-specific package layout.
- Fail when generated files are manually edited, incomplete, or nondeterministic.
- Keep generated output disposable unless a later artifact policy explicitly changes.

### Publication

Publication is deferred until a second repository or external consumer needs generated artifacts.

Future publication may include:

- canonical Buf module or schema bundle;
- Java artifact through Maven;
- Python package through PyPI;
- TypeScript package through npm;
- provenance tying every artifact to the same schema source commit.

Publishing generated language packages must not create multiple editable sources of truth.

## Nx Adoption

The Nx adapter should:

1. discover supported contract modules from committed configuration or explicit Nx project references;
2. infer project roots, source roots, and declared target outputs through the Nx project graph while rejecting ambiguous or unsafe paths;
3. create or configure cacheable targets only where resolved inputs and outputs are deterministic;
4. preserve Omni's existing target names during migration;
5. declare `libs/contracts/gen` as generated output;
6. include Buf config, resolved Proto inputs, generator configuration, lockfiles, and ContractKit configuration in task inputs;
7. avoid network access for local format, lint, build, generate, and deterministic verification;
8. avoid invoking Nx recursively from project-graph hooks;
9. support the repository's pinned Nx major version before installation;
10. keep the generic CLI usable without Nx.

Registry publication targets must remain explicit, non-cacheable, and separated from build/generation targets.

## ContractKit and Karapace

ContractKit and Karapace operate at different lifecycle boundaries:

| Boundary               | ContractKit                                                     | Karapace                                                      |
| ---------------------- | --------------------------------------------------------------- | ------------------------------------------------------------- |
| Primary phase          | Development and CI                                              | Runtime/deployment                                            |
| Canonical input        | Versioned schema files in Git                                   | Approved registered schema versions                           |
| Main responsibility    | Format, lint, breaking, generate, verify, publish orchestration | Schema IDs, subjects, versions, compatibility, runtime lookup |
| Availability           | Short-lived CLI                                                 | Long-running service                                          |
| Code generation        | Delegates to Buf/plugins                                        | Not the build owner                                           |
| Kafka data path        | No                                                              | Registry-aware serializers/consumers may query it             |
| Source-of-truth status | Git-facing orchestrator                                         | Derived runtime registry state                                |

The initial Omni provider is:

```toml
[registry]
provider = "none"
```

A future Karapace provider may support:

```text
contract-kit registry check --provider karapace --subject <subject>
contract-kit registry publish --provider karapace --subject <subject>
contract-kit registry verify --provider karapace --subject <subject>
```

Rules for future Karapace adoption:

- Git and `libs/contracts/proto` remain canonical;
- CI checks compatibility before registration;
- only an approved release workflow registers schemas;
- production applications must not create uncontrolled schema versions;
- subject naming and key/value strategy are explicit and reviewable;
- Karapace compatibility levels must not be weaker than the selected release policy;
- registry credentials are supplied at runtime and never stored in ContractKit config;
- Schema Registry and Kafka REST Proxy are separate capabilities; Omni does not adopt the REST Proxy merely to use schema registration;
- registry outage must not block offline formatting, linting, generation, or local tests.

Karapace remains deferred until independently deployed producers/consumers, multiple repositories, multiple teams, runtime schema discovery, or a concrete compatibility incident justifies the extra service.

## Future Schema Adapters

The initial adoption is Protobuf-only. Later adapters may include:

- OpenAPI for the Plan 016 HTTP/client workflow;
- AsyncAPI for event documentation and discovery;
- JSON Schema for non-Protobuf contracts;
- Avro where a real Kafka use case exists.

Adapters must preserve separate semantic ownership. ContractKit must not merge HTTP, Kafka, persistence, and object-storage manifests into one ambiguous schema.

## Dataset Outputs

No analytical dataset output.

## Metadata Outputs

No dataset metadata output.

## Algorithm Feature Outputs

No direct algorithm feature output.

## Algorithms Unlocked

No analytical algorithm is unlocked. The adoption makes cross-language transport evolution more deterministic and reduces generator/tooling duplication.

## Contract Impact

| Contract                           | Impact                                                                        |
| ---------------------------------- | ----------------------------------------------------------------------------- |
| Kafka/service-to-service protobuf  | No schema change from adoption; lifecycle commands become generic             |
| Object-storage JSON manifest       | None                                                                          |
| Storage path/dataset ownership     | None                                                                          |
| Public Java/Python API             | Generated package paths remain unchanged initially                            |
| Configuration/environment contract | Adds `contract-kit.toml`; optional registry credentials remain external       |
| Nx workspace contract              | Existing `contracts:* ` targets keep their names and semantics                |
| CI contract                        | Existing Buf/generation checks migrate only after parity proof                |
| Runtime registry contract          | None initially; future Karapace adoption requires its own activation decision |

This plan must not change field numbers, packages, topic mappings, payload semantics, or producer/consumer migration state by itself.

## Security Requirements

- Never print registry credentials, tokens, Kafka credentials, schema-registry authentication headers, or secret env values.
- Pin ContractKit, Buf, protoc plugins, and generated package versions.
- Validate every configured path remains inside the workspace.
- Treat schemas and generated code as untrusted build input until lint, build, and compatibility checks pass.
- Do not download plugins from ad-hoc URLs.
- Keep registry writes disabled unless an explicit publish command and approved credentials are present.
- Record schema source commit, module digest, generator versions, and output digest for publication.
- Do not embed production endpoints or credentials into generated SDKs.

## Adoption Increments

### Increment 1 — External package readiness

- Confirm the product name, npm scope, source repository, and supported platforms.
- Publish the generic CLI and Nx adapter independently from Omni.
- Provide a stable config schema, checksums/provenance, and version compatibility policy.
- Test against a disposable Protobuf workspace before Omni adoption.

### Increment 2 — Omni shadow verification

- Add ContractKit as a pinned development dependency without changing existing targets.
- Add `contract-kit.toml` mapped to the current Buf module; verify both explicit paths and Nx-inferred defaults resolve to the same inputs and outputs.
- Run ContractKit against recorded fixtures or an isolated workspace copy.
- Compare lint, breaking, generated tree, deterministic hashes, and exit behavior with current targets.
- Resolve parity gaps before changing the canonical path.

### Increment 3 — Nx target migration

- Install the Nx adapter.
- Replace repository-specific orchestration only where parity is proven.
- Preserve all existing `contracts:* ` commands.
- Preserve offline generation and disposable Java/Python output.
- Remove superseded wrappers only after no active command or CI workflow references them.

### Increment 4 — Optional package publication

- Activate only when another repository or independently versioned consumer exists.
- Publish generated artifacts from one approved schema commit.
- Verify Maven/PyPI/npm artifacts carry matching schema identity and version.
- Document consumer upgrade and rollback behavior.

### Increment 5 — Optional Karapace provider

- Activate through a separate owner decision.
- Define subject naming, compatibility level, authentication, availability, backup/recovery, and deployment ownership.
- Register schemas through CI/release, not uncontrolled application startup.
- Add registry compatibility and publish evidence without weakening local Buf checks.

## Migration and Rollback

Migration rules:

1. Keep existing scripts and Nx targets during shadow verification.
2. Compare ContractKit results against the current baseline.
3. Switch target implementations without changing user-facing target names.
4. Remove old wrappers only after CI and local documentation use ContractKit.
5. Keep one release where rollback restores the prior target commands without changing schemas or generated output paths.

Rollback must not delete Proto sources, change field numbers, rewrite generated output policy, or unregister runtime schemas.

## Verification

Required verification after implementation:

```text
npx contract-kit format
npx contract-kit lint
npx contract-kit breaking --against origin/main
npx contract-kit generate
npx contract-kit verify

npx nx run contracts:format
npx nx run contracts:lint
npx nx run contracts:breaking
npx nx run contracts:generate
npx nx run contracts:generate-check
npx nx run contracts:test
```

Also verify:

- invalid Proto syntax and lint violations return source locations;
- reused or incompatible fields fail the selected breaking policy;
- a missing required baseline fails closed;
- two clean generations produce identical Java/Python trees;
- generated output remains ignored and disposable;
- path traversal and output paths outside the workspace are rejected;
- the Nx adapter preserves exit codes, inputs, outputs, and target names;
- explicit paths and Nx-inferred defaults resolve to the same cache key and generated tree;
- ambiguous, missing, overlapping, or out-of-workspace paths fail before generation;
- `contract-kit inspect` reports the final resolved inputs, outputs, and resolution source;
- offline checks do not contact Karapace or another registry;
- a future Karapace provider checks compatibility before registration and never prints credentials.

These commands were not run for this documentation-only plan.

## Repository Guidance Updates

Implementation must review and update where applicable:

```text
AGENTS.md
CLAUDE.md
.roo/rules/
README.md
docs/README.md
docs/INDEX.md
docs/data/001-kafka-contracts.md
docs/development/001-where-to-change.md
docs/plans/002-cross-service-protobuf-contracts.md
docs/plans/016-shared-api-contract-and-unified-openapi.md
libs/contracts/README.md
```

This planning change updates the documentation registries and links the OpenAPI plan. Runtime, contract, and agent guidance changes only when adoption begins.

## Acceptance Criteria

- Omni consumes pinned released ContractKit core and Nx adapter packages.
- Existing Buf-based contract guarantees have documented parity evidence.
- `libs/contracts/proto` remains the single canonical Protobuf source.
- Existing `contracts:* ` commands and output paths remain stable during adoption.
- Java/Python generation is deterministic and disposable.
- Breaking checks fail closed when their required baseline is unavailable.
- The generic CLI accepts explicit input/output paths and works without Nx.
- The Nx adapter can infer safe defaults without duplicating the engine, and explicit configuration always overrides inference.
- Ambiguous or unsafe path inference fails before files are written.
- Karapace remains optional and is never an implicit local or CI dependency.
- No contract schema, topic mapping, or runtime message semantics changes solely because of adoption.
- Documentation and agent guidance are synchronized when implementation begins.

## Non-Goals

- Reimplementing Buf, protoc, language generators, or Schema Registry.
- Migrating existing JSON producers/consumers merely to adopt tooling.
- Adding TypeScript generation without an actual consumer.
- Deploying Karapace or Kafka REST Proxy in the initial adoption.
- Publishing generated artifacts before a second consumer exists.
- Making ContractKit a prerequisite for Plan 024, Polycheck, data sync, analysis, notifications, or deployment.
