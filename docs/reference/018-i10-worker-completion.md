# I10 unified worker completion

November 11 terminal-replay amendment: [V26 audit handling](017-i10-versioned-storage.md#terminal-completion-replay-audit-owner-approved-november-11)
preserves terminal VERSIONED attempts and acknowledges valid late completion only
after durable audit. Same-output SUCCESS replay is a no-op. The earlier no-new-table
statement describes the November 10 envelope change, not this later approved amendment.

The November 11 regression supersedes the historical 13/14 result below: real
worker-chain E2E passes 14/14 and full Platform coverage has 907 cases, zero failures
and 13 skips. Retained cutover remains open; see the
[source/test checkpoint](016-i10-signals-backfill-closure.md#terminal-replay-checkpoint-november-11).

Owner revision November 11: [TD-018](../technical-debt/018-execution-watchdog-observation.md)
parks automatic expiry during observation, with the watchdog flag explicitly false.
It is no longer a current I10 acceptance gate. The old full Metadata scan duration
is not a blocker for this PostgreSQL-readiness observation scope; reconciliation
and reader-cutover safety remain. Historical timing/expiry descriptions below
explain retained source and evidence, not an instruction to enable it.

Owner decision (2026-11-10): combine terminal status and output registration into
one Kafka record, then revisit the remaining I10 blockers. Implementation and local
verification are in progress; no live cutover is implied.

Executed checkpoint: shared Python coverage passes 537 tests (five Kafka-only
cases excluded and independently covered by the existing integration gate),
Analyzer passes 277, Ingestor 120, contracts generate/build verifies 14 artifacts.
Actual worker-chain E2E passes 13/14, including the completion-backlog and physical
baseline/Signals/Query case. The sole failure is the existing timed-out revoked
writer's subsequent rerun. Full Platform coverage subsequently passes 897 tests
(13 skipped), with no failures; see source attribution in the closure audit.
The final focused run passes 159 tests without skips/failures, including a direct
PostgreSQL reproduction of conflicting late versioned completion. It preserves
FAILED and the first immutable registration, creates no READY/head and rejects the
different logical payload with Conflicting immutable eventId.

## Protocol and compatibility

New enrolled commands freeze outputCompletion.protocol=WORKER_COMPLETION_V1 in
execution metadata. RUNNING remains a receipt on the status topic, not a heartbeat.
EOD, Indicators, Signals and evaluation send one WorkerCompletionEvent at the end.
SUCCESS requires one registration member matching execution/attempt/event/work;
ERROR has no registration. Valid-empty success retains its approved reason.

The existing topic-register-output-metadata-v1 accepts the new envelope alongside
legacy OutputRegistrationEvent. Fields are kind=WORKER_COMPLETION_V1, schemaVersion,
eventId, atomicAttemptId, status and nullable registration. Protobuf additions and
worker-completion-v1.json define the paired Java/Python wire. The complete envelope
is capped at 256 KiB. Kafka's key remains atomicAttemptId; no physical paths are added.

Queued commands without protocol retain registration-then-status behavior. Deploy
Platform and shared Python/workers together before issuing new commands. Default
enrollment flags are unchanged. Do not rewrite queued messages or reset offsets.

## Ordering and transaction

1. Worker sends RUNNING, then invokes its handler.
2. Handler reads frozen inputs, computes, writes and reads back Parquet.
3. OutputPublication collects evidence locally; its publish step stages the
   registration for the new protocol instead of emitting a separate record.
4. Terminal publisher sends one completion and awaits broker ACK. It does not send
   another terminal status. Errors before output publication still send ERROR.
5. Platform validates the bounded wire against the sealed persisted attempt and
   declaration under the execution lock. Registration, terminal status and eligible
   READY promotion commit in one transaction. A failure rolls all three back.
6. Command offset advances after terminal publication ACK. Broker ACK does not
   mean Platform has consumed and committed the completion.

The operational debt around broker ACK versus Platform commit and physical Parquet
without committed registration/READY is recorded in [TD-018](../technical-debt/018-execution-watchdog-observation.md#completion-delivery-and-storage-commit-boundaries).
This records diagnostics/recovery follow-up; current completion and READY safeguards
remain required.

JobService preserves outputCompletion as structured server-owned metadata and
prevents worker status from replacing it. Prior string conversion lost the
declaration on status updates and broke subsequent replay validation. Output facts
and execution states remain separate database records; FAILED never grants READY.

## Impact and evidence

Platform changes declaration, consumer routing, completion acceptance and frozen
metadata preservation. Shared Python owns envelope validation, staging/publication;
Ingestor changes EOD, Analyzer changes Indicators/Signals/evaluation. Contracts add
messages/shared fixture; generated output comes from the Nx target. Persistence
reuses current tables/locks/inbox: no new migration. Query/Console keep their existing
READY/read/public API; configuration/topic names and ownership/paths are unchanged.
Operations require paired deployment; no live activation or cleanup is performed.

Tests cover old commands, invalid identities, failure, valid-empty, replay, database
rollback and the actual Kafka/worker/Parquet/Query chain. Executed results and
attributable coverage belong in the [closure audit](016-i10-signals-backfill-closure.md).
Bounded graph analysis cannot prove cross-language isolation. General recovery
journals, automatic retry redesign and P12 remain deferred.

## Remaining boundaries

Combining terminal messages removes the cross-topic join for new commands. It does
not remove backlog, stop old workers or freeze a provider response on replay. The
watchdog still uses its published deadline; queued/running/completion-wait deadlines
are not implemented by the envelope change. The November 11 terminal-replay amendment
audits conflicting late VERSIONED completion before ACK, without touching accepted
evidence or READY. Other invalid/legacy records retain strict handling. Keep the
previous recovery failure in history and record its new executed regression result.

## Consumer poll window

Owner request November 10: inspect actual job durations, increase the Python poll
window to 15 minutes if supported, and defer changes to sequential handling.
READ ONLY PostgreSQL measurements use terminal child executions with a numeric
worker-reported durationMs. Root aggregation, missing-duration timestamp fallbacks,
operator cancellations and watchdog timeouts are excluded. This matters because
manually failed historical executions have day-long ages that are not worker time.
The local ignored report is .agent/reports/job-duration-audit-20261110.json with
its replayable READ ONLY SQL in .agent/operations/job-duration-audit-20261110.sql.

The measured history (August 27-November 7) contains 258789 successful timed child
executions: 134 at least 5 minutes, one at least 15 minutes. Every successful job
type except SYNC_METADATA has a measured maximum below 11 minutes 18 seconds.
SYNC_METADATA has only three successful samples: 213.737, 827.244 and 3233.651
seconds. The slowest scan examined 2723 objects; its reported duration agrees with
finishedAt-startedAt, so it is not merely queue delay. No successful timed intraday
sample exists; its 10265 timed failures average 9.512 seconds, maximum 76.845 seconds.
These historical measurements cannot guarantee a future maximum or exclude debugger
pauses; worker duration also does not include every terminal transport/commit delay.

KafkaSettings.max_poll_interval_ms defaults to 900000 and KafkaClientFactory passes
it explicitly for both single-topic and list subscriptions, logging the effective
value at consumer construction. KAFKA_MAX_POLL_INTERVAL_MS is a validated process-wide
override in BaseAppSettings. The 15-minute default improves the existing 5-minute
limit for ordinary work; it does not solve the long Metadata scan. No Metadata-only
exception, processing parallelism, watchdog change or live restart is included.

Impact: shared Python settings/factory affect Ingestor and Analyzer consumers and
validate the shared settings used by Query; Query exposes no new API. Java Platform,
Console, Kafka business schemas/topic names, storage/persistence and dataset owners
are unchanged. Tests cover both subscription shapes, custom intervals, startup env
override/rejection, existing sequential/ACK behavior and disposable Kafka replay.
Governance, ADR, flow, registry status, repository guidance and ownership need no
policy change; I10 remains in_progress with its existing late-evidence/cutover gates.

Verification P14-I10-poll-15m-20261110 passes 2/2 recorded checks: py-common coverage
545 passed/5 integration skips, and py-common:test-integration 5 real Kafka tests
passed separately. Factory coverage is 86.67% line/100% branch; KafkaSettings model
definitions are fully covered. The changed flat-override method executes 7/7
instrumented lines and has no instrumented branch; default, override and invalid
startup scenarios are executed explicitly. Whole shared.py branch coverage is
66.67% in unchanged configuration paths, not a component-wide coverage pass.
Artifacts retain unit/Kafka JUnit, coverage XML and the timing snapshot. No
15-minute wall-clock timeout experiment, live service restart, CI or full I10
completion is inferred from these local checks. Targeted static documentation
review reconciles units, override scope, Metadata exception and unchanged ownership;
no executable documentation checker or automatic formatting was run.
