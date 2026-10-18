-- P14-I3.b: additive persistence only. Existing scheduler/manual/outbox writers stay unchanged.
CREATE TABLE job_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    mode VARCHAR(16) NOT NULL CHECK (mode IN ('SCHEDULED', 'MANUAL', 'BACKFILL')),
    actor VARCHAR(200) NOT NULL CHECK (btrim(actor) <> ''),
    idempotency_key VARCHAR(128) NOT NULL CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{1,128}$'),
    reason VARCHAR(500) NOT NULL CHECK (btrim(reason) <> ''),
    business_group VARCHAR(200),
    request_json JSONB NOT NULL CHECK (jsonb_typeof(request_json) = 'object'
        AND octet_length(request_json::text) <= 65536),
    target_definition_id UUID NOT NULL REFERENCES job_definitions(id) ON DELETE RESTRICT,
    scope_type VARCHAR(16) NOT NULL CHECK (scope_type IN ('GLOBAL', 'EXCHANGE', 'SECTOR', 'SYMBOL')),
    scope_key VARCHAR(256) NOT NULL CHECK (scope_key = btrim(scope_key) AND scope_key <> ''),
    business_date DATE NOT NULL,
    path_contract_version INTEGER NOT NULL CHECK (path_contract_version = 1),
    path_fingerprint CHAR(64) NOT NULL CHECK (path_fingerprint ~ '^[0-9a-f]{64}$'),
    step_count INTEGER NOT NULL CHECK (step_count BETWEEN 1 AND 1024),
    state VARCHAR(16) NOT NULL DEFAULT 'PREPARING'
        CHECK (state IN ('PREPARING', 'PREPARED', 'RUNNING', 'SUCCESS', 'FAILED', 'CANCELLED')),
    failure_reason TEXT,
    CONSTRAINT uq_job_runs_request UNIQUE (mode, actor, idempotency_key)
);

CREATE TABLE job_run_steps (
    job_run_id UUID NOT NULL REFERENCES job_runs(id) ON DELETE RESTRICT,
    step_index INTEGER NOT NULL CHECK (step_index BETWEEN 0 AND 1023),
    job_definition_id UUID NOT NULL REFERENCES job_definitions(id) ON DELETE RESTRICT,
    definition_source VARCHAR(255) NOT NULL,
    definition_job_type VARCHAR(50) NOT NULL,
    definition_cron VARCHAR(255) NOT NULL,
    scope_type VARCHAR(16) NOT NULL CHECK (scope_type IN ('GLOBAL', 'EXCHANGE', 'SECTOR', 'SYMBOL')),
    scope_key VARCHAR(256) NOT NULL CHECK (scope_key = btrim(scope_key) AND scope_key <> ''),
    business_date DATE NOT NULL,
    PRIMARY KEY (job_run_id, step_index),
    CONSTRAINT uq_job_run_step_definition UNIQUE (job_run_id, step_index, job_definition_id),
    CONSTRAINT uq_job_run_scoped_step UNIQUE
        (job_run_id, job_definition_id, scope_type, scope_key, business_date)
);

CREATE TABLE job_run_step_predecessors (
    job_run_id UUID NOT NULL,
    step_index INTEGER NOT NULL,
    predecessor_index INTEGER NOT NULL,
    PRIMARY KEY (job_run_id, step_index, predecessor_index),
    FOREIGN KEY (job_run_id, step_index) REFERENCES job_run_steps(job_run_id, step_index),
    FOREIGN KEY (job_run_id, predecessor_index) REFERENCES job_run_steps(job_run_id, step_index),
    CHECK (predecessor_index < step_index)
);

-- Instance membership is appendable only inside its initial preparation transaction.
CREATE FUNCTION guard_job_run_membership() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE run_state VARCHAR;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'JobRun snapshot membership is immutable';
    END IF;
    SELECT state INTO run_state FROM job_runs WHERE id = NEW.job_run_id FOR UPDATE;
    IF run_state IS DISTINCT FROM 'PREPARING' THEN
        RAISE EXCEPTION 'JobRun snapshot membership is sealed';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER guard_job_run_steps BEFORE INSERT OR UPDATE OR DELETE ON job_run_steps
    FOR EACH ROW EXECUTE FUNCTION guard_job_run_membership();
CREATE TRIGGER guard_job_run_predecessors BEFORE INSERT OR UPDATE OR DELETE ON job_run_step_predecessors
    FOR EACH ROW EXECUTE FUNCTION guard_job_run_membership();

CREATE FUNCTION guard_job_run_snapshot() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE actual_count INTEGER; ancestor_count INTEGER; target_matches BOOLEAN;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.state <> 'PREPARING' THEN
            RAISE EXCEPTION 'JobRun must start in preparation';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'JobRun history cannot be deleted through normal DML';
    END IF;
    IF (to_jsonb(NEW) - 'state' - 'failure_reason') IS DISTINCT FROM
       (to_jsonb(OLD) - 'state' - 'failure_reason') THEN
        RAISE EXCEPTION 'JobRun request and path identity are immutable';
    END IF;
    IF NEW.state = 'PREPARING' THEN
        RAISE EXCEPTION 'JobRun cannot return to preparation';
    END IF;
    IF OLD.state = 'PREPARING' THEN
        IF NEW.state <> 'PREPARED' THEN
            RAISE EXCEPTION 'JobRun must seal before lifecycle transitions';
        END IF;
        SELECT count(*) INTO actual_count FROM job_run_steps WHERE job_run_id = NEW.id;
        SELECT EXISTS (
            SELECT 1 FROM job_run_steps s
            WHERE s.job_run_id = NEW.id AND s.step_index = NEW.step_count - 1
              AND s.job_definition_id = NEW.target_definition_id
              AND s.scope_type = NEW.scope_type AND s.scope_key = NEW.scope_key
              AND s.business_date = NEW.business_date
        ) INTO target_matches;
        IF actual_count <> NEW.step_count OR NOT target_matches OR EXISTS (
            SELECT 1 FROM job_run_steps WHERE job_run_id = NEW.id
              AND (step_index >= NEW.step_count OR business_date <> NEW.business_date)
        ) THEN
            RAISE EXCEPTION 'JobRun snapshot membership is incomplete';
        END IF;
        IF (SELECT count(*) FROM job_run_step_predecessors WHERE job_run_id = NEW.id) > 8192 THEN
            RAISE EXCEPTION 'JobRun path edge bound exceeded';
        END IF;
        WITH RECURSIVE ancestors(step_index) AS (
            SELECT NEW.step_count - 1
            UNION
            SELECT p.predecessor_index FROM job_run_step_predecessors p
                JOIN ancestors a ON a.step_index = p.step_index WHERE p.job_run_id = NEW.id
        ) SELECT count(*) INTO ancestor_count FROM ancestors;
        IF ancestor_count <> NEW.step_count THEN
            RAISE EXCEPTION 'JobRun snapshot contains disconnected membership';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER guard_job_runs BEFORE INSERT OR UPDATE OR DELETE ON job_runs
    FOR EACH ROW EXECUTE FUNCTION guard_job_run_snapshot();

CREATE FUNCTION require_sealed_job_run() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM job_runs WHERE id = NEW.id AND state = 'PREPARING') THEN
        RAISE EXCEPTION 'An incomplete JobRun cannot commit';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER require_sealed_job_run AFTER INSERT ON job_runs
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION require_sealed_job_run();

ALTER TABLE job_execution_histories
    ADD COLUMN job_run_id UUID,
    ADD COLUMN job_run_step_index INTEGER,
    ADD CONSTRAINT ck_execution_job_run_pair
        CHECK ((job_run_id IS NULL) = (job_run_step_index IS NULL)),
    ADD CONSTRAINT fk_execution_job_run_step
        FOREIGN KEY (job_run_id, job_run_step_index, job_id)
        REFERENCES job_run_steps(job_run_id, step_index, job_definition_id),
    ADD CONSTRAINT uq_execution_run_identity UNIQUE (id, job_run_id),
    ADD CONSTRAINT fk_execution_parent_run
        FOREIGN KEY (parent_log_id, job_run_id) REFERENCES job_execution_histories(id, job_run_id);

CREATE INDEX idx_job_runs_state_requested ON job_runs(state, requested_at, id);
CREATE INDEX idx_job_runs_scope_date ON job_runs(scope_type, scope_key, business_date, id);
CREATE INDEX idx_job_runs_definition_date ON job_runs(target_definition_id, business_date, id);
CREATE INDEX idx_job_run_steps_definition ON job_run_steps(job_definition_id, job_run_id);
CREATE INDEX idx_execution_job_run_step ON job_execution_histories(job_run_id, job_run_step_index, status)
    WHERE job_run_id IS NOT NULL;

COMMENT ON TABLE job_run_steps IS 'Frozen scoped instance of a resolved static path, not a mutable topology authority.';
COMMENT ON COLUMN job_execution_histories.job_run_id IS 'Nullable for legacy executions; never replaces executionId or parentLogId.';
