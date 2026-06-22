-- =============================================
-- symbol: Master list of tradeable symbols
-- =============================================
CREATE TABLE symbol (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    code VARCHAR(50) NOT NULL,
    exchange VARCHAR(50) NOT NULL,
    is_active BOOLEAN DEFAULT TRUE,
    meta_json JSONB,
    CONSTRAINT uq_symbol UNIQUE (code, exchange)
);

-- =============================================
-- sync_job_symbol: Per-symbol state for a job
-- =============================================
CREATE TABLE sync_job_symbol (
    job_id UUID NOT NULL REFERENCES sync_job (id) ON DELETE CASCADE,
    symbol_id UUID NOT NULL REFERENCES symbol (id) ON DELETE CASCADE,
    is_active BOOLEAN DEFAULT TRUE,
    meta_json JSONB,
    last_offset TIMESTAMP WITH TIME ZONE,
    -- e.g. "2025-06-20", updated after each successful sync
    last_synced_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (job_id, symbol_id)
);

CREATE INDEX idx_sync_job_symbol_job_id ON sync_job_symbol (job_id);