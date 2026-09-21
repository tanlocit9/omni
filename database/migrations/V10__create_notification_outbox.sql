CREATE TABLE notification_outbox_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_id UUID,
    updated_by_id UUID,
    provider VARCHAR(32) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    notification_kind VARCHAR(64) NOT NULL,
    schema_version INTEGER NOT NULL,
    payload TEXT NOT NULL,
    deduplication_key VARCHAR(512) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    claim_token UUID,
    claimed_by VARCHAR(255),
    claim_until TIMESTAMP WITH TIME ZONE,
    sent_at TIMESTAMP WITH TIME ZONE,
    last_error TEXT,
    CONSTRAINT uq_notification_outbox_delivery
        UNIQUE (provider, channel, deduplication_key),
    CONSTRAINT chk_notification_outbox_status
        CHECK (status IN ('PENDING', 'SENT', 'DEAD')),
    CONSTRAINT chk_notification_outbox_schema_version
        CHECK (schema_version > 0),
    CONSTRAINT chk_notification_outbox_attempts
        CHECK (attempts >= 0),
    CONSTRAINT chk_notification_outbox_claim_state CHECK (
        (claim_token IS NULL AND claimed_by IS NULL AND claim_until IS NULL)
        OR
        (claim_token IS NOT NULL AND claimed_by IS NOT NULL AND claim_until IS NOT NULL)
    )
);

CREATE INDEX idx_notification_outbox_dispatch
    ON notification_outbox_messages (available_at, created_at, id)
    WHERE status = 'PENDING';

CREATE TABLE notification_provider_rate_limits (
    provider VARCHAR(32) PRIMARY KEY,
    next_permitted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
