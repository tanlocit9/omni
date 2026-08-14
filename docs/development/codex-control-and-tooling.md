# Codex Control and Tooling Plan

**Status:** Proposed  
**Assessment date:** 2026-08-14  
**Scope:** Codex skills, MCP integrations, and supporting developer-tool prerequisites for the Omni repository.

## Purpose

This document defines the smallest useful Codex extension set for operating Omni safely and consistently. It separates reusable workflow knowledge, which belongs in skills, from live tool or service access, which belongs in MCP or an existing connector.

The goal is stronger repository navigation, change-impact analysis, CI control, and roadmap execution without bypassing Omni's existing Nx entry points, contract ownership rules, review gates, or owner decisions.

## Current Baseline

Omni already has a strong control foundation:

- Nx is the canonical entry point for project operations.
- AGENTS.md and CLAUDE.md define repository-wide agent behavior.
- plans/roadmap/automation-rules.md defines selection, branch, draft-PR, verification, and stop rules.
- Java/Spring Platform, Python data services, Kafka, PostgreSQL, and MinIO/S3-compatible storage have explicit ownership boundaries.
- The connected GitHub integration can inspect repositories, pull requests, reviews, and GitHub Actions state.

The assessment also found these gaps:

1. AGENTS.md requires code-review-graph before unfamiliar code exploration and impact-sensitive changes.
2. Draft PR #9 records that code-review-graph tools were not exposed in its execution environment, so repository and contract analysis fell back to manual inspection.
3. The assessed Codex host reported no locally configured MCP servers.
4. The separate product vault can lag behind the canonical repository roadmap and implementation evidence.
5. Broad infrastructure MCP access would add mutation paths that are not governed by Omni's Nx targets or contract rules.

## Operating Principles

1. Use skills for repeatable procedures and repository-specific judgment.
2. Use MCP only for capabilities that require live structured access or persistent code intelligence.
3. Prefer repository-scoped configuration over global configuration.
4. Keep write-capable tools behind explicit approvals and narrow allow lists.
5. Treat GitHub, repository source, tests, and CI as implementation evidence; treat the product vault as a synchronized summary.
6. Do not add an MCP when existing Codex shell, Nx, or GitHub capabilities already provide the required control surface.
7. Do not merge automatically. Omni's roadmap automation leaves pull requests as drafts until acceptance criteria and CI pass.

## Recommended Controls

| Priority | Control | Type | Decision | Primary value |
| --- | --- | --- | --- | --- |
| 1 | code-review-graph | MCP plus local index | Install now | Semantic search, dependency tracing, impact radius, and post-change detection across Java and Python |
| 1 | Nx AI integration | Skills plus Nx MCP | Configure now | Workspace graph awareness, valid target discovery, affected-project analysis, Nx Cloud CI context, and task monitoring |
| 2 | omni-roadmap-operator | Repository skill | Create now | Execute the existing roadmap protocol consistently |
| 2 | omni-vault-sync | Repository skill | Create now | Detect and reconcile drift between canonical GitHub evidence and the product vault |
| 3 | security-threat-model | Curated skill | Install before portable deployment or Console exposure | Repository-grounded trust boundaries, assets, abuse paths, and mitigations |
| Later | Playwright | Curated skill | Add with the Omni Console phase | Browser-driven user-flow verification and screenshots |
| Later | Sentry | Curated skill | Add only after Sentry adoption | Read-only production error and health inspection |

## Immediate Control 1: code-review-graph

### Why

Omni's agent rules already require these graph operations:

- semantic search or graph queries before manually scanning unfamiliar implementation;
- impact-radius analysis before changing shared contracts, DTOs, events, storage paths, or configuration;
- change detection when reviewing or completing edits.

The current gap is therefore not a new architectural proposal. It is an unavailable dependency for an already accepted repository workflow.

### Setup boundary

- Install a real Python 3.10-or-newer tool runtime; use uv or an isolated tool environment rather than mixing the MCP server into an Omni application environment.
- Configure code-review-graph for Codex from the Omni repository checkout.
- Keep its graph database and generated local state out of source control unless the tool's documented integration explicitly requires a tracked file.
- Pin the adopted tool version after validation so scheduled runs do not change behavior unexpectedly.
- Build the graph from the repository root and verify Java, Python, SQL, and configuration coverage relevant to Omni.
- Ensure the server is available in the Codex environment that performs roadmap automation, not only in an editor-specific session.

### Required verification

The setup is accepted when Codex can:

1. locate a known Java scheduler class and a known Python Kafka consumer through semantic search;
2. trace the impact of a shared Kafka payload or storage-path change;
3. identify affected callers, producers, consumers, tests, and documentation;
4. run change detection against a branch or working-tree diff;
5. refresh the graph after edits without requiring a full manual rebuild.

## Immediate Control 2: Nx AI Integration

Omni uses Nx 22.7.8, so its native AI integration should be evaluated before adding a separate general-purpose workspace MCP.

Run the non-mutating configuration audit first:

- npx nx configure-ai-agents --check=all

Then configure Codex from a dedicated branch:

- npx nx configure-ai-agents --agents=codex

Review the generated diff carefully. The command may update AGENTS.md, CLAUDE.md, MCP configuration, or skill directories. Preserve Omni's existing PowerShell, contract, storage, roadmap, and verification rules. Generated generic guidance must not replace project-specific policy.

The intended result is:

- Nx workspace-exploration and task-execution skills;
- valid project and target discovery rather than invented commands;
- affected-project and dependency-graph awareness;
- Nx MCP connectivity for Nx Cloud CI, task monitoring, and current Nx documentation;
- continued use of nx run project:target as the execution boundary.

## Repository Skill: omni-roadmap-operator

Create the skill at:

.agents/skills/omni-roadmap-operator/SKILL.md

The skill should activate when the owner asks Codex to continue, implement, reconcile, or report on the Omni roadmap. It should reference canonical repository documents rather than duplicate their full content.

Required workflow:

1. Read plans/roadmap/README.md, implementation-increments.md, automation-rules.md, cross-phase-rules.md, and the selected phase file.
2. Reconcile main, existing branches, open pull requests, and CI before selecting work.
3. Continue in-progress or verification-pending work before selecting a new increment.
4. Refuse blocked, approval-required, manual, superseded, dependency-incomplete, or conflicting work.
5. Use code-review-graph before unfamiliar exploration and for impact-sensitive changes.
6. Inspect Nx project targets and run targeted checks before affected checks.
7. Use one branch and one draft pull request per increment.
8. Attempt implementation and attributable CI repair at most three times per scheduled run.
9. Never weaken tests, acceptance criteria, security checks, or contract guarantees to obtain a green result.
10. Produce the canonical daily implementation report and record evidence changes.
11. Stop for owner input at the conditions defined in automation-rules.md.
12. Never merge automatically.

## Repository Skill: omni-vault-sync

Create the skill at:

.agents/skills/omni-vault-sync/SKILL.md

This skill should activate when the owner asks to synchronize product notes, evidence, milestones, or public-sharing status.

Required workflow:

1. Read the latest default-branch commit, roadmap registry, execution log, merged pull requests, and successful CI evidence.
2. Compare those facts with the Omni product-vault Roadmap, Implementation Evidence, Milestones, and related summaries.
3. Report drift before writing.
4. Update only factual summaries and evidence links; do not move canonical technical decisions out of the repository.
5. Preserve the distinction between source present, locally verified, CI evidenced, and production verified.
6. Do not mark a milestone complete from source presence alone.
7. Update public-sharing drafts only after their milestone evidence changes.
8. Run link/path validation for changed vault documentation.

If the vault is not mounted or writable in the execution environment, the skill should emit a structured drift report rather than guessing or silently skipping synchronization.

## Existing GitHub Integration

The connected GitHub plugin already covers repository metadata, branches, pull requests, reviews, comments, and workflow inspection. Do not install a second general GitHub MCP unless a documented capability gap appears.

GitHub CLI remains a useful local prerequisite for workflows that explicitly depend on gh authentication or local branch-to-PR discovery, but it should complement rather than duplicate the connected GitHub integration.

## Deferred Skills

### security-threat-model

Install before Phase 5 portable deployment or Phase 6 Omni Console exposes additional trust boundaries. The threat model should cover:

- external market-data providers and licensing constraints;
- Platform APIs and future Console authentication;
- Kafka producer and consumer boundaries;
- PostgreSQL operational state and migrations;
- MinIO/S3-compatible datasets, manifests, and credentials;
- Telegram delivery;
- CI, dependency supply chain, generated protobuf output, and secrets.

### Playwright

Add when a browser-facing Omni Console exists. Use it to verify operator journeys such as job observation, dataset inspection, analysis execution, and failure recovery. It is not useful for the current backend-only product boundary.

### Sentry

Add only after Sentry is selected and instrumented. Until then, define observability through application logs, metrics, health checks, and existing runtime tools rather than installing an unused service-specific skill.

## Controls Not Recommended Now

Do not add the following by default:

- a second GitHub MCP;
- generic filesystem or shell MCP servers;
- write-capable PostgreSQL, Kafka, MinIO, or S3 administration MCPs;
- a Docker MCP gateway solely to execute commands already owned by Nx targets;
- production credentials in MCP configuration;
- tools that mutate roadmap state, merge pull requests, delete data, or run migrations without approval.

Direct data-plane or control-plane MCP access can bypass the repository's ownership rules. If runtime inspection later requires an MCP, prefer a purpose-built read-only server with:

- environment allow lists;
- read-only credentials;
- explicit project or database scope;
- tool allow lists;
- write approvals enabled;
- audit logging;
- no access to production secrets from untrusted branches.

## Project-Scoped Codex Configuration

Prefer a trusted, repository-scoped .codex/config.toml for Omni-specific MCP servers. Keep user-wide configuration limited to tools that genuinely apply to every repository.

For each server:

- set an explicit working directory;
- use a pinned command or package version;
- forward only required environment variables;
- enable only required tools;
- require startup when repository rules depend on the server;
- prompt for writes or disable write tools entirely;
- set bounded startup and tool timeouts;
- never store secrets directly in tracked configuration.

## Rollout Order

1. Work from an actual Omni checkout rather than the product-vault directory.
2. Install missing developer prerequisites required by the repository, including Docker for Compose-based local infrastructure and an isolated Python/uv tool runtime for code-review-graph.
3. Configure and verify code-review-graph.
4. Run the Nx AI configuration audit.
5. Apply Nx Codex integration on a review branch and reconcile generated guidance with existing rules.
6. Create and test omni-roadmap-operator.
7. Create and test omni-vault-sync.
8. Install security-threat-model before deployment exposure expands.
9. Add Playwright and Sentry only when their product phases and runtime dependencies exist.

## Acceptance Criteria

The tooling rollout is complete when:

- Codex lists code-review-graph and Nx MCP as enabled for the Omni project;
- graph tools work across representative Java and Python paths;
- Nx skills discover real projects and targets without bypassing Nx;
- generated agent guidance preserves all existing Omni-specific rules;
- a shared-contract test change produces an impact report and appropriate affected checks;
- Codex can inspect draft-PR CI and report failures with branch and commit identity;
- the roadmap operator selects only eligible work and always leaves a draft PR;
- the vault-sync skill detects a deliberately introduced roadmap mismatch;
- no duplicate GitHub integration or broad infrastructure write surface is introduced;
- no credentials are committed;
- installation and rollback instructions are documented.

## Risks and Mitigations

| Risk | Mitigation |
| --- | --- |
| Generated Nx guidance overwrites repository-specific rules | Configure on a branch, review the full diff, and preserve canonical Omni policy |
| Graph data becomes stale | Enable documented incremental refresh and verify status at session start |
| MCP tools expand mutation authority | Use project scope, tool allow lists, read-only credentials, and write approvals |
| Skills duplicate canonical roadmap prose | Link to canonical documents and keep skills procedural |
| Vault summaries become a second source of truth | Repository evidence always wins; sync after verified changes |
| Tool upgrades change automation behavior | Pin validated versions and upgrade through reviewable pull requests |
| Too many skills dilute triggering accuracy | Install only phase-relevant skills with narrow descriptions |

## References

- [Agent and Development Rules](../../AGENTS.md)
- [Roadmap Automation Rules](../../plans/roadmap/automation-rules.md)
- [Roadmap](../../plans/roadmap/README.md)
- [Draft PR #9](https://github.com/tanlocit9/omni/pull/9)
- [OpenAI Codex skills](https://developers.openai.com/codex/skills)
- [OpenAI Codex MCP](https://developers.openai.com/codex/mcp)
- [Nx AI setup](https://nx.dev/docs/getting-started/ai-setup)
- [Nx MCP reference](https://nx.dev/docs/reference/nx-mcp)
- [code-review-graph](https://code-review-graph.com/)
- [OpenAI curated skills](https://github.com/openai/skills/tree/main/skills/.curated)
