-- P1-I4 hard cutover: install canonical work identity indexes after operators
-- manually clear execution history and drain pending scheduler work.
-- This migration never deletes or rewrites operational records.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM scheduler_outbox_messages
        WHERE status = 'PENDING'
    ) THEN
        RAISE EXCEPTION 'P1-I4 requires a drained scheduler outbox';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM job_execution_histories
    ) THEN
        RAISE EXCEPTION 'P1-I4 requires job execution history to be cleared manually before cutover';
    END IF;
END $$;

DROP INDEX IF EXISTS idx_job_execution_history_symbol;

DROP INDEX IF EXISTS idx_job_execution_history_symbol_offset;

CREATE INDEX IF NOT EXISTS idx_job_execution_history_work_identity ON job_execution_histories (
    (meta_json ->> 'workType'),
    (meta_json ->> 'workKey')
)
WHERE
    parent_log_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_job_execution_history_symbol_offset ON job_execution_histories (
    job_id,
    (meta_json ->> 'workKey'),
    finished_at DESC
)
WHERE
    new_offset IS NOT NULL
    AND meta_json ->> 'workType' = 'SYMBOL';
