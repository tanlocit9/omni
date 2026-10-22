-- P14-I3.d: operator-triggered jobs are deferred by owner decision.
-- Deploy only after stopping old application instances that still use this table.
-- Old request audit/idempotency history is intentionally discarded; no data migration.
-- Data rollback requires a backup. Application rollback alone cannot restore this table.
-- V8 remains immutable migration history. Do not cascade into execution/run storage.
DROP TABLE manual_job_triggers;
