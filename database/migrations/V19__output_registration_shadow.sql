-- I10.a shadow evidence only: none of these rows is a READY pointer.
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
    CHECK (atomic_work_id ~ '^work:[0-9a-f]{64}$')
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
