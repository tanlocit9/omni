# I10 versioned physical storage

November 11: [automatic expiry is owner-deferred](../technical-debt/018-execution-watchdog-observation.md)
and disabled during observation. Existing timed-out history and late-writer safety
remain; this decision does not promote old outputs or resolve conflicting completion.

November 10 [unified completion](018-i10-worker-completion.md) embeds registration/status
in one record for new commands. Existing physical safety rules remain; the two-message
ordering below describes legacy commands.

Owner approved this storage-safety scope on 2026-11-09. Implementation and local
verification are in progress; retained-environment migration, bucket changes and
reader activation have not been performed. I10 remains `in_progress`.

### Terminal completion replay audit (owner-approved November 11)

The owner approved terminal replay handling for SUCCESS and FAILED, not only
watchdog timeouts, and allowed adding its schema to V26. A READ ONLY local Flyway
inspection found version 25 with no V26 storage tables. V26 source now adds
output_completion_rejections; no retained migration was executed. Environments
that have already applied a different V26 need a forward migration, not checksum repair.

WorkerCompletionStore validates the wire, server declaration and sealed work/attempt
identity under the execution lock before deciding on terminal replay. The exception
applies only to VERSIONED contracts and the unified completion protocol:

- SUCCESS plus the same logical registration is an acknowledged duplicate. A new
  physical version for identical rows does not replace the first accepted coordinate.
- SUCCESS plus different logical output or a late ERROR records TERMINAL_SUCCESS_CONFLICT.
- FAILED plus a valid late completion records TERMINAL_FAILED_LATE. No accepted
  registration, SUCCESS or READY is created for that failed execution.

Rejected payloads retain event_id, execution_id, atomic_attempt_id, terminal_status,
reason, raw UTF-8 payload (at most 256 KiB), payload_sha256 and created_at. Primary
key (event_id,payload_sha256) deduplicates identical replay. The immutable-history
trigger prevents UPDATE/DELETE. Audit and classification use the existing execution
transaction; listener success/ACK follows commit. Audit/DB failure propagates for retry.

Malformed/foreign/declaration-invalid records cannot take this shortcut. CANONICAL
and legacy registration-only messages retain their existing strict handling; this
is not a general DLT, automatic replay service, retry redesign or recovery journal.
The previous timeout-only audit proposal is superseded. PostgreSQL tests cover
deduplication, evidence retention, transaction failure and races; real late-PUT and
subsequent-rerun E2E passes 14/14 in the
[November 11 closure checkpoint](016-i10-signals-backfill-closure.md#terminal-replay-checkpoint-november-11).

## Identity and ordering

### Frozen attempts across refresh

Owner-approved November 11: V26 records output_work_ready_history only inside the
actual VERSIONED READY promotion transaction, after current row ownership is
established. The immutable registration/output reference retains promotion evidence;
registration or terminal SUCCESS alone does not create history. History insertion
failure rolls back READY, storage head, status and registration with completion.

GET /api/v1/jobs/output-readiness/work keeps its latest/current semantics when
atomicAttemptId is absent. With the optional UUID selector, it resolves the exact
work/attempt through output_work_ready_attempt_catalog: current proofs plus actual
historical VERSIONED promotions. Frozen dependency binding uses the same catalog.
Workers send the frozen atomicAttemptId and still compare work/day/attempt/version
and validate exact immutable Parquet bytes; newer output is never substituted.
Superseded mutable CANONICAL history is not certified by this extension. Deploy the
amended V26 and matched Platform/shared-reader code together. See
[review and executed evidence](019-i10-review-20261111.md).

Logical paths, atomicWorkId, atomicAttemptId and row-based dataVersion are unchanged.
An optional RegisteredOutput.storageVersionId (protobuf field 12) identifies the
immutable backend version at the configured canonical key. It is a physical
coordinate, not a path, work identity, whole-file content version or READY claim.
New work does not require a separate whole-file snapshot before enrollment.

The default-off Platform flag is
APP_OUTPUT_REGISTRATION_VERSIONED_STORAGE_ENABLED. Matched workers implement the
protocol per persisted command, including after a configuration change:

1. Enrollment freezes storage_policy=VERSIONED in the completion contract.
2. A successful outbox claim freezes each logical output's accepted storage head in
   output_storage_write_bases, once per execution. Replays retain the same bases.
   The envelope carries outputCompletion.storagePolicy and storageBases; each base
   contains dataset, partition, storageVersionId and generation, never an object path.
3. Workers read history and merge against the frozen version. They overlay only
   their declared rows and preserve other dates/symbols/models. A cold null base
   permits an absent object; an existing unpinned object requires an explicit baseline.
4. PUT requires an already enabled, non-excluded versioned bucket. Workers capture
   the PUT result's version ID, read back that exact version, validate the declared
   rows, ACK registration and then ACK terminal status. Replace/copy uses the same
   versioned final publication and validates staged bytes before PUT.
5. Platform advances the physical head and logical row/work READY only after validated
   registration plus SUCCESS, under the existing partition lock. Head generations
   only advance. Late failed/timed-out attempts cannot advance a head or reopen work.
6. Private work proofs and paged discovery carry the accepted storageVersionId.
   Python/Query fetch exact versions and verify each approved row fingerprint/schema.
   Multi-work partitions combine individually validated rows. Physical IDs are excluded
   from composite logical version hashes. Missing versions never fall back to mutable GET.

Retry may upload identical rows into a different physical version. Registration
keeps the first persisted physical coordinate when both old and incoming coordinates
exist and output positions/logical identities match. Immutable registration
comparisons still reject logical differences; terminal unified VERSIONED completion
uses the audited rejection above. This is not a
worker recovery journal; an orphaned upload does not become READY automatically.

A prior timeout stops blocking a replacement only when both contracts are VERSIONED.
Legacy/mixed-policy uncertain writers remain blocked. Pending/running owners still
serialize; SUCCESS must finish physical promotion before the next merge is admitted.
Valid-empty outputs do not wait for a nonexistent physical head.
Once a partition has a physical head, enrollment rejects a CANONICAL writer with
CANONICAL_WORK_ON_VERSIONED_PARTITION before durable dispatch intent commits. Turning
the flag off is not permission to downgrade an already pinned partition.

## Migration and existing files

[V26](../../database/migrations/V26__versioned_output_storage.sql) adds the frozen
contract policy, immutable write bases and monotonic output_storage_heads. Existing
contracts default to CANONICAL. No prior migration is rewritten. Install the complete
migration sequence before this runtime; intermediate versions are not runtime cutovers.

Existing canonical files require a physical baseline before strict versioned readers
are enabled. This preserves historical unknown provenance and does not create work
READY or rewrite registrations. The operator-only helper is
py_common.storage.output_reconciliation.OutputStorageBaselineCoordinator.pin:

```python
await coordinator.pin(
    "eod", {"exchange": "hose", "code": "hpg"},
    allow_unproven_history=True,
)
```

Construct it with matched settings, a PostgreSQL metadata reader and ParquetStorage.
The explicit historical-container approval is required even when some registered
rows exist; it never claims provenance for the rest of the file. Use it only in a
drained maintenance window, with strict versioning still off for the initial scan.
It obtains the existing fence and readiness revision, validates all available
accepted proofs against canonical bytes, checks scan stability, clones those exact
bytes into a backend version, verifies exact-version read-back and commits through
POST /api/v1/jobs/output-readiness/backfill/storage. Reader and reconcile tokens have
separate route scopes; do not put token values into scripts or documentation.

The commit rejects a changed writer fence/revision, active/uncertain mutators,
unpromoted successful outputs, unsupported coordinates or a conflicting baseline.
It records checksum/size and the current maximum completion generation. The fence
sequence and completion generation are separate counters. A legacy proof may use
only the immutable baseline coordinate, and only at/below that baseline generation;
it never borrows the latest mutable head. Physical pinning is idempotent and cannot
replace an already versioned head. Re-running after a lost commit response requires
reviewing the existing baseline rather than blindly cloning another version.

## Shadow comparison of registered rows

Analyzer and Query pass their existing ParquetStorage validator to the shared
metadata-reader factory. Shadow catalog reads retain manifest authority. Enrolled
worker input reads use the frozen PostgreSQL work-proof route in shadow mode as
well as manifest mode; they do not fall back to discovery JSON.

For scoped READY, shadow comparison validates both the legacy whole-file bytes and
the exact approved PostgreSQL versions, then compares registered row coordinates,
columns and input-version membership. Inventory differences, missing rows, invalid
bytes or lineage differences cannot report a match. Unregistered history is retained
but is not certified by a WORK_ROWS_V1 comparison. A metadata-only comparison is
labelled PARTITION_METADATA and cannot substitute for this byte-level gate.

The disposable physical-baseline E2E passed on November 10: the real private API
pinned EOD/Indicators without changing logical versions or registrations, compared
two registered rows against larger legacy files, and completed versioned Signals
using actual workers and Query. This finite fixture is not retained-inventory parity
or a global reader-cutover approval. The November 11 terminal audit E2E checkpoint
supersedes the historical late-registration blocker.

## Paired rollout and rollback

Prepare a concrete reviewed inventory and backups before retained operations:

1. Pause producer schedules and acceptance, drain commands/workers and resolve legacy
   uncertain ownership. Retain execution/registration history and Kafka offsets.
2. Apply V26 through Flyway; retain before/after history and catalog evidence.
3. Configure bucket versioning and least-privilege GetObjectVersion/PutObject access
   externally. Runtime checks configuration; it never enables versioning itself.
   Retain every version referenced by registrations, heads, baselines or frozen bases.
   This change does not authorize lifecycle expiry, deletion, GC or Object Lock setup.
4. Pin and validate existing affected files under the fenced procedure. Compare the
   affected metadata/row inventory; do not equate a physical clone with logical READY.
5. Deploy matched Platform, Ingestor, Analyzer, py-common and Query adapters, then
   enable the versioned flag and exercise a bounded Signals request before expansion.
   Enrolled work uses its explicit PostgreSQL route while default manifest discovery
   can remain in place. Global reader cutover still requires its separate parity gate;
   unsupported producer/dataset protocols do not gain support from this flag.
6. Roll back only after draining/stopping versioned writers, restoring accepted pinned
   bytes to canonical keys and reconciling discovery. Keep all post-cutover registration
   history. Flipping flags or serving stale JSON alone is not a safe rollback, because
   an old worker may still replace the mutable latest version.

No live migration, versioning change, retention policy or global reader switch is
authorized/executed merely by the disposable regression runs.

The [November 11 drained canonical-repair rehearsal](019-i10-review-20261111.md#continuation-drain-and-canonical-repair)
passes in the full 14-case disposable integration target. It stops owned workers,
restores three canonical partitions from accepted immutable versions, preserves
registration/READY history and Query results, and rejects mutable writer downgrade
with HTTP 503. PostgreSQL authority stays enabled throughout; this is not proof
of old-binary compatibility, manifest-authority rollback or retained inventory parity.

## Impact and evidence boundary

Platform owns enrollment, claim bases, completion/READY, migration and authenticated
physical baseline API. Ingestor changes the initial EOD-history read. Analyzer uses
the shared captured output/history and frozen input readers. Query uses the shared
validated buffer; Console receives discovery through the existing Query API without
new physical coordinates in its UI contract. Contracts add one optional field;
shared Python owns port/model/merge/readback behavior. Configuration and operations
require the paired deployment and backend-version permissions above. Dataset owners,
topic names, logical paths, algorithms and P12 concurrency/batching remain unchanged.

Graph impact is bounded/truncated and is navigation evidence; direct reconciliation
includes all services above. Local command results and the critical source/test matrix
are tracked in [Signals closure](016-i10-signals-backfill-closure.md). No retained
environment or production-provider acceptance is inferred from local tests.
