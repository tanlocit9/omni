CREATE TABLE sync_job (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_id UUID,
    updated_by_id UUID,
    source VARCHAR(255) NOT NULL,
    fallback_sources JSONB DEFAULT '[]' :: jsonb,
    job_type VARCHAR(50) NOT NULL,
    cron_expr VARCHAR(255),
    is_active BOOLEAN DEFAULT TRUE,
    next_run TIMESTAMP WITH TIME ZONE,
    config_json JSONB,
    CONSTRAINT uq_sync_job_source_table UNIQUE (source, job_type)
);

CREATE INDEX idx_sync_job_next_run ON sync_job (next_run, is_active);