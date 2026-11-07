-- I10 schema stage 5/5. Apply V19-V23 in order from a reconciled V18 baseline.
-- V1-V18 and runtime feature defaults remain unchanged.

-- Canonical work identity readiness
-- Work readiness uses the canonical graph identity, never a partition as a work key.
-- Partition pointers are only latest-output discovery projections.
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

-- Current row ownership, independent of the physical container version.
CREATE TABLE output_ready_rows (
    dataset TEXT NOT NULL,
    partition_values JSONB NOT NULL,
    row_key JSONB NOT NULL CHECK(jsonb_typeof(row_key)='object'),
    registration_id UUID NOT NULL,
    output_index INTEGER NOT NULL,
    generation BIGINT NOT NULL,
    PRIMARY KEY(dataset,partition_values,row_key),
    FOREIGN KEY(registration_id,output_index) REFERENCES output_registration_outputs(registration_id,output_index)
);

CREATE VIEW output_work_ready_catalog AS
SELECT w.atomic_work_id,r.atomic_attempt_id,r.execution_id,
       (r.payload->>'businessDate')::date AS business_date,
       w.dataset,w.partition_values,o.data_version,o.payload,w.generation,w.promoted_at
FROM output_work_ready w JOIN output_registrations r USING(registration_id)
JOIN output_registration_outputs o USING(registration_id,output_index)
JOIN atomic_work_attempts a ON a.id=r.atomic_attempt_id AND a.execution_id=r.execution_id
    AND a.work_id=w.atomic_work_id
WHERE o.payload->'workScope' IS NOT NULL
AND NOT EXISTS (
    SELECT 1 FROM jsonb_array_elements(o.payload->'workScope'->'rowKeys') k
    LEFT JOIN output_ready_rows current_row
        ON current_row.dataset=w.dataset AND current_row.partition_values=w.partition_values AND current_row.row_key=k
    WHERE current_row.registration_id IS DISTINCT FROM w.registration_id
       OR current_row.output_index IS DISTINCT FROM w.output_index
);

COMMENT ON VIEW output_work_ready_catalog IS
'READY covers the registered work rows in canonical Parquet. Unrelated rows do not invalidate READY; overlapping writes supersede the prior proof. Approved input versions remain immutable provenance, not a claim of transitive freshness.';
