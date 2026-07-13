-- =============================================
-- job_execution_histories: Generic audit log per execution
-- =============================================
CREATE TABLE job_execution_histories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_id UUID,
    updated_by_id UUID,
    job_id UUID NOT NULL,
    used_source VARCHAR(255) NOT NULL,
    attempt INTEGER NOT NULL DEFAULT 1,
    parent_log_id UUID,
    status VARCHAR(255) NOT NULL,
    triggered_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP WITH TIME ZONE,
    finished_at TIMESTAMP WITH TIME ZONE,
    records_synced INTEGER,
    records_skipped INTEGER,
    new_offset VARCHAR(255),
    error TEXT,
    meta_json JSONB,
    -- { "symbolKey": "VNM:HOSE", "exchange": "HOSE", ... }
    CONSTRAINT fk_job_execution_history_job FOREIGN KEY (job_id) REFERENCES job_definitions (id) ON DELETE CASCADE
);

CREATE INDEX idx_job_execution_history_job_id ON job_execution_histories (job_id);

CREATE INDEX idx_job_execution_history_status ON job_execution_histories (status);

CREATE INDEX idx_job_execution_history_parent_id ON job_execution_histories (parent_log_id)
WHERE
    parent_log_id IS NOT NULL;

CREATE INDEX idx_job_execution_history_symbol ON job_execution_histories ((meta_json ->> 'symbolKey'))
WHERE
    meta_json ? 'symbolKey';

CREATE INDEX idx_job_execution_history_symbol_offset ON job_execution_histories (
    job_id,
    (meta_json ->> 'symbolKey'),
    finished_at DESC
)
WHERE
    new_offset IS NOT NULL;