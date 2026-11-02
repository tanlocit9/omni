-- Candidate forward migration. Review retained V19/V20 identities before applying.
ALTER TABLE output_completion_contracts ADD COLUMN generation BIGINT GENERATED ALWAYS AS IDENTITY;
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
