-- I10 schema stage 2/5. Apply V19-V23 in order from a reconciled V18 baseline.
-- V1-V18 and runtime feature defaults remain unchanged.

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
