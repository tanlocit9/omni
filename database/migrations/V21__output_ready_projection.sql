-- I10 schema stage 3/5. Apply V19-V23 in order from a reconciled V18 baseline.
-- V1-V18 and runtime feature defaults remain unchanged.

-- Latest registered output projection for legacy/catalog discovery
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
