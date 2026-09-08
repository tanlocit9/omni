---
name: manual-trigger-metadata
description: Safely discover and manually trigger Omni's existing SYNC_METADATA job through the Platform operator API, optionally targeting one supported dataset or exact partition, then poll the audited trigger and execution to a terminal state.
---

# Manual Trigger Metadata

Use this skill when the owner asks to manually synchronize or republish metadata
manifests through the existing Platform job API.

## Boundaries

- Use only the Platform operator API under `/api/v1/jobs`; never publish directly to
  Kafka, call Analyzer internals, edit job state, or bypass scheduler claims and
  dependency guards.
- Never print, log, or persist credentials, cookies, tokens, or secret headers.
- Do not modify the manual-trigger allow-list without explicit owner authorization.
- Do not trigger production unless the owner explicitly identifies the environment as
  production and authorizes the trigger.
- A trigger is an operational write. Before sending it, show the resolved Platform URL,
  definition ID/name, target scope, and reason, then obtain explicit owner approval.
- Generate a new idempotency key for each intended operation. Reuse the same key only
  when retrying the same request after an uncertain client/network result.
- Do not stage, commit, push, or update roadmap status while using this skill.

## Required Inputs

Obtain these values before triggering:

1. Platform base URL, defaulting to `http://localhost:8080` only for an explicitly local
   environment.
2. Trusted operator UUID expected by the local proxy/security boundary. For local
   development, resolve `SYSTEM_OPERATOR_UUID`; its default is
   `b252fe62-80f3-4df9-9734-5dc549705a25`.
3. Scope: `FULL`, `DATASET`, or `EXACT`.
4. For `DATASET`, one of `eod`, `indicators`, or `signals`.
5. For `EXACT`, every required partition key.
6. A concise audit reason.
7. Explicit owner approval for the displayed trigger request.

The trusted identity header is `X-Omni-User`. Its value must be a valid UUID because
Platform maps the authenticated principal through `UUID.fromString` for JPA auditing.
Reject aliases such as `local-admin`; do not invent an operator identity. In local
development, use the configured `SYSTEM_OPERATOR_UUID` or its documented default.

## Platform Prerequisite

Manual triggering is secure by default. The active metadata definition must be allowed
by either its UUID or stable identity in:

```text
app.scheduler.manual-trigger.allow-list
```

The stable allow-list identity is:

```text
SYNC_METADATA:<SOURCE>
```

For the Analyzer metadata definition this is normally:

```text
SYNC_METADATA:ANALYZER
```

Confirm the catalog reports `manualTriggerAllowed: true`. If it is false, stop and
report the allow-list requirement. Do not change configuration automatically.

## Supported Targets

### Full synchronization

Use an empty parameters object:

```json
{
  "parameters": {}
}
```

### Dataset synchronization

```json
{
  "parameters": {
    "dataset": "signals"
  }
}
```

Supported datasets are `eod`, `indicators`, and `signals`.

### Exact EOD partition

```json
{
  "parameters": {
    "dataset": "eod",
    "partition": {
      "exchange": "hose",
      "code": "hpg"
    }
  }
}
```

### Exact indicator partition

```json
{
  "parameters": {
    "dataset": "indicators",
    "partition": {
      "source": "ad_close",
      "timeframe": "1d",
      "exchange": "hose",
      "code": "hpg"
    }
  }
}
```

### Exact signal partition

```json
{
  "parameters": {
    "dataset": "signals",
    "partition": {
      "strategy": "confirmed_trend_equals",
      "timeframe": "1d",
      "exchange": "hose"
    }
  }
}
```

Platform lowercases dataset and partition values. Exact key sets are mandatory; extra
or missing keys are rejected.

## Workflow

### 1. Discover the job definition

Request active metadata definitions from:

```text
GET /api/v1/jobs/definitions?jobType=SYNC_METADATA&active=true&page=0&size=25
```

Use the trusted operator identity header required by the environment. Select exactly
one intended active definition. If zero or multiple plausible definitions exist, stop
and ask the owner to select one.

Fetch its detail:

```text
GET /api/v1/jobs/definitions/{definitionId}
```

Confirm:

- job type is `SYNC_METADATA`;
- definition is active;
- `manualTriggerAllowed` is true;
- source/environment matches the owner's request.

### 2. Build the audited request

Use an idempotency key containing only letters, digits, `.`, `_`, `:`, or `-`, with a
maximum of 128 characters. A useful form is:

```text
metadata-<scope>-<UTC timestamp>
```

The request body is:

```json
{
  "idempotencyKey": "metadata-signals-20260907T151700Z",
  "reason": "Republish signal metadata after lineage validation fix",
  "parameters": {
    "dataset": "signals"
  }
}
```

Show the non-secret request summary and obtain explicit owner approval immediately
before POSTing.

### 3. Submit through Platform

```text
POST /api/v1/jobs/definitions/{definitionId}/triggers
```

Interpret the response conservatively:

- `ACCEPTED`: capture `requestId` and `executionId`, then poll.
- `BLOCKED`: report the sanitized dependency reason; do not bypass it.
- `CONFLICT`: another scheduler/operator owns the job, the definition is inactive, or
  the allow-list denies it. Do not retry rapidly.
- `FAILED`: report the sanitized error and stop.
- `duplicate: true`: this idempotency key was already accepted; poll the returned
  request instead of creating another trigger.

### 4. Poll bounded status

Poll no faster than every two seconds:

```text
GET /api/v1/jobs/triggers/{requestId}
```

Stop after five minutes unless the owner approves a longer wait. Once an execution ID
exists, the execution may also be queried at:

```text
GET /api/v1/jobs/executions/{executionId}
```

Terminal execution outcomes are reported exactly as returned. Do not treat an accepted
trigger as a successful metadata synchronization.

### 5. Report the result

Report only:

```text
Environment: <local/non-production/production>
Definition: <name and UUID>
Scope: <FULL, DATASET value, or exact partition>
Trigger: <state, requestId, duplicate flag>
Execution: <executionId and terminal status, or timeout>
Result: <sanitized error/block reason or concise success metadata>
```

Do not reproduce stack traces unless the owner asks for diagnosis.

## PowerShell Reference

Use PowerShell's `Invoke-RestMethod` on Windows. Keep identity values in process-local
variables and do not echo them:

```powershell
$baseUrl = 'http://localhost:8080'
$operatorUuid = if ($env:SYSTEM_OPERATOR_UUID) {
  $env:SYSTEM_OPERATOR_UUID
} else {
  'b252fe62-80f3-4df9-9734-5dc549705a25'
}
$headers = @{ 'X-Omni-User' = $operatorUuid }

$catalog = Invoke-RestMethod `
  -Uri "$baseUrl/api/v1/jobs/definitions?jobType=SYNC_METADATA&active=true&page=0&size=25" `
  -Headers $headers
```

After owner approval:

```powershell
$body = @{
  idempotencyKey = 'metadata-signals-20260907T151700Z'
  reason = 'Republish signal metadata after lineage validation fix'
  parameters = @{ dataset = 'signals' }
} | ConvertTo-Json -Depth 5

$trigger = Invoke-RestMethod `
  -Method Post `
  -Uri "$baseUrl/api/v1/jobs/definitions/$definitionId/triggers" `
  -Headers $headers `
  -ContentType 'application/json' `
  -Body $body
```

Do not copy example idempotency timestamps unchanged. Resolve the operator UUID and
idempotency key for the current environment and operation.

## Stop Conditions

Stop and ask the owner when:

- the environment or trusted identity boundary is unclear;
- the request would target production without explicit approval;
- no unique active `SYNC_METADATA` definition can be selected;
- manual triggering is not allowed;
- the requested dataset or partition keys are unsupported;
- the trigger is blocked, conflicting, or failed;
- polling exceeds five minutes;
- direct Kafka publication, force, bypass, cancellation, or secret disclosure would be
  required.
