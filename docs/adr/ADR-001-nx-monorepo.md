# ADR-001: Use Nx Monorepo for Multi-service Development

## Status

Accepted

## Context

Omni contains multiple services and shared libraries across Java and Python. Developers need a consistent way to discover and run build, test, lint, format, serve, dependency, and packaging operations.

## Decision

Use Nx as the canonical workspace task runner and project graph boundary. Project operations should be exposed as Nx targets in each [`project.json`](../../project.json) or app/library-level project file.

## Consequences

- Developers run operations from the workspace root.
- Commands should use `nx run <project>:<target>` instead of underlying tools when a target exists.
- Each service owns its target definitions.
- CI and local workflows can share the same target names.
