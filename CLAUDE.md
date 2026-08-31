<!-- nx configuration start-->
<!-- Leave the start & end comments to automatically receive updates. -->

# General Guidelines for working with Nx

- Prefer Nx targets over underlying tools for repository operations.
- Use Nx workspace/project MCP tools when available to inspect architecture,
  project details, configuration errors, and current Nx documentation.
- Check `node_modules/@nx/<plugin>/PLUGIN.md` for plugin-specific guidance when
  present.

<!-- nx configuration end-->

# Omni repository guidance

[`AGENTS.md`](AGENTS.md) is the canonical repository-wide agent policy. Follow its
workflow, verification approval gate, Nx boundary, contract/data guardrails, and
repository boundaries without duplicating them here.

Use `code-review-graph` before manual exploration, for impact analysis of shared
contracts, and for post-edit change detection as specified in [`AGENTS.md`](AGENTS.md).

Canonical navigation:

- [`docs/README.md`](docs/README.md) — documentation index
- [`docs/development/001-where-to-change.md`](docs/development/001-where-to-change.md) — ownership
- [`docs/governance/001-implementation-plan-standard.md`](docs/governance/001-implementation-plan-standard.md) — plan rules
- [`plans/roadmap/automation-rules.md`](plans/roadmap/automation-rules.md) — roadmap automation

Do not execute build, test, lint, format, affected checks, or equivalent tools
without the explicit request or command approval required by [`AGENTS.md`](AGENTS.md).
