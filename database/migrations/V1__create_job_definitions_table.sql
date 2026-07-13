CREATE TABLE job_definitions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_id UUID,
    updated_by_id UUID,
    title VARCHAR(255) NOT NULL,
    source VARCHAR(255) NOT NULL,
    fallback_sources JSONB DEFAULT '[]' :: jsonb,
    job_type VARCHAR(50) NOT NULL,
    cron_expr VARCHAR(255),
    is_active BOOLEAN DEFAULT TRUE,
    next_run TIMESTAMP WITH TIME ZONE,
    config_json JSONB,
    CONSTRAINT uq_job_definition_source_type UNIQUE (source, job_type, cron_expr)
);

CREATE INDEX idx_job_definition_next_run ON job_definitions (next_run, is_active);