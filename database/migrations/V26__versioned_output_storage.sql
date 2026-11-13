-- Optional immutable backend coordinates. Logical work/version/READY authority is unchanged.
ALTER TABLE output_completion_contracts ADD COLUMN storage_policy TEXT NOT NULL DEFAULT 'CANONICAL'
    CHECK (storage_policy IN ('CANONICAL','VERSIONED'));

CREATE TABLE output_storage_heads (
    dataset TEXT NOT NULL,
    partition_values JSONB NOT NULL CHECK (jsonb_typeof(partition_values)='object'),
    storage_version_id VARCHAR(1024) NOT NULL CHECK (length(storage_version_id)>0 AND storage_version_id<>'null'),
    generation BIGINT NOT NULL CHECK (generation>=0),
    source_execution_id UUID REFERENCES output_completion_contracts(execution_id),
    baseline_version_id VARCHAR(1024),
    baseline_generation BIGINT,
    baseline_checksum TEXT,
    baseline_total_bytes BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(dataset,partition_values),
    CHECK ((baseline_version_id IS NULL)=(baseline_generation IS NULL)),
    CHECK ((baseline_version_id IS NULL)=(baseline_checksum IS NULL)),
    CHECK ((baseline_version_id IS NULL)=(baseline_total_bytes IS NULL)),
    CHECK (baseline_checksum IS NULL OR baseline_checksum ~ '^sha256:[0-9a-f]{64}$'),
    CHECK (baseline_total_bytes IS NULL OR baseline_total_bytes>0),
    CHECK (baseline_generation IS NULL OR baseline_generation BETWEEN 0 AND generation)
);

CREATE OR REPLACE FUNCTION guard_output_storage_head() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Physical storage history cannot be deleted'; END IF;
    IF TG_OP='UPDATE' AND (
        NEW.dataset IS DISTINCT FROM OLD.dataset OR NEW.partition_values IS DISTINCT FROM OLD.partition_values
        OR NEW.baseline_version_id IS DISTINCT FROM OLD.baseline_version_id
        OR NEW.baseline_generation IS DISTINCT FROM OLD.baseline_generation
        OR NEW.baseline_checksum IS DISTINCT FROM OLD.baseline_checksum
        OR NEW.baseline_total_bytes IS DISTINCT FROM OLD.baseline_total_bytes
        OR NEW.generation<=OLD.generation) THEN
        RAISE EXCEPTION 'Physical storage head must advance without rewriting its baseline';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER guard_output_storage_head BEFORE UPDATE OR DELETE ON output_storage_heads
    FOR EACH ROW EXECUTE FUNCTION guard_output_storage_head();

CREATE TABLE output_storage_write_bases (
    execution_id UUID PRIMARY KEY REFERENCES output_completion_contracts(execution_id),
    bases JSONB NOT NULL CHECK (jsonb_typeof(bases)='array' AND jsonb_array_length(bases) BETWEEN 1 AND 32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TRIGGER guard_output_storage_write_bases BEFORE UPDATE OR DELETE ON output_storage_write_bases
    FOR EACH ROW EXECUTE FUNCTION guard_output_registration_history();

-- Rejected terminal completions are evidence, never accepted output registrations.
CREATE TABLE output_completion_rejections (
    event_id UUID NOT NULL,
    payload_sha256 CHAR(64) NOT NULL CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    execution_id UUID NOT NULL REFERENCES output_completion_contracts(execution_id),
    atomic_attempt_id UUID NOT NULL REFERENCES atomic_work_attempts(id),
    terminal_status TEXT NOT NULL CHECK (terminal_status IN ('SUCCESS','FAILED')),
    reason TEXT NOT NULL CHECK (reason IN ('TERMINAL_SUCCESS_CONFLICT','TERMINAL_FAILED_LATE')),
    payload TEXT NOT NULL CHECK (octet_length(payload) BETWEEN 2 AND 262144),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(event_id,payload_sha256)
);
CREATE INDEX idx_output_completion_rejections_execution ON output_completion_rejections(execution_id,created_at);
CREATE TRIGGER guard_output_completion_rejections BEFORE UPDATE OR DELETE ON output_completion_rejections
    FOR EACH ROW EXECUTE FUNCTION guard_output_registration_history();

-- Actual VERSIONED READY promotions remain readable by frozen attempt after refresh.
-- Registration/SUCCESS alone must never populate this history.
CREATE TABLE output_work_ready_history (
    registration_id UUID NOT NULL,
    output_index INTEGER NOT NULL,
    promoted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (registration_id, output_index),
    FOREIGN KEY (registration_id, output_index)
        REFERENCES output_registration_outputs(registration_id, output_index)
);
CREATE TRIGGER guard_output_work_ready_history BEFORE UPDATE OR DELETE ON output_work_ready_history
    FOR EACH ROW EXECUTE FUNCTION guard_output_registration_history();

CREATE VIEW output_work_ready_attempt_catalog AS
SELECT * FROM output_work_ready_catalog
UNION ALL
SELECT r.atomic_work_id,r.atomic_attempt_id,r.execution_id,
       (r.payload->>'businessDate')::date AS business_date,
       o.dataset,o.partition_values,o.data_version,o.payload,c.generation,h.promoted_at
FROM output_work_ready_history h
JOIN output_registrations r USING(registration_id)
JOIN output_registration_outputs o USING(registration_id,output_index)
JOIN output_completion_contracts c ON c.execution_id=r.execution_id AND c.atomic_attempt_id=r.atomic_attempt_id
JOIN atomic_work_attempts a ON a.id=r.atomic_attempt_id AND a.execution_id=r.execution_id AND a.work_id=r.atomic_work_id
JOIN job_execution_histories e ON e.id=r.execution_id AND e.status='SUCCESS'
WHERE c.storage_policy='VERSIONED' AND o.payload->>'storageVersionId' IS NOT NULL
AND NOT EXISTS (SELECT 1 FROM output_attempt_timeouts t WHERE t.execution_id=r.execution_id)
AND NOT EXISTS (
    SELECT 1 FROM output_work_ready_catalog current_work
    WHERE current_work.atomic_attempt_id=r.atomic_attempt_id
      AND current_work.dataset=o.dataset AND current_work.partition_values=o.partition_values
);
