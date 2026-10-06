---
name: verify-increment
description: Verify an Omni increment through cross-service blast-radius reconciliation, attributable executed-test coverage, and its complete tools/check_result.py gate; optionally record explicit owner attestation without overstating CI, deployment, runtime, or completion.
---

# Verify Increment

Use this skill when the owner says they personally reviewed a passing verification
gate and wants that ownership recorded as `owner_verified`.

This skill does not run checks unless the owner separately authorizes the exact
commands under [`AGENTS.md`](../../../AGENTS.md). It never converts owner attestation
into CI, merge, deployment, production, provider, or live-runtime evidence.

## Required Inputs

Obtain:

1. increment or shared verification ID;
2. verifier identity containing only letters, numbers, dots, underscores, or hyphens;
3. explicit owner confirmation that they reviewed the passing gate;
4. target roadmap increment IDs when a shared verification ID covers multiple
   increments; and
5. increment-specific blast-radius evidence identifying changed and directly impacted
   files/symbols across all applicable services and boundaries; and
6. coverage evidence mapping every changed or directly impacted feature, acceptance
   criterion, and safety-critical branch to executed tests and relevant source symbols.

Do not infer verifier identity from Git configuration, environment variables, account
names, or prior messages.

## Required Workflow

### 1. Confirm the gate is passing

Read only the compact conclusion:

```text
python tools/check_result.py conclusion --increment <ID>
```

Continue only when it returns `PASS`. Stop on `FAIL`, `INCOMPLETE`, or `INVALID`.
Do not remove or weaken required checks to make attestation possible.

### 2. Prove blast radius and increment-specific code coverage

Before requesting owner approval:

1. run code-review-graph impact analysis for the increment's implemented source files;
2. reconcile the bounded graph result against canonical documentation for Platform,
   Analyzer, Ingestor, Query Service, Console, shared contracts/libraries,
   persistence, configuration, tests, and operations; and
3. build a concise matrix containing:

```text
feature/criterion -> changed or impacted source files/symbols -> executed tests -> line/branch coverage -> passing check
```

Approval is allowed only when this matrix demonstrates that the passing recorded gate
actually executed tests covering all changed and directly impacted behavior,
including success, failure, retry, boundary, compatibility, concurrency, transaction,
authorization, READY-last, lineage, and data-loss-prevention paths when those paths are
in scope. A graph hop limit must not be used as proof that a cross-language Kafka,
HTTP, storage, generated-contract, or persistence boundary has no impact.

Source presence, test-file presence, test names, compilation, broad unrelated suite
success, static graph reachability, commit/PR/merge/CI metadata, or an unexecuted
coverage report are not coverage evidence. When the project exposes a coverage
target/report, it must be included in the required `check_result` gate and each
increment-relevant critical component must meet its documented threshold, defaulting
to at least 80% line and 80% branch coverage. Aggregate project coverage is
insufficient. When no instrumentation target exists, executed focused tests may
establish behavioral coverage only if each changed and directly impacted source path
and acceptance criterion is mapped explicitly; the instrumentation limitation remains
recorded and absence of this mapping blocks attestation.

For a shared verification ID, produce a separate coverage matrix for each target
increment. A shared test/build pass cannot attest an increment whose owned changes are
not exercised by that run.

### 3. Ask for explicit attestation

The owner must explicitly approve this exact semantic statement:

```text
I reviewed the passing verification gate and mark it owner_verified.
```

The CLI requires the confirmation token `I-VERIFIED` so accidental conversational
approval cannot create an attestation.

### 4. Record owner attestation

Run:

```text
python tools/check_result.py attest --increment <ID> --verified-by <identity> --confirm I-VERIFIED
```

The tool writes ignored local evidence under
`.agent/check-results/<ID>/attestation.json`, including:

- increment ID;
- `owner_verified` status;
- verifier identity;
- attestation timestamp;
- hash of the exact compact summary;
- hash of the required-check manifest.

Never hand-edit this file.

### 5. Validate before consuming

Read the attestation only through:

```text
python tools/check_result.py attestation --increment <ID>
```

A changed requirement manifest, changed result/log, non-passing latest gate, malformed
identity, or summary mismatch makes the attestation invalid.

### 6. Update evidence conservatively

When requested, record compact wording in the relevant phase file and execution log:

```text
Owner attestation: OWNER_VERIFIED <ID> verified_by=<identity>; bound to the passing
check_result summary and required-check manifest.
```

For one shared verification ID, name every covered roadmap increment explicitly and
verify that the same command scope genuinely applies to each one.

Roadmap evidence must name or link the increment-specific coverage matrix; never record
`owner_verified` from the compact PASS line alone.

Owner attestation may satisfy a documented owner-review requirement. It does not by
itself satisfy or waive:

- missing blast-radius reconciliation or attributable coverage;
- missing required checks;
- acceptance criteria;
- dependency completion;
- documentation synchronization;
- migration, integration, deployment, provider, or live-runtime evidence; or
- any manual production gate.

Commit, PR, merge, and CI identifiers remain traceability evidence unless the
increment explicitly declares them as delivery acceptance criteria. They never replace
impact or coverage evidence.

Do not mark an increment `completed` unless every remaining roadmap completion gate is
independently satisfied.

### 7. Documentation synchronization

If roadmap evidence changes, load `update-implementation-plans`, update the minimum
canonical surfaces, run post-edit code-review-graph change detection, and invoke
`verify-document-consistency` before claiming synchronization.

## Stop Conditions

Stop when:

- the gate is not `PASS`;
- blast-radius analysis or cross-service reconciliation is missing or uses bounded
  graph hops as proof of no cross-language impact;
- increment-specific coverage evidence or its feature/source/test mapping is missing,
  incomplete, not executed, or does not cover changed, impacted, and safety-critical branches;
- a coverage target exists but was omitted from the required gate or failed its
  documented threshold;
- verifier identity or explicit confirmation is missing;
- required checks changed after the pass;
- latest verification evidence changed after attestation;
- the shared gate does not genuinely cover a target increment;
- the owner asks attestation to masquerade as CI/live evidence; or
- completion still depends on unresolved roadmap gates.

## Completion Report

Report:

```text
Attestation: OWNER_VERIFIED <ID>
Verified by: <identity>
Bound evidence: summary hash + required manifest hash
Coverage evidence: <feature/source/test matrix and coverage report when available>
Roadmap evidence: <updated paths or none>
Increment status: <status and remaining gates>
```
