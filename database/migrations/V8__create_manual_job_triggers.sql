-- Durable operator-trigger idempotency and audit record.
CREATE TABLE manual_job_triggers
(
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by_id      UUID,
    updated_by_id      UUID,
    job_definition_id  UUID NOT NULL,
    execution_id       UUID,
    actor              VARCHAR(200) NOT NULL,
    idempotency_key    VARCHAR(128) NOT NULL,
    reason             VARCHAR(500) NOT NULL,
    parameters_json    JSONB NOT NULL DEFAULT '{}'::jsonb,
    state              VARCHAR(32) NOT NULL,
    block_reason       TEXT,
    error              TEXT,
    requested_at       TIMESTAMPTZ NOT NULL,
    resolved_at        TIMESTAMPTZ,
    CONSTRAINT fk_manual_job_trigger_definition
        FOREIGN KEY (job_definition_id) REFERENCES job_definitions (id) ON DELETE CASCADE,
    CONSTRAINT fk_manual_job_trigger_execution
        FOREIGN KEY (execution_id) REFERENCES job_execution_histories (id) ON DELETE SET NULL,
    CONSTRAINT uq_manual_job_trigger_actor_key UNIQUE (actor, idempotency_key)
);

CREATE INDEX idx_manual_job_triggers_definition_requested
    ON manual_job_triggers (job_definition_id, requested_at DESC);

CREATE INDEX idx_manual_job_triggers_execution
    ON manual_job_triggers (execution_id)
    WHERE execution_id IS NOT NULL;

COMMENT ON TABLE manual_job_triggers IS
    'Audited, idempotent operator requests. BLOCKED requests do not create fake job executions.';
