-- Retire the unused pre-outbox tracker only when no historical rows remain.
-- Lock before checking so a concurrent legacy writer cannot race the removal.
LOCK TABLE blocked_jobs IN ACCESS EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM blocked_jobs) THEN
        RAISE EXCEPTION 'blocked_jobs contains rows; export or resolve retained history before retirement';
    END IF;
END;
$$;

DROP TABLE blocked_jobs RESTRICT;
