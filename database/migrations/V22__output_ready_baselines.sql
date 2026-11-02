-- Owner-approved historical baseline: unknown producer provenance stays NULL.
CREATE SEQUENCE output_backfill_generation;
CREATE FUNCTION advance_output_backfill_fence() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock_shared(21410,22);
    PERFORM nextval('output_backfill_generation');
    RETURN NULL;
END; $$;
CREATE TRIGGER output_backfill_execution_fence BEFORE INSERT OR UPDATE OR DELETE ON job_execution_histories
    FOR EACH STATEMENT EXECUTE FUNCTION advance_output_backfill_fence();
CREATE TABLE output_ready_baselines (
    id UUID PRIMARY KEY,
    dataset TEXT NOT NULL,
    partition_values JSONB NOT NULL,
    payload JSONB NOT NULL,
    source_execution_id UUID CHECK(source_execution_id IS NULL),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TRIGGER guard_output_ready_baselines BEFORE UPDATE OR DELETE ON output_ready_baselines
    FOR EACH ROW EXECUTE FUNCTION guard_output_registration_history();
CREATE TABLE output_baseline_pointers (
    dataset TEXT NOT NULL,
    partition_values JSONB NOT NULL,
    baseline_id UUID NOT NULL REFERENCES output_ready_baselines(id),
    PRIMARY KEY(dataset,partition_values)
);
CREATE VIEW output_ready_catalog AS
SELECT p.dataset,p.partition_values,o.payload,r.execution_id,p.promoted_at
FROM output_ready_pointers p JOIN output_registration_outputs o USING(registration_id,output_index)
JOIN output_registrations r USING(registration_id)
UNION ALL
SELECT b.dataset,b.partition_values,b.payload,b.source_execution_id,b.created_at
FROM output_baseline_pointers p JOIN output_ready_baselines b ON b.id=p.baseline_id
WHERE NOT EXISTS(SELECT 1 FROM output_ready_pointers r WHERE r.dataset=p.dataset AND r.partition_values=p.partition_values);
