# Polycheck Adoption for Omni

## Status

Deferred developer-tooling adoption plan. This plan is not roadmap-scheduled, does not block Plan 024, and does not authorize building Polycheck inside the Omni repository.

`Polycheck`, the npm package names, and the public release process remain provisional until registry ownership and a separate source repository are confirmed.

## Goal

Adopt a generic repository-readiness CLI in Omni so a developer or CI job can identify missing or inconsistent local prerequisites before starting the polyglot workspace.

Omni is the first reference consumer. Polycheck remains workspace-agnostic; Nx is its first adapter rather than a core assumption.

## Outcome

After adoption:

- `polycheck doctor` checks the local environment without mutating the machine;
- `polycheck doctor --json` emits a stable machine-readable result;
- the Nx adapter exposes a workspace target such as `nx run workspace:doctor`;
- `polycheck.toml` records Omni's required runtimes, tools, files, ports, and local infrastructure checks;
- mise remains responsible for installing and pinning user-space runtimes;
- every failed check returns evidence and an actionable remediation command without printing secrets.

## Ownership Boundary

| Concern                                     | Owner                                   |
| ------------------------------------------- | --------------------------------------- |
| Generic check engine and result schema      | Polycheck core                          |
| Nx target/generator integration             | Polycheck Nx adapter                    |
| Runtime installation and version activation | mise                                    |
| Java build                                  | Gradle Wrapper                          |
| Python dependency sync                      | uv through existing Nx targets          |
| Node/Nx dependency sync                     | npm workspace                           |
| Local infrastructure lifecycle              | Omni Nx targets and Docker Compose      |
| Omni-specific requirements                  | `polycheck.toml` in Omni                |
| Business/runtime health                     | Individual Omni services; not Polycheck |

Polycheck must not become a build system, language-version manager, Docker orchestrator, secret manager, or replacement for Nx.

## Proposed Package Boundary

Initial package names:

```text
@tanlocit9/polycheck
@tanlocit9/polycheck-nx
```

Target commands:

```text
npx polycheck doctor
npx polycheck doctor --json
npx nx run workspace:doctor
```

The Rust binary is distributed through the npm wrapper. Platform-specific binaries may use optional packages, but consumers install only the public CLI package and adapter.

Omni must consume a pinned released version. Do not depend on an unpublished local path, a moving Git branch, or an unverified download URL in the committed onboarding flow.

## Configuration Contract

Proposed root configuration:

```toml
schema = 1

[workspace]
manager = "nx"

[tools.node]
version = ">=22 <23"

[tools.java]
version = "21"
home_env = "JAVA_HOME"

[tools.python]
version = "3.14.5"

[tools.uv]
required = true

[tools.git]
required = true

[services.docker]
required = true
compose = ">=2"
daemon = true

[ports]
required_free = [5432, 9092, 9000, 9001, 5050, 8080, 8000, 8001, 8002, 5173]

[files]
required = ["nx.json", "package-lock.json", ".env.example"]
```

The exact schema belongs to Polycheck. Omni owns only its configuration values and must pin the supported schema version.

## Doctor MVP Checks

The first adopted version must be read-only and check:

- OS, architecture, shell, PATH resolution, and Windows native versus WSL2 mode;
- executable path and version for Git, Docker, Compose, Node, npm, Java, Python, uv, and the Gradle Wrapper;
- `JAVA_HOME` versus the resolved Java executable and JDK major version;
- Python executable selection, `.python-version`, virtual-environment state, and lock consistency;
- local Nx version and whether the project graph can be created;
- Docker daemon availability, Linux-container mode, Compose v2, and actionable resource warnings;
- required env files and required variable names without returning their values;
- Git submodule initialization;
- configured local port conflicts;
- static Compose configuration validity;
- stable human and JSON output with deterministic exit codes.

## Exit Contract

| Exit code | Meaning                                           |
| --------: | ------------------------------------------------- |
|       `0` | Ready for the selected workspace mode             |
|       `1` | Supported environment but remediation is required |
|       `2` | Invalid configuration or unsupported environment  |
|       `3` | Polycheck failed unexpectedly                     |

Check identifiers must remain stable enough for CI annotations and editor integrations.

## Nx Adoption

The Nx adapter should:

1. add or infer a non-cacheable workspace `doctor` target;
2. pass the workspace root and selected Polycheck configuration to the native CLI;
3. preserve the Polycheck exit code;
4. avoid recursively starting Nx from project-graph hooks;
5. keep project-graph discovery deterministic and lightweight;
6. support the repository's pinned Nx major version before installation;
7. provide an init generator only for config scaffolding and target registration.

The generic CLI must still work without Nx.

## Future Check Packs

The following extensions are explicitly post-adoption and must not enlarge the first Omni increment:

- format checks through repository-owned Prettier, Ruff, Spotless, and rustfmt commands;
- `.gitignore`, lockfile, submodule, and accidentally tracked-secret checks;
- Dockerfile and Compose policy checks;
- GitHub Actions syntax, permissions, action pinning, and secret-boundary checks;
- CI/CD target coverage and dependency checks;
- safe deterministic fixes through an explicit `fix --safe` command;
- Turborepo, pnpm workspace, Bazel, or other workspace adapters.

Polycheck should call established tools and normalize their findings rather than reimplementing formatters, compilers, or security scanners.

## Setup Boundary

Polycheck may later expose a setup plan, but installation remains provider-based:

```text
polycheck setup --dry-run
polycheck setup --provider mise
```

Rules:

- `doctor` never mutates;
- setup displays the complete plan before mutation;
- Node, Java, Python, and uv installation delegates to mise;
- Docker, Git, WSL2 features, firewall rules, OS packages, and system services require explicit system-install approval;
- existing env files and secrets are never overwritten;
- infrastructure startup remains a separate explicit Omni command;
- the second setup run must be idempotent.

## Dataset Outputs

No analytical dataset output.

## Metadata Outputs

No dataset metadata output.

## Algorithm Feature Outputs

No direct algorithm feature output.

## Algorithms Unlocked

No analytical algorithm is unlocked. The adoption reduces onboarding and local-environment ambiguity for later development work.

## Contract Impact

| Contract                           | Impact                                                         |
| ---------------------------------- | -------------------------------------------------------------- |
| Kafka/service-to-service protobuf  | None                                                           |
| Object-storage JSON manifest       | None                                                           |
| Storage path/dataset ownership     | None                                                           |
| Public Java/Python API             | None                                                           |
| Configuration/environment contract | Adds `polycheck.toml` and a stable doctor result/exit contract |
| Nx workspace contract              | Adds a workspace doctor target through the adapter             |
| CI contract                        | Optional read-only doctor gate after local adoption is proven  |

This plan must not add physical storage paths or business-routing fields to transport contracts.

## Security Requirements

- Never print env values, tokens, passwords, credentials, signed URLs, or full secret-bearing files.
- Redact values using repository secret classification and conservative name matching.
- Do not upload diagnostic data or enable telemetry by default.
- Pin package and binary versions; verify release integrity through the selected distribution path.
- Treat external command output as untrusted before including it in structured results.
- Require explicit approval for every system-level change.
- Keep doctor usable offline after dependencies are installed.

## Implementation Increments

### Increment 1 — External package readiness

- Confirm the product name and npm scope.
- Publish the generic Rust CLI and Nx adapter from their own source repository.
- Provide checksums/provenance and supported platform metadata.
- Prove installation in a disposable Nx workspace before Omni adoption.

### Increment 2 — Omni configuration

- Add pinned Polycheck packages to root dev dependencies.
- Add `polycheck.toml`.
- Register the workspace doctor target.
- Map current Java, Python, Node, Docker, file, env-name, submodule, and port requirements.
- Keep mise as the runtime provider.

### Increment 3 — Local onboarding

- Update root README and local setup documentation.
- Make doctor the first documented onboarding command.
- Keep dependency synchronization and infrastructure startup as explicit later commands.
- Record a clean-machine Windows/WSL2 or Linux result.

### Increment 4 — Optional CI gate

- Run JSON doctor checks only for CI-relevant requirements.
- Exclude developer-only daemon, local port, and interactive checks from CI mode.
- Surface stable check IDs and remediation without secrets.
- Do not add the gate until local false positives are resolved.

## Verification

Required verification after implementation:

```text
npx polycheck doctor
npx polycheck doctor --json
npx nx run workspace:doctor
npx nx show project workspace --json
```

Also verify:

- missing Java, mismatched `JAVA_HOME`, missing uv, unavailable Docker, occupied ports, missing env files, and uninitialized submodules produce the expected stable findings;
- JSON output contains no secret values;
- the Nx target preserves exit codes;
- repeated doctor runs are read-only and deterministic;
- package installation resolves the correct platform binary;
- Windows/WSL2 and Linux behavior follow the declared support matrix.

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
docs/development/001-where-to-change.md
docs/technical-debt/004-post-mvp-roadmap-work.md
```

This planning change updates the documentation registries and links the existing local-developer setup debt. Runtime guidance changes only when adoption begins.

## Acceptance Criteria

- Omni consumes a pinned released Polycheck CLI and Nx adapter.
- `polycheck.toml` contains no Omni business logic or secrets.
- A fresh supported machine receives actionable prerequisite results before applications start.
- Human and JSON outputs agree on status and exit code.
- The Nx target works without making the generic CLI Nx-dependent.
- mise remains the runtime installation owner.
- No existing env file, global runtime configuration, or system package is changed by doctor.
- Plan 024 remains independently implementable.
- Required documentation and agent guidance are synchronized when implementation begins.

## Non-Goals

- Building Polycheck inside the Omni repository.
- Publishing Polycheck as part of the Omni release.
- Replacing Nx, mise, uv, npm, Gradle, Docker Compose, or existing linters.
- Automatically fixing repository or CI/CD policy in the first increment.
- Making Polycheck adoption a prerequisite for correlation logging, data sync, analysis, notifications, or deployment.
