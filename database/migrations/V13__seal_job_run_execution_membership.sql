-- P14-I3.c: additive preparation closure. Legacy NULL-linked execution/outbox rows are unaffected.
-- V12 instances can already have optional execution links: the unique root applies only to
-- the versioned preparation path, without rewriting those older optional links.
CREATE UNIQUE INDEX uq_job_run_prepared_root ON job_execution_histories(job_run_id, job_run_step_index)
    WHERE parent_log_id IS NULL AND job_run_id IS NOT NULL
      AND meta_json ->> 'runPreparationVersion' = '1';
CREATE UNIQUE INDEX uq_job_run_prepared_work ON job_execution_histories
    (job_run_id, job_run_step_index, (meta_json ->> 'workType'), (meta_json ->> 'workKey'))
    WHERE parent_log_id IS NOT NULL AND job_run_id IS NOT NULL
      AND meta_json ->> 'runPreparationVersion' = '1';

CREATE TABLE job_run_step_preparations (
    job_run_id UUID NOT NULL,
    step_index INTEGER NOT NULL,
    parent_execution_id UUID NOT NULL UNIQUE REFERENCES job_execution_histories(id) ON DELETE RESTRICT,
    child_count INTEGER NOT NULL CHECK (child_count BETWEEN 0 AND 5000),
    message_count INTEGER NOT NULL CHECK (message_count BETWEEN 0 AND 5000),
    closed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (job_run_id, step_index),
    FOREIGN KEY (job_run_id, step_index) REFERENCES job_run_steps(job_run_id, step_index),
    CHECK (child_count = message_count OR (child_count = 0 AND message_count = 1))
);

CREATE FUNCTION seal_job_run_execution_membership() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE parent job_execution_histories%ROWTYPE; actual_children INTEGER; actual_messages INTEGER;
BEGIN
    IF TG_OP <> 'INSERT' THEN RAISE EXCEPTION 'JobRun preparation closure is immutable'; END IF;
    PERFORM 1 FROM job_runs WHERE id = NEW.job_run_id FOR UPDATE;
    SELECT * INTO parent FROM job_execution_histories WHERE id = NEW.parent_execution_id;
    IF parent.job_run_id IS DISTINCT FROM NEW.job_run_id
       OR parent.job_run_step_index IS DISTINCT FROM NEW.step_index
       OR parent.parent_log_id IS NOT NULL
       OR parent.meta_json ->> 'runPreparationVersion' IS DISTINCT FROM '1' THEN
        RAISE EXCEPTION 'JobRun preparation parent mismatch';
    END IF;
    SELECT count(*) INTO actual_children FROM job_execution_histories
        WHERE job_run_id = NEW.job_run_id AND job_run_step_index = NEW.step_index
          AND parent_log_id = NEW.parent_execution_id;
    -- Parameterized scalar counts retain the execution_id index condition even on a
    -- freshly migrated table without statistics. A broad LEFT JOIN can otherwise
    -- choose a full outbox index scan once per child during a large preparation.
    SELECT coalesce(sum((SELECT count(*) FROM scheduler_outbox_messages o WHERE o.execution_id=h.id)),0)
        INTO actual_messages FROM job_execution_histories h
        WHERE h.job_run_id = NEW.job_run_id AND h.job_run_step_index = NEW.step_index;
    IF actual_children <> NEW.child_count OR actual_messages <> NEW.message_count OR EXISTS (
        SELECT 1 FROM job_execution_histories h
        WHERE h.job_run_id = NEW.job_run_id AND h.job_run_step_index = NEW.step_index
          AND (h.parent_log_id IS DISTINCT FROM NEW.parent_execution_id AND h.id <> NEW.parent_execution_id)
    ) OR EXISTS (
        SELECT 1 FROM job_execution_histories h WHERE h.parent_log_id = NEW.parent_execution_id
          AND (SELECT count(*) FROM scheduler_outbox_messages o WHERE o.execution_id=h.id) <> 1
    ) THEN
        RAISE EXCEPTION 'Incomplete JobRun execution/message membership';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER seal_job_run_preparation BEFORE INSERT OR UPDATE OR DELETE ON job_run_step_preparations
    FOR EACH ROW EXECUTE FUNCTION seal_job_run_execution_membership();

CREATE FUNCTION guard_prepared_job_run_execution() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE run_id UUID; step_id INTEGER; parent_run_id UUID; parent_step_id INTEGER;
BEGIN
    -- Progress must not invert the parent-aggregation/run lock order. Compare the
    -- full protected identity, not only run links or the list of updated columns.
    IF TG_OP = 'UPDATE' THEN
        IF (NEW.id, NEW.job_id, NEW.parent_log_id, NEW.job_run_id, NEW.job_run_step_index,
            NEW.meta_json ->> 'workType', NEW.meta_json ->> 'workKey', NEW.meta_json ->> 'businessDate',
            NEW.meta_json ->> 'runPreparationVersion') IS NOT DISTINCT FROM
           (OLD.id, OLD.job_id, OLD.parent_log_id, OLD.job_run_id, OLD.job_run_step_index,
            OLD.meta_json ->> 'workType', OLD.meta_json ->> 'workKey', OLD.meta_json ->> 'businessDate',
            OLD.meta_json ->> 'runPreparationVersion') THEN
            RETURN NEW;
        END IF;
    END IF;
    -- A legacy writer can supply only parent_log_id. Validate the parent before
    -- the NULL-run compatibility return so it cannot bypass prepared membership.
    IF TG_OP <> 'DELETE' AND NEW.parent_log_id IS NOT NULL AND NEW.job_run_id IS NULL THEN
        SELECT h.job_run_id, h.job_run_step_index INTO parent_run_id, parent_step_id
            FROM job_execution_histories h WHERE h.id = NEW.parent_log_id
              AND h.job_run_id IS NOT NULL AND h.meta_json ->> 'runPreparationVersion' = '1';
        IF parent_run_id IS NOT NULL THEN
            PERFORM 1 FROM job_runs WHERE id = parent_run_id FOR UPDATE;
            IF (NEW.job_run_id, NEW.job_run_step_index) IS DISTINCT FROM (parent_run_id, parent_step_id) THEN
                RAISE EXCEPTION 'Prepared parent requires matching child JobRun membership';
            END IF;
        END IF;
    END IF;
    IF TG_OP = 'INSERT' THEN run_id := NEW.job_run_id; step_id := NEW.job_run_step_index;
    ELSE run_id := OLD.job_run_id; step_id := OLD.job_run_step_index; END IF;
    IF TG_OP = 'UPDATE' AND (NEW.job_run_id, NEW.job_run_step_index) IS DISTINCT FROM
                           (OLD.job_run_id, OLD.job_run_step_index) AND NEW.job_run_id IS NOT NULL THEN
        PERFORM 1 FROM job_runs WHERE id IN (OLD.job_run_id, NEW.job_run_id) ORDER BY id FOR UPDATE;
        IF EXISTS (SELECT 1 FROM job_run_step_preparations WHERE job_run_id = NEW.job_run_id
                   AND step_index = NEW.job_run_step_index) THEN
            RAISE EXCEPTION 'Cannot move execution into closed JobRun membership';
        END IF;
    END IF;
    IF run_id IS NULL THEN RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END; END IF;
    PERFORM 1 FROM job_runs WHERE id = run_id FOR UPDATE;
    IF TG_OP <> 'DELETE' AND (TG_OP = 'INSERT' OR NEW.parent_log_id IS DISTINCT FROM OLD.parent_log_id)
       AND EXISTS (SELECT 1 FROM job_run_step_preparations WHERE parent_execution_id=NEW.parent_log_id) THEN
        RAISE EXCEPTION 'Cannot append execution under a closed preparation parent';
    END IF;
    IF TG_OP = 'INSERT' AND NEW.meta_json ->> 'runPreparationVersion' = '1' THEN
        IF coalesce(NEW.meta_json ->> 'workType', '') NOT IN ('GLOBAL', 'EXCHANGE', 'SECTOR', 'SYMBOL')
           OR coalesce(btrim(NEW.meta_json ->> 'workKey'), '') = ''
           OR NOT EXISTS (SELECT 1 FROM job_run_steps WHERE job_run_id=run_id AND step_index=step_id
                          AND business_date::text=NEW.meta_json ->> 'businessDate') THEN
            RAISE EXCEPTION 'Prepared JobRun requires work identity and its frozen business date';
        END IF;
    END IF;
    IF EXISTS (SELECT 1 FROM job_run_step_preparations WHERE job_run_id = run_id AND step_index = step_id) THEN
        IF TG_OP = 'INSERT' OR TG_OP = 'DELETE' THEN
            RAISE EXCEPTION 'JobRun execution membership is closed';
        END IF;
        IF (NEW.id, NEW.job_id, NEW.parent_log_id, NEW.job_run_id, NEW.job_run_step_index,
            NEW.meta_json ->> 'workType', NEW.meta_json ->> 'workKey', NEW.meta_json ->> 'businessDate',
            NEW.meta_json ->> 'runPreparationVersion') IS DISTINCT FROM
           (OLD.id, OLD.job_id, OLD.parent_log_id, OLD.job_run_id, OLD.job_run_step_index,
            OLD.meta_json ->> 'workType', OLD.meta_json ->> 'workKey', OLD.meta_json ->> 'businessDate',
            OLD.meta_json ->> 'runPreparationVersion') THEN
            RAISE EXCEPTION 'JobRun execution identity is immutable';
        END IF;
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$;
CREATE TRIGGER guard_prepared_job_run_execution BEFORE INSERT OR UPDATE OR DELETE ON job_execution_histories
    FOR EACH ROW EXECUTE FUNCTION guard_prepared_job_run_execution();

CREATE FUNCTION guard_prepared_job_run_outbox() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE run_id UUID; step_id INTEGER; execution_id UUID;
BEGIN
    -- Delivery/lease/retry updates leave intent unchanged and need no run lock.
    IF TG_OP = 'UPDATE' THEN
        IF (NEW.id, NEW.execution_id, NEW.message_index, NEW.topic, NEW.message_key, NEW.payload)
           IS NOT DISTINCT FROM (OLD.id, OLD.execution_id, OLD.message_index, OLD.topic, OLD.message_key, OLD.payload) THEN
            RETURN NEW;
        END IF;
    END IF;
    IF TG_OP = 'INSERT' THEN execution_id := NEW.execution_id; ELSE execution_id := OLD.execution_id; END IF;
    SELECT h.job_run_id, h.job_run_step_index INTO run_id, step_id
        FROM job_execution_histories h WHERE h.id = execution_id;
    IF TG_OP = 'UPDATE' AND NEW.execution_id IS DISTINCT FROM OLD.execution_id THEN
        PERFORM 1 FROM job_runs r
            WHERE r.id=run_id OR r.id=(SELECT h.job_run_id FROM job_execution_histories h WHERE h.id=NEW.execution_id)
            ORDER BY r.id FOR UPDATE;
        IF EXISTS (SELECT 1 FROM job_run_step_preparations p JOIN job_execution_histories h
            ON h.job_run_id=p.job_run_id AND h.job_run_step_index=p.step_index WHERE h.id=NEW.execution_id) THEN
            RAISE EXCEPTION 'Cannot move outbox into closed JobRun membership';
        END IF;
    END IF;
    IF run_id IS NULL THEN RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END; END IF;
    PERFORM 1 FROM job_runs WHERE id = run_id FOR UPDATE;
    IF EXISTS (SELECT 1 FROM job_run_step_preparations WHERE job_run_id = run_id AND step_index = step_id) THEN
        IF TG_OP <> 'UPDATE' THEN RAISE EXCEPTION 'JobRun outbox membership is closed'; END IF;
        IF (NEW.id, NEW.execution_id, NEW.message_index, NEW.topic, NEW.message_key, NEW.payload)
           IS DISTINCT FROM (OLD.id, OLD.execution_id, OLD.message_index, OLD.topic, OLD.message_key, OLD.payload) THEN
            RAISE EXCEPTION 'JobRun outbox intent is immutable';
        END IF;
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$;
CREATE TRIGGER guard_prepared_job_run_outbox BEFORE INSERT OR UPDATE OR DELETE ON scheduler_outbox_messages
    FOR EACH ROW EXECUTE FUNCTION guard_prepared_job_run_outbox();

-- Versioned preparation parents must close membership before commit. Older optional V12 links
-- remain compatible and do not gain a fabricated completion marker during this migration.
CREATE FUNCTION require_job_run_preparation_closure() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.job_run_id IS NOT NULL AND NEW.parent_log_id IS NULL
       AND NEW.meta_json ->> 'runPreparationVersion' = '1'
       AND (SELECT count(*) FROM job_run_step_preparations WHERE job_run_id = NEW.job_run_id)
            <> (SELECT step_count FROM job_runs WHERE id = NEW.job_run_id) THEN
        RAISE EXCEPTION 'JobRun execution membership must close before commit';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER require_job_run_preparation_closure AFTER INSERT ON job_execution_histories
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION require_job_run_preparation_closure();
