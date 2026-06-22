-- =============================================
-- sync_job_log: Generic audit log per execution
-- =============================================
CREATE TABLE sync_job_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid (),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_id UUID,
    updated_by_id UUID,
    job_id UUID NOT NULL REFERENCES sync_job (id) ON DELETE CASCADE,
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
    -- { "symbol": "VNM", "exchange": "HOSE", ... }
    CONSTRAINT fk_sync_job_log_job FOREIGN KEY (job_id) REFERENCES sync_job (id) ON DELETE CASCADE
);

CREATE INDEX idx_sync_job_log_job_id ON sync_job_log (job_id);

CREATE INDEX idx_sync_job_log_status ON sync_job_log (status);

CREATE INDEX idx_sync_job_log_parent_id ON sync_job_log (parent_log_id)
WHERE
    parent_log_id IS NOT NULL;

CREATE INDEX idx_sync_job_log_symbol ON sync_job_log ((meta_json ->> 'symbol'))
WHERE
    meta_json ? 'symbol';