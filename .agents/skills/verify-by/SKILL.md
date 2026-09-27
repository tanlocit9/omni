---
name: verify-by
description: Record an explicit owner attestation for an Omni increment only after its complete tools/check_result.py gate passes and attributable code coverage proves the increment's changed features and code paths are exercised; bind the attestation to exact evidence without overstating CI, deployment, runtime, or completion.
---

# Verify By

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
5. increment-specific coverage evidence mapping every changed feature, acceptance
   criterion, and safety-critical branch to executed tests and the relevant source
   files/symbols.

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

### 2. Prove increment-specific code coverage

Before requesting owner approval, inspect the increment scope, changed source, tests,
and acceptance criteria. Build a concise coverage matrix containing:

```text
feature/criterion -> source files or symbols -> executed test names -> passing check
```

Approval is allowed only when this matrix demonstrates that the passing recorded gate
actually executed tests covering all changed behavior owned by the increment,
including success, failure, retry, boundary, compatibility, and concurrency paths when
those paths are in scope.

Source presence, test-file presence, test names, compilation, broad unrelated suite
success, static graph reachability, or an unexecuted coverage report are not coverage
evidence. When the project exposes a coverage target/report, it must be included in the
required `check_result` gate and its increment-relevant report must pass the documented
threshold. When no instrumentation target exists, executed focused tests may establish
behavioral code coverage only if each changed source path and acceptance criterion is
mapped explicitly; absence of this mapping blocks attestation.

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

- missing required checks;
- acceptance criteria;
- dependency completion;
- documentation synchronization;
- increment-owned commit or PR;
- exact-head CI;
- migration, integration, deployment, provider, or live-runtime evidence; or
- any manual production gate.

Do not mark an increment `completed` unless every remaining roadmap completion gate is
independently satisfied.

### 7. Documentation synchronization

If roadmap evidence changes, load `update-implementation-plans`, update the minimum
canonical surfaces, run post-edit code-review-graph change detection, and invoke
`verify-document-consistency` before claiming synchronization.

## Stop Conditions

Stop when:

- the gate is not `PASS`;
- increment-specific coverage evidence or its feature/source/test mapping is missing,
  incomplete, not executed, or does not cover safety-critical branches;
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
