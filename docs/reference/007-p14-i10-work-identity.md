# I10 canonical work identity — owner revision 2026-11-03

## Goal

Use the existing graph atomicWorkId consistently for work readiness and causal
input bindings. It hashes JobType, WorkType, workKey, businessDate and canonical
parameters through WorkExpansion.identity / atomic_work_id. No new identity hash
or I11 scope is invented. The registry owner-decision flag is now false because
the owner explicitly resolved this identity direction; acceptance is not waived. WorkIdentity(type,key) remains a legacy routing tuple,
not the identity of a dated graph work.

## Outcome and boundaries

- A logical version reference is (atomicWorkId,dataVersion); the content digest
  is not required to become another identity hash.
- atomicWorkId identifies a logical work; atomicAttemptId/executionId identify a
  particular run; dataVersion identifies its output content evidence. A retry does
  not change the work identity, and a different day/key/parameter set does.
- Dataset/partition identifies physical output ownership and catalog discovery.
  It must still serialize writers sharing a file; it is not a work READY key.
- Work lineage follows frozen atomic_work_dependencies and the approved input
  tuple (atomicWorkId, businessDate, atomicAttemptId, dataset, partition, dataVersion).
  Do not flatten every ancestor into the child's causal lineage.
- The v1 output.inputs fingerprint still describes the persisted file artifact;
  it is separate compatibility/integrity evidence, not graph work lineage. The
  earlier 128-history-links/paging proposal is not an approved work-lineage design.
  Artifact payload bounds remain fail-closed; normalizing that retained v1 format
  is still a verification/cutover concern, not authorization for paging.
- Canonical writes still overwrite existing paths. Existing optional physical
  byte snapshots remain storage evidence under the prior flag; this change does
  not make a snapshot filename or an entire history file into a work identity.
  Enrollment's existing snapshot flag prerequisite is not removed here.

## Source and persistence

V23 is a new candidate forward migration; V18-V22 are unchanged. output_work_ready
is keyed by (atomic_work_id,dataset,partition_values); multiple outputs remain
attributable to one work. Generation monotonicity applies within that work key.
The older partition pointer remains a projection of the current physical version.
Only previously promoted pointers are carried forward; no synthetic work/date or
attempt is created for NULL-provenance historical baselines.

output_work_ready_catalog exposes the exact identity/attempt/date/version only
when the physical partition projection still has that version. Recursive frozen
upstream approval checks prevent a replaced upstream attempt/version from leaving
a descendant falsely READY. A physical overwrite/version replacement can therefore
make older work unreadable; no per-row hash or automatic rebind is invented.
Execution SUCCESS remains immutable history; it is not perpetual work READY.
This conservative v1 file-fingerprint limitation remains explicit, especially for
multiple symbols sharing signal history. A missing READY identity waits for
explicitly valid upstream work; it never silently substitutes the newest partition.

OutputReadinessStore promotes both work evidence and the physical projection in
the existing completion transaction. OutputWorkReadiness.bind resolves approvals
only from the graph's accepted upstream attempts, with exact dataset/partition and
version equality. Missing/ambiguous/replaced bindings return waiting with
UPSTREAM_IDENTITY_NOT_READY for enrolled work. Legacy unenrolled work retains its
explicit manifest boundary. SchedulerOutboxService and JobService serialize the
same DatasetRef.approvedInput shape; publication retries preserve first approval.

The private GET /api/v1/jobs/output-readiness/work?atomicWorkId=work:... returns
matching output evidence, approvedInputs and frozen upstreamWorks. Reader bearer
tokens may GET this exact route; reconciler tokens cannot, and reader tokens cannot
POST it. The old paged catalog remains partition discovery, never proof that an
arbitrary work/date succeeded. Python approved_input_version requires the complete
work/date/attempt tuple and SHA-256 version for enrolled bindings; partial identity
is rejected. Unenrolled legacy bindings stay compatible.

## Matched deployment boundary

Drain previously enrolled queued commands before installing strict identity readers.
Their first approvedInputs payload is immutable; legacy partition/version approvals
must not be patched into invented graph identities. Deploy matching Platform and
shared Python code, and review V23/retained checksums before any rollout. Enable
READY promotion with enrollment for new supported chains so upstream identity
proofs exist before dependent work is admitted. A validated historical file/baseline
alone cannot satisfy a missing work identity; prepare a legitimate new work attempt
when needed. Do not repair the ledger, automatically replay failed work, or turn on
reader cutover without the complete separate gate. These are documented steps,
not executed deployment actions.

## Contract impact and blast radius

| Owner | Impact / concrete boundary |
| --- | --- |
| Platform | Work-keyed READY persistence, exact frozen upstream approvals, additive authenticated work lookup and unified approval serialization. |
| Analyzer | Shared approval validation now checks complete upstream identity for enrolled indicator/signal reads. Handler ownership and calculations unchanged. |
| Ingestor | Existing atomicWorkId registration stays authoritative; EOD normally has no analytical approved inputs. Shared validation remains compatible with legacy commands. |
| Query Service | Partition catalog is discovery for ad-hoc queries, not atomic-work readiness. Existing public SQL/catalog DTOs unchanged; byte/version checks remain. |
| Console | No new public UI or browser secret; still uses existing Query Service DTOs. |
| Shared contracts/library | Existing Kafka registration already carries canonical work/attempt/date and validates against frozen execution metadata. No new protobuf field, physical path in Kafka, or generated-source edit. Approval metadata is additive for legacy commands and mandatory-complete for enrolled inputs. |
| Persistence | Candidate V23; no retained migration, ledger repair or production data change. Baselines cannot invent work identities. |
| Config/operations | No flag enabled. Deploy matched Platform/shared Python code and review V23 before opting in. Enrolled dependencies require promoted upstream identity evidence; baselines or manifest rows alone cannot satisfy them. Existing drain, timeout ownership, cutover and rollback gates remain. |

Graph analysis before edits: readiness/provider/shared approval scope found 26
impacted nodes/four files; DatasetRef/registry/dispatch/JobService scope found 84
nodes/four files at depth two. These bounded reports do not prove cross-language
isolation. The manual matrix above covers the missing boundaries.

## Verification

New gate: P14-I10-identity-20261103. No earlier passing result is reused for changed
source. Focused Java/Python cases cover canonical date/key/parameter separation,
legacy boundary, missing/ambiguous identity rejection, exact identity serialization,
private-token scopes and forward-migrated PostgreSQL work lookups. Existing tests
exercise duplicate status/registration, generation guards and retained upgrade.

| Criterion | Source / executed test mapping |
| --- | --- |
| Canonical identity and input serialization | WorkExpansion.identity, DatasetRef; OutputWorkReadinessTest and DatasetRefIdentityTest (date/attempt separation, immutable coordinates, legacy wire). |
| Frozen upstream binding, missing/ambiguous work | OutputWorkReadiness.bind; OutputWorkReadinessTest; full Platform suite. |
| Real promotion/work lookup and NULL baseline exclusion | V23/OutputReadinessStore/OutputWorkReadiness.read; GraphWorkRequestsPostgresTest.baselineHasNullProvenanceAndCannotReplaceRegisteredReady. |
| Auth scopes | TrustedOperatorHeaderFilter; OutputReadinessTokenTest. |
| Worker identity/date/attempt/version validation | approved_inputs; test_approved_inputs plus affected worker suites. |
| Fresh and retained forward schema | SchedulerOutboxIdentityMigrationPostgresTest now expects V23. |

Fresh Python/contract results: py-common:coverage PASS 343 passed / 5 opt-in Kafka
skips; analyzer:coverage PASS 242; ingestor:coverage PASS 94; query-service:test
PASS 40 on isolated rerun; contracts:test PASS canonical generation/build and all
11 expected artifacts. Java full coverage PASS: 599 entries / 4 opt-in skips / zero failures or errors.
A separate focused admission/value-object/token run PASS: 24 entries, no skips.
Do not sum these overlapping runs. The backend identity/upgrade focused run also
passed seven cases; it is not the missing worker-to-HTTP integration.
The five Kafka skips do not establish real-broker identity round-trip evidence.

Fresh results are recorded when available below. The initial focused run failed
when the candidate V23 was edited while its disposable database test was active;
Flyway correctly rejected the checksum on restart. A subsequent full run caught
a retained-upgrade test still expecting six migrations after V16; V23 makes seven.
The expectation is corrected without changing migration checksums/history.
Final V23 is rerun on fresh
disposable databases, never repaired. Query Service initially hit its existing
audit-event timing race under parallel load; isolated rerun passed 40 tests. No
production Query Service change or deterministic lifecycle guarantee is claimed.
Full worker-to-API identity round trip, all-source attributable coverage, artifact
fingerprint compatibility and coordinated cutover remain separate gates. I10 is
in_progress, not owner_verified/completed.

### Attributable identity coverage

The full Java report was preserved before focused runs as
`.agent/check-results/P14-I10-identity-20261103/artifacts/platform-full-jacoco.xml`.
The focused report is `identity-admission-jacoco.xml` in the same ignored directory.
Production source did not change between these runs; only targeted tests were added.
The existing threshold helpers check each component against its executed report;
no merged/aggregate coverage is fabricated. No equivalent increment-specific Nx
threshold target exists, so the repository helpers are invoked directly.

| Source | Line | Branch | Executed report |
| --- | ---: | ---: | --- |
| OutputWorkReadiness | 100% | 91.67% | Full Java |
| OutputReadinessStore | 100% | 80% | Full Java |
| JobService | 95.10% | 87.72% | Full Java |
| SchedulerOutboxService | 99.07% | 90% | Full Java |
| DatasetRef | 100% | 93.75% | Focused admission |
| PlatformDependencyRegistry | 100% | 97.50% | Focused admission |
| TrustedOperatorHeaderFilter | 100% | 82.14% | Focused admission |
| OutputReadinessController | 100% | N/A, no branches | Full Java |
| Python approved_inputs | 93.94% | 87.50% | py-common coverage.xml |

The controller has no BRANCH counter; its line gate is checked separately, not
represented as an 80% branch pass. SQL has no numeric coverage instrumentation;
the PostgreSQL cases prove identity lookup, baseline exclusion, missing upstream
proof invalidation, missing physical projection invalidation/re-promotion and the
V16-to-V23 retained fixture upgrade. Unit registry cases cover ready identity,
missing identity, date holes, unresolved writers, signal component inputs, schema
incompatibility and malformed accepted coordinates.

Compact conclusion: `INCOMPLETE P14-I10-identity-20261103
check=integration/identity-roundtrip source=none`. All six unit/contract targets and
the identity coverage check pass. Full real-worker/HTTP identity integration is
not run; older whole-I10 coverage/cutover gates are not waived. No owner attestation,
production migration, flag activation, deployment or automatic repair was performed.

## Documentation reconciliation

Plan 030, registry, roadmap, indexes, release notes, ADR-008, architecture, execution
flow, Kafka/data-lake/database, deployment, developer ownership, four service guides
and AGENTS point to this owner revision. No phase dependency, schema owner,
analytical algorithm, provider credential, P12 writer, recovery journal, tracing or
I11 registration is added. CLAUDE/Roo/governance already require shared identity,
contract review and verification; no policy change is needed. Historical handoffs
004-006 and their measurements remain history, superseded only for this identity
scope. Static documentation consistency cannot replace missing runtime acceptance.

Static audit: the canonical P14-I10 row remains unique and in_progress after
completed I9; only the resolved owner-decision flag changes. Plan 030 retains all
ten mandatory sections. Reference/007 is indexed as current, 006 remains historical
for this revised identity scope, and directly changed relative links resolve to
existing files. Contract/ownership and executed/not-run wording are consistent in
the touched scope. Post-edit graph analysis is repeated at delivery; flows are
skipped and its heuristic gaps are not executed coverage. This audit does not
convert the incomplete integration gate into increment completion.
