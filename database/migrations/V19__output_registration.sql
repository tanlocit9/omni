-- I10 schema stage 1/5. Apply V19-V23 in order from a reconciled V18 baseline.
-- V1-V18 and runtime feature defaults remain unchanged.

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

CREATE OR REPLACE FUNCTION guard_output_registration_history() RETURNS TRIGGER LANGUAGE plpgsql AS $$
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
