-- Work readiness uses the canonical graph identity, never a partition as a work key.
-- V19-V22 remain unchanged. Partition pointers are only current physical-version projections.
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
-- Carry forward only pointers already promoted; do not enroll historical baselines.
INSERT INTO output_work_ready
SELECT r.atomic_work_id,p.dataset,p.partition_values,p.registration_id,p.output_index,p.generation,p.promoted_at
FROM output_ready_pointers p JOIN output_registrations r USING(registration_id);

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
