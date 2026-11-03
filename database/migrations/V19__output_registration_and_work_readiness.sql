-- I10 final schema, consolidated before first retained deployment.
-- Owner confirmed the former V19/V20 were not applied; the unshipped V19-V23
-- development sequence is replaced by this single V19. V1-V18 are unchanged.
-- Registration/completion history, physical catalog and work READY remain distinct.
-- No historical execution/work identity is invented or enrolled by this migration.

-- Atomic attempt identity prerequisite
ALTER TABLE atomic_work_attempts ADD CONSTRAINT uq_atomic_attempt_execution
    UNIQUE (id, execution_id);

-- Registration inbox and immutable evidence
CREATE TABLE output_registration_events (
    event_id UUID PRIMARY KEY,
    schema_version INTEGER NOT NULL CHECK (schema_version = 1),
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE output_registrations (
    registration_id UUID PRIMARY KEY,
    first_event_id UUID NOT NULL REFERENCES output_registration_events(event_id),
    execution_id UUID NOT NULL UNIQUE REFERENCES job_execution_histories(id),
    atomic_attempt_id UUID NOT NULL REFERENCES atomic_work_attempts(id),
    atomic_work_id TEXT NOT NULL,
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (atomic_work_id ~ '^work:[0-9a-f]{64}$'),
    CONSTRAINT fk_registration_attempt_execution
        FOREIGN KEY (atomic_attempt_id, execution_id)
        REFERENCES atomic_work_attempts(id, execution_id)
);
CREATE INDEX output_registrations_work ON output_registrations(atomic_work_id, received_at);

CREATE FUNCTION guard_output_registration_history() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Output registration evidence is immutable';
END; $$;
CREATE TRIGGER guard_output_registration_events BEFORE UPDATE OR DELETE ON output_registration_events
    FOR EACH ROW EXECUTE FUNCTION guard_output_registration_history();
CREATE TRIGGER guard_output_registrations BEFORE UPDATE OR DELETE ON output_registrations
    FOR EACH ROW EXECUTE FUNCTION guard_output_registration_history();

-- Output coverage, completion and timeout evidence
-- Indexed per-output provenance and exact dates. Evidence is not a READY pointer.
CREATE TABLE output_registration_outputs (
    registration_id UUID NOT NULL REFERENCES output_registrations(registration_id),
    output_index INTEGER NOT NULL CHECK (output_index BETWEEN 0 AND 31),
    dataset TEXT NOT NULL,
    partition_values JSONB NOT NULL CHECK (jsonb_typeof(partition_values) = 'object'),
    data_version TEXT NOT NULL CHECK (data_version ~ '^sha256:[0-9a-f]{64}$'),
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    PRIMARY KEY (registration_id, output_index)
);
CREATE INDEX output_registration_version_provenance
    ON output_registration_outputs(dataset, partition_values, data_version);
CREATE TABLE output_registration_coverage (
    registration_id UUID NOT NULL,
    output_index INTEGER NOT NULL,
    coverage_date DATE NOT NULL,
    PRIMARY KEY (registration_id, output_index, coverage_date),
    FOREIGN KEY (registration_id, output_index)
        REFERENCES output_registration_outputs(registration_id, output_index)
);
CREATE INDEX output_registration_coverage_date
    ON output_registration_coverage(coverage_date, registration_id, output_index);
CREATE TRIGGER guard_output_registration_outputs
    BEFORE UPDATE OR DELETE ON output_registration_outputs
    FOR EACH ROW EXECUTE FUNCTION guard_output_registration_history();
CREATE TRIGGER guard_output_registration_coverage
    BEFORE UPDATE OR DELETE ON output_registration_coverage
    FOR EACH ROW EXECUTE FUNCTION guard_output_registration_history();

CREATE TABLE output_completion_contracts (
    generation BIGINT GENERATED ALWAYS AS IDENTITY,
    execution_id UUID PRIMARY KEY REFERENCES job_execution_histories(id),
    atomic_attempt_id UUID NOT NULL,
    required_outputs JSONB NOT NULL CHECK (jsonb_typeof(required_outputs) = 'array'
        AND jsonb_array_length(required_outputs) BETWEEN 1 AND 32),
    valid_empty_reasons JSONB NOT NULL DEFAULT '[]'::jsonb
        CHECK (jsonb_typeof(valid_empty_reasons) = 'array'
            AND jsonb_array_length(valid_empty_reasons) <= 32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (atomic_attempt_id, execution_id)
        REFERENCES atomic_work_attempts(id, execution_id)
);
CREATE TRIGGER guard_output_completion_contracts
    BEFORE UPDATE OR DELETE ON output_completion_contracts
    FOR EACH ROW EXECUTE FUNCTION guard_output_registration_history();

CREATE TABLE output_completion_statuses (
    execution_id UUID PRIMARY KEY REFERENCES output_completion_contracts(execution_id),
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'
        AND payload->>'status' IS NOT NULL AND payload->>'status' = 'SUCCESS'
        AND payload->>'executionId' IS NOT NULL AND payload->>'executionId' = execution_id::text),
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TRIGGER guard_output_completion_statuses
    BEFORE UPDATE OR DELETE ON output_completion_statuses
    FOR EACH ROW EXECUTE FUNCTION guard_output_registration_history();

CREATE TABLE output_attempt_timeouts (
    execution_id UUID PRIMARY KEY REFERENCES atomic_work_attempts(execution_id),
    published_at TIMESTAMPTZ NOT NULL,
    timed_out_at TIMESTAMPTZ NOT NULL,
    ownership_state TEXT NOT NULL DEFAULT 'UNCERTAIN'
        CHECK (ownership_state = 'UNCERTAIN'),
    CHECK (timed_out_at >= published_at)
);
CREATE TRIGGER guard_output_attempt_timeouts
    BEFORE UPDATE OR DELETE ON output_attempt_timeouts
    FOR EACH ROW EXECUTE FUNCTION guard_output_registration_history();

-- Index the actual immutable publication clock, never updated_at/preparation time.
CREATE INDEX scheduler_outbox_published_deadline
    ON scheduler_outbox_messages(published_at, execution_id)
    WHERE status = 'PUBLISHED';

-- Current physical version projection
CREATE TABLE output_ready_pointers (
    dataset TEXT NOT NULL,
    partition_values JSONB NOT NULL,
    registration_id UUID NOT NULL,
    output_index INTEGER NOT NULL,
    generation BIGINT NOT NULL,
    promoted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (dataset, partition_values),
    FOREIGN KEY (registration_id, output_index)
        REFERENCES output_registration_outputs(registration_id, output_index)
);
CREATE TABLE output_ready_revision (
    id INTEGER PRIMARY KEY CHECK (id=1),
    revision BIGINT NOT NULL DEFAULT 0
);
INSERT INTO output_ready_revision(id) VALUES (1);

-- Fenced historical baselines and catalog
-- Owner-approved historical baseline: unknown producer provenance stays NULL.
-- Advisory lock (21410,22) is a stable coordination key, not a migration version.
CREATE SEQUENCE output_backfill_generation;
CREATE FUNCTION advance_output_backfill_fence() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock_shared(21410,22);
    PERFORM nextval('output_backfill_generation');
    RETURN NULL;
END; $$;
CREATE TRIGGER output_backfill_execution_fence BEFORE INSERT OR UPDATE OR DELETE ON job_execution_histories
    FOR EACH STATEMENT EXECUTE FUNCTION advance_output_backfill_fence();
CREATE TABLE output_ready_baselines (
    id UUID PRIMARY KEY,
    dataset TEXT NOT NULL,
    partition_values JSONB NOT NULL,
    payload JSONB NOT NULL,
    source_execution_id UUID CHECK(source_execution_id IS NULL),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TRIGGER guard_output_ready_baselines BEFORE UPDATE OR DELETE ON output_ready_baselines
    FOR EACH ROW EXECUTE FUNCTION guard_output_registration_history();
CREATE TABLE output_baseline_pointers (
    dataset TEXT NOT NULL,
    partition_values JSONB NOT NULL,
    baseline_id UUID NOT NULL REFERENCES output_ready_baselines(id),
    PRIMARY KEY(dataset,partition_values)
);
CREATE VIEW output_ready_catalog AS
SELECT p.dataset,p.partition_values,o.payload,r.execution_id,p.promoted_at
FROM output_ready_pointers p JOIN output_registration_outputs o USING(registration_id,output_index)
JOIN output_registrations r USING(registration_id)
UNION ALL
SELECT b.dataset,b.partition_values,b.payload,b.source_execution_id,b.created_at
FROM output_baseline_pointers p JOIN output_ready_baselines b ON b.id=p.baseline_id
WHERE NOT EXISTS(SELECT 1 FROM output_ready_pointers r WHERE r.dataset=p.dataset AND r.partition_values=p.partition_values);

-- Canonical work identity readiness
-- Work readiness uses the canonical graph identity, never a partition as a work key.
-- Partition pointers are only current physical-version projections.
CREATE TABLE output_work_ready (
    atomic_work_id TEXT NOT NULL REFERENCES atomic_work_slots(work_id),
    dataset TEXT NOT NULL,
    partition_values JSONB NOT NULL,
    registration_id UUID NOT NULL,
    output_index INTEGER NOT NULL,
    generation BIGINT NOT NULL,
    promoted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (atomic_work_id, dataset, partition_values),
    FOREIGN KEY (registration_id, output_index)
        REFERENCES output_registration_outputs(registration_id, output_index)
);

CREATE VIEW output_work_ready_catalog AS
SELECT w.atomic_work_id,r.atomic_attempt_id,r.execution_id,
       (r.payload->>'businessDate')::date AS business_date,
       w.dataset,w.partition_values,o.data_version,o.payload,w.generation,w.promoted_at
FROM output_work_ready w JOIN output_registrations r USING(registration_id)
JOIN output_registration_outputs o USING(registration_id,output_index)
JOIN atomic_work_attempts a ON a.id=r.atomic_attempt_id AND a.execution_id=r.execution_id
    AND a.work_id=w.atomic_work_id
JOIN output_ready_pointers p ON p.dataset=w.dataset AND p.partition_values=w.partition_values
JOIN output_registration_outputs current_output
    ON current_output.registration_id=p.registration_id AND current_output.output_index=p.output_index
WHERE current_output.data_version=o.data_version
AND NOT EXISTS (
    WITH RECURSIVE closure(attempt_id) AS (
        SELECT r.atomic_attempt_id
        UNION
        SELECT d.upstream_attempt_id FROM closure c
        JOIN atomic_work_dependencies d ON d.attempt_id=c.attempt_id
    )
    SELECT 1 FROM closure c JOIN atomic_work_attempts ancestor ON ancestor.id=c.attempt_id
    JOIN job_execution_histories e ON e.id=ancestor.execution_id
    JOIN output_completion_contracts enrolled ON enrolled.execution_id=e.id
    CROSS JOIN LATERAL jsonb_array_elements(coalesce(e.meta_json->'approvedInputs','[]'::jsonb)) binding
    WHERE binding->>'atomicWorkId' IS NULL OR NOT EXISTS (
        SELECT 1 FROM output_work_ready parent
        JOIN output_registrations registration ON registration.registration_id=parent.registration_id
        JOIN output_registration_outputs evidence
            ON evidence.registration_id=parent.registration_id AND evidence.output_index=parent.output_index
        JOIN output_ready_pointers physical ON physical.dataset=parent.dataset AND physical.partition_values=parent.partition_values
        JOIN output_registration_outputs current_parent
            ON current_parent.registration_id=physical.registration_id AND current_parent.output_index=physical.output_index
        WHERE parent.atomic_work_id=binding->>'atomicWorkId'
          AND registration.atomic_attempt_id::text=binding->>'atomicAttemptId'
          AND registration.payload->>'businessDate'=binding->>'businessDate'
          AND parent.dataset=binding->>'dataset' AND parent.partition_values=binding->'partition'
          AND evidence.data_version=binding->>'dataVersion'
          AND current_parent.data_version=evidence.data_version
    )
);

COMMENT ON VIEW output_work_ready_catalog IS
'READY by canonical work identity and approved attempt/version; replaced physical versions fail closed. Baselines cannot invent a work identity.';
