ALTER TABLE job_definitions
    ADD COLUMN claim_token UUID,
    ADD COLUMN claimed_by VARCHAR(255),
    ADD COLUMN claimed_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN claim_until TIMESTAMP WITH TIME ZONE;

ALTER TABLE job_definitions
    ADD CONSTRAINT ck_job_definition_claim_state
    CHECK (
        (claim_token IS NULL AND claimed_by IS NULL AND claimed_at IS NULL AND claim_until IS NULL)
        OR
        (claim_token IS NOT NULL AND claimed_by IS NOT NULL AND claimed_at IS NOT NULL AND claim_until IS NOT NULL)
    );

CREATE INDEX idx_job_definition_claimable
    ON job_definitions (next_run, claim_until, id)
    WHERE is_active = TRUE;
