-- =============================================
-- sectors: Canonical Java-owned sector taxonomy and external source mapping
-- =============================================
CREATE TABLE sectors (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(100) NOT NULL,
    name_vi VARCHAR(255),
    name_en VARCHAR(255),
    taxonomy VARCHAR(50) NOT NULL,
    taxonomy_level INTEGER NOT NULL,
    source_code VARCHAR(100) NOT NULL,
    parent_id UUID NULL REFERENCES sector(id),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    meta_json JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_sector_code UNIQUE (code),
    CONSTRAINT uq_sector_source UNIQUE (taxonomy, taxonomy_level, source_code)
);

CREATE INDEX idx_sector_parent_id ON sectors(parent_id);

CREATE INDEX idx_sector_active_code ON sectors(is_active, code);

ALTER TABLE
    symbols
ADD
    COLUMN sector_id UUID NULL REFERENCES sectors(id);

CREATE INDEX idx_symbol_sector_id ON symbols(sector_id);

ALTER TABLE
    symbols DROP CONSTRAINT IF EXISTS chk_symbol_sector_uppercase;