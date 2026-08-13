CREATE TABLE scheduler_outbox_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_id UUID,
    updated_by_id UUID,
    execution_id UUID NOT NULL,
    message_index INTEGER NOT NULL,
    topic VARCHAR(255) NOT NULL,
    message_key VARCHAR(512),
    payload TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    claim_token UUID,
    claimed_by VARCHAR(255),
    claim_until TIMESTAMP WITH TIME ZONE,
    published_at TIMESTAMP WITH TIME ZONE,
    last_error TEXT,
    CONSTRAINT fk_scheduler_outbox_execution
        FOREIGN KEY (execution_id) REFERENCES job_execution_histories (id) ON DELETE CASCADE,
    CONSTRAINT uq_scheduler_outbox_execution_message UNIQUE (execution_id, message_index),
    CONSTRAINT chk_scheduler_outbox_claim_state CHECK (
        (claim_token IS NULL AND claimed_by IS NULL AND claim_until IS NULL)
        OR
        (claim_token IS NOT NULL AND claimed_by IS NOT NULL AND claim_until IS NOT NULL)
    )
);

CREATE INDEX idx_scheduler_outbox_dispatch
    ON scheduler_outbox_messages (available_at, created_at, id)
    WHERE status = 'PENDING';

