-- P14-I7: run only after affected writers/claims are drained and a backup is verified.
-- Flyway runs this in one PostgreSQL transaction; failure preserves all intent/history.
SET LOCAL lock_timeout = '5s';
LOCK TABLE job_execution_histories IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE scheduler_outbox_messages IN SHARE ROW EXCLUSIVE MODE;

CREATE TEMP TABLE p14_outbox_work_identity ON COMMIT DROP AS
WITH parsed AS (
    SELECT o.*, CASE WHEN payload IS JSON OBJECT AND pg_input_is_valid(payload,'jsonb')
        THEN payload::jsonb END AS document
    FROM scheduler_outbox_messages o
), identified AS (
    SELECT p.*, CASE WHEN document->>'executionId' ~*
        '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        THEN (document->>'executionId')::uuid END AS target_id
    FROM parsed p
)
SELECT o.id, o.execution_id AS stored_id, o.target_id, o.message_index,
    CASE WHEN o.document IS NULL THEN 'INVALID_JSON_OBJECT'
      WHEN o.target_id IS NULL THEN 'INVALID_OR_MISSING_WORK_ID'
      WHEN work.id IS NULL THEN 'MISSING_WORK_EXECUTION'
      WHEN o.target_id<>o.execution_id AND (stored.job_run_id IS NOT NULL OR work.job_run_id IS NOT NULL)
        THEN 'LINKED_RUN_CONFLICT'
      WHEN o.target_id<>o.execution_id AND work.parent_log_id IS DISTINCT FROM stored.id THEN 'PARENT_CONFLICT'
      WHEN o.target_id<>o.execution_id AND work.job_id IS DISTINCT FROM stored.job_id THEN 'DEFINITION_CONFLICT'
      WHEN o.document->>'parentExecutionId' IS DISTINCT FROM work.parent_log_id::text THEN 'PAYLOAD_PARENT_CONFLICT'
      WHEN o.document ? 'jobDefinitionId' AND lower(o.document->>'jobDefinitionId') IS DISTINCT FROM work.job_id::text
        THEN 'PAYLOAD_DEFINITION_CONFLICT'
      ELSE 'VALID' END AS identity_state
FROM identified o
JOIN job_execution_histories stored ON stored.id=o.execution_id
LEFT JOIN job_execution_histories work ON work.id=o.target_id;

DO $$
DECLARE invalid RECORD;
BEGIN
    SELECT id,identity_state INTO invalid FROM p14_outbox_work_identity
        WHERE identity_state<>'VALID' ORDER BY id LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'P14_I7_INVALID_IDENTITY messageId=% reason=%', invalid.id,invalid.identity_state;
    END IF;
    SELECT target_id,message_index INTO invalid FROM p14_outbox_work_identity
        GROUP BY target_id,message_index HAVING count(*)>1 ORDER BY target_id,message_index LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'P14_I7_PROJECTED_UNIQUE_COLLISION executionId=% messageIndex=%', invalid.target_id,invalid.message_index;
    END IF;
END;
$$;

-- Only this FK changes. Do not touch status, attempts, payload, keys, leases or audit fields.
UPDATE scheduler_outbox_messages o SET execution_id=i.target_id
FROM p14_outbox_work_identity i WHERE o.id=i.id AND o.execution_id<>i.target_id;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM scheduler_outbox_messages o JOIN p14_outbox_work_identity i ON i.id=o.id
        WHERE o.execution_id IS DISTINCT FROM i.target_id) THEN
        RAISE EXCEPTION 'P14_I7_REMAINING_ALIAS';
    END IF;
END;
$$;

-- Prevent an incompatible old writer from recreating a parent-FK/child-payload alias.
-- Lease/status/ack updates do not invoke this identity-only guard.
CREATE FUNCTION guard_scheduler_outbox_work_identity() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE document JSONB; payload_id UUID;
BEGIN
    IF NOT (NEW.payload IS JSON OBJECT AND pg_input_is_valid(NEW.payload,'jsonb')) THEN
        RAISE EXCEPTION 'P14_I7_INVALID_JSON_OBJECT messageId=%', NEW.id;
    END IF;
    document := NEW.payload::jsonb;
    IF document->>'executionId' IS NULL OR NOT (document->>'executionId' ~*
        '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$') THEN
        RAISE EXCEPTION 'P14_I7_INVALID_OR_MISSING_WORK_ID messageId=%', NEW.id;
    END IF;
    payload_id := (document->>'executionId')::uuid;
    IF payload_id<>NEW.execution_id THEN
        RAISE EXCEPTION 'P14_I7_OUTBOX_WORK_ID_MISMATCH messageId=%', NEW.id;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER guard_scheduler_outbox_work_identity
    BEFORE INSERT OR UPDATE OF execution_id,payload ON scheduler_outbox_messages
    FOR EACH ROW EXECUTE FUNCTION guard_scheduler_outbox_work_identity();
