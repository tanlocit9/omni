-- Backfill requests reuse the existing atomic-work graph and immutable membership.
ALTER TABLE atomic_work_requests DROP CONSTRAINT atomic_work_requests_mode_check;
ALTER TABLE atomic_work_requests ADD CONSTRAINT atomic_work_requests_mode_check
    CHECK (mode IN ('SCHEDULED', 'MANUAL', 'BACKFILL'));
