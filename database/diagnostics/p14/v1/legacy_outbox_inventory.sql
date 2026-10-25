-- Version 1. Read-only UUID page over all outbox states; never a cleanup command.
-- psql parameters: after_id (empty or UUID), row_limit (1..400), observed_at (UTC).
-- Use a read-only repeatable-read transaction with statement_timeout=5s.
WITH args AS (
    SELECT nullif(:'after_id', '')::uuid AS after_id,
           greatest(1, least(400, :'row_limit'::integer)) AS row_limit,
           :'observed_at'::timestamptz AS observed_at
), page AS MATERIALIZED (
    SELECT o.* FROM scheduler_outbox_messages o, args a
    WHERE a.after_id IS NULL OR o.id > a.after_id
    ORDER BY o.id LIMIT (SELECT row_limit + 1 FROM args)
), bounded AS MATERIALIZED (
    SELECT * FROM page ORDER BY id LIMIT (SELECT row_limit FROM args)
), parsed AS (
    SELECT o.*, CASE WHEN o.payload IS JSON OBJECT
        AND pg_input_is_valid(o.payload, 'jsonb') THEN o.payload::jsonb END AS document
    FROM bounded o
), identities AS (
    SELECT o.*, CASE WHEN document->>'executionId' ~*
        '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        THEN (document->>'executionId')::uuid END AS work_id
    FROM parsed o
), classified AS (
    SELECT o.id AS message_id, o.execution_id AS stored_execution_id, o.work_id,
           o.message_index, o.status, o.attempts, o.available_at,
           coalesce(o.claim_until > a.observed_at, false) AS live_lease,
           CASE
             WHEN o.document IS NULL THEN 'INVALID_JSON_OBJECT'
             WHEN nullif(btrim(o.document->>'executionId'), '') IS NULL THEN 'MISSING_WORK_ID'
             WHEN o.work_id IS NULL THEN 'INVALID_WORK_ID'
             WHEN child.id IS NULL THEN 'MISSING_WORK_EXECUTION'
             WHEN o.work_id = o.execution_id THEN 'CURRENT_IDENTITY'
             WHEN parent.job_run_id IS NOT NULL OR child.job_run_id IS NOT NULL THEN 'LINKED_RUN_CONFLICT'
             WHEN child.parent_log_id IS DISTINCT FROM parent.id THEN 'PARENT_CONFLICT'
             WHEN child.job_id IS DISTINCT FROM parent.job_id THEN 'DEFINITION_CONFLICT'
             WHEN o.document->>'parentExecutionId' IS DISTINCT FROM parent.id::text THEN 'PAYLOAD_PARENT_CONFLICT'
             WHEN EXISTS (SELECT 1 FROM scheduler_outbox_messages other
                          WHERE other.execution_id=o.work_id AND other.message_index=o.message_index
                            AND other.id<>o.id) THEN 'PROJECTED_UNIQUE_COLLISION'
             ELSE 'LEGACY_ALIAS'
           END AS identity_state
    FROM identities o CROSS JOIN args a
    LEFT JOIN job_execution_histories parent ON parent.id=o.execution_id
    LEFT JOIN job_execution_histories child ON child.id=o.work_id
)
SELECT jsonb_build_object(
    'version', 1, 'observedAt', (SELECT observed_at FROM args),
    'scope', 'OUTBOX_UUID_PAGE', 'rowLimit', (SELECT row_limit FROM args),
    'hasMore', (SELECT count(*) > (SELECT row_limit FROM args) FROM page),
    'nextCursor', (SELECT message_id FROM classified ORDER BY message_id DESC LIMIT 1),
    'counts', (SELECT coalesce(jsonb_object_agg(identity_state,n),'{}'::jsonb)
               FROM (SELECT identity_state,count(*) n FROM classified GROUP BY identity_state) s),
    'rows', (SELECT coalesce(jsonb_agg(to_jsonb(c) ORDER BY message_id),'[]'::jsonb) FROM classified c)
) AS diagnostic;
