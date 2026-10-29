-- No existing definition is disabled by migration. Apply reviewed consolidation via service.
ALTER TABLE job_definitions ADD COLUMN schedule_enabled BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE job_definitions ADD COLUMN canonical_definition_id UUID REFERENCES job_definitions(id);
ALTER TABLE job_definitions ADD CONSTRAINT definition_canonical_not_self CHECK(canonical_definition_id IS DISTINCT FROM id);
CREATE TABLE job_definition_consolidations (
    id UUID PRIMARY KEY,
    retained_definition_id UUID NOT NULL REFERENCES job_definitions(id),
    inventory_json JSONB NOT NULL CHECK(jsonb_typeof(inventory_json)='array'),
    applied_config JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    state TEXT NOT NULL CHECK(state IN ('APPLIED','ROLLED_BACK'))
);
CREATE INDEX job_definition_consolidations_retained ON job_definition_consolidations(retained_definition_id,state);
-- Offset readers can include preserved historical definition IDs after consolidation.
CREATE VIEW job_definition_history_aliases AS
    SELECT id AS definition_id,coalesce(canonical_definition_id,id) AS retained_definition_id FROM job_definitions;
CREATE FUNCTION guard_definition_consolidation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' OR (TG_OP='UPDATE' AND
        (OLD.state<>'APPLIED' OR NEW.state<>'ROLLED_BACK' OR
         (to_jsonb(NEW)-'state') IS DISTINCT FROM (to_jsonb(OLD)-'state'))) THEN
        RAISE EXCEPTION 'Definition consolidation inventory is immutable';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER guard_definition_consolidation BEFORE UPDATE OR DELETE ON job_definition_consolidations
    FOR EACH ROW EXECUTE FUNCTION guard_definition_consolidation();
