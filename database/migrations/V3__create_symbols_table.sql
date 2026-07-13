-- =============================================
-- symbols: Master list of tradeable symbols
-- =============================================
CREATE TABLE symbols (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    exchange VARCHAR(50) NOT NULL,
    code VARCHAR(50) NOT NULL,
    is_active BOOLEAN DEFAULT TRUE,
    meta_json JSONB,
    CONSTRAINT uq_symbol UNIQUE (exchange, code)
);

ALTER TABLE
    symbols
ADD
    CONSTRAINT chk_symbol_sector_uppercase CHECK (
        meta_json ->> 'sector' IS NULL
        OR meta_json ->> 'sector' = UPPER(meta_json ->> 'sector')
    );