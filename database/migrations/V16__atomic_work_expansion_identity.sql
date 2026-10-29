-- I8: retain frozen step membership and distinguish the atomic work day.
-- No old row is re-expanded or rewritten. Applied V1--V15 remain untouched.
DROP INDEX uq_job_run_prepared_work;
CREATE UNIQUE INDEX uq_job_run_prepared_work ON job_execution_histories
    (job_run_id, job_run_step_index,
     (coalesce(meta_json->>'workIdentity',
               (meta_json->>'workType') || ':' || (meta_json->>'workKey'))))
    WHERE parent_log_id IS NOT NULL AND job_run_id IS NOT NULL
      AND meta_json->>'runPreparationVersion'='1';

CREATE FUNCTION guard_atomic_work_snapshot() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE adding_snapshot BOOLEAN;
BEGIN
    IF TG_OP='UPDATE' THEN
        IF OLD.meta_json->'workExpansion' IS NOT NULL AND
           (NEW.meta_json->'workExpansion', NEW.meta_json->>'workIdentity', NEW.meta_json->>'workBusinessDate')
           IS DISTINCT FROM
           (OLD.meta_json->'workExpansion', OLD.meta_json->>'workIdentity', OLD.meta_json->>'workBusinessDate') THEN
            RAISE EXCEPTION 'Atomic work snapshot is immutable';
        END IF;
        IF OLD.meta_json->'acceptedExpansion' IS NOT NULL AND
           NEW.meta_json->'acceptedExpansion' IS DISTINCT FROM OLD.meta_json->'acceptedExpansion' THEN
            RAISE EXCEPTION 'Accepted expansion is immutable';
        END IF;
        IF OLD.meta_json->'expandedWorkIds' IS NOT NULL AND
           NEW.meta_json->'expandedWorkIds' IS DISTINCT FROM OLD.meta_json->'expandedWorkIds' THEN
            RAISE EXCEPTION 'Expanded membership is immutable';
        END IF;
        adding_snapshot :=
            (OLD.meta_json->'workExpansion' IS NULL AND NEW.meta_json->'workExpansion' IS NOT NULL)
            OR (OLD.meta_json->'acceptedExpansion' IS NULL AND NEW.meta_json->'acceptedExpansion' IS NOT NULL)
            OR (OLD.meta_json->'expandedWorkIds' IS NULL AND NEW.meta_json->'expandedWorkIds' IS NOT NULL);
    ELSE
        adding_snapshot := NEW.meta_json->'workExpansion' IS NOT NULL
            OR NEW.meta_json->'acceptedExpansion' IS NOT NULL
            OR NEW.meta_json->'expandedWorkIds' IS NOT NULL;
    END IF;
    IF NEW.meta_json->'workExpansion' IS NOT NULL THEN
        IF jsonb_typeof(NEW.meta_json->'workExpansion') IS DISTINCT FROM 'object'
           OR jsonb_typeof(NEW.meta_json->'workExpansion'->'parameters') IS DISTINCT FROM 'object'
           OR coalesce(NEW.meta_json->>'workIdentity','') !~ '^work:[0-9a-f]{64}$' THEN
            RAISE EXCEPTION 'Invalid atomic work snapshot';
        END IF;
        IF NEW.meta_json->'workExpansion'->>'workType' IS DISTINCT FROM NEW.meta_json->>'workType'
           OR NEW.meta_json->'workExpansion'->>'workKey' IS DISTINCT FROM NEW.meta_json->>'workKey'
           OR NEW.meta_json->'workExpansion'->>'businessDate' IS DISTINCT FROM NEW.meta_json->>'workBusinessDate'
           OR NEW.meta_json->'workExpansion'->>'atomicWorkId' IS DISTINCT FROM NEW.meta_json->>'workIdentity' THEN
            RAISE EXCEPTION 'Atomic work snapshot identity mismatch';
        END IF;
    END IF;
    IF adding_snapshot AND EXISTS (
        SELECT 1 FROM job_run_step_preparations
        WHERE job_run_id=NEW.job_run_id AND step_index=NEW.job_run_step_index
    ) THEN
        RAISE EXCEPTION 'Cannot add atomic identity to closed JobRun work';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER guard_atomic_work_snapshot BEFORE INSERT OR UPDATE ON job_execution_histories
    FOR EACH ROW EXECUTE FUNCTION guard_atomic_work_snapshot();
