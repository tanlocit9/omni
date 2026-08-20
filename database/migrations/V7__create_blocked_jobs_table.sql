-- V7: Create blocked_jobs table for dependency guard tracking
--
-- When a job's ENFORCED dataset dependencies are not satisfied, it is deferred
-- to this table instead of creating a false FAILED execution history entry.
-- The scheduler retries blocked jobs with exponential backoff.
--
-- Backoff sequence: 30s → 60s → 120s → 300s (capped)

CREATE TABLE IF NOT EXISTS blocked_jobs
(
    -- Primary key from AuditableEntity (UUID)
    id                UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,

    -- Job identification
    job_name          VARCHAR(255) NOT NULL,           -- "JOBTYPE_SOURCE" identifier
    job_type          VARCHAR(100) NOT NULL,           -- JobDefinition.JobType name
    execution_id      VARCHAR(100) NOT NULL,           -- UUID for correlation

    -- Block details
    block_reason      VARCHAR(1000) NOT NULL,          -- Human-readable summary
    failed_checks_json TEXT,                           -- JSON array of failed DependencyCheckResult

    -- Timing
    first_blocked_at  TIMESTAMPTZ  NOT NULL,           -- When this job was first blocked
    next_retry_at     TIMESTAMPTZ  NOT NULL,           -- When to re-check dependencies

    -- Retry tracking
    retry_count       INT          NOT NULL DEFAULT 0,
    max_retries       INT          NOT NULL DEFAULT 20,

    -- Resolution
    resolved          BOOLEAN      NOT NULL DEFAULT FALSE,
    resolved_at       TIMESTAMPTZ,                     -- When dependencies were satisfied

    -- Audit columns from AuditableEntity
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by_id     UUID,
    updated_by_id     UUID
);

-- Index for scheduler retry query: WHERE resolved = FALSE AND next_retry_at <= NOW()
CREATE INDEX IF NOT EXISTS idx_blocked_jobs_next_retry
    ON blocked_jobs (next_retry_at)
    WHERE resolved = FALSE;

-- Index for looking up by job name
CREATE INDEX IF NOT EXISTS idx_blocked_jobs_job_name
    ON blocked_jobs (job_name)
    WHERE resolved = FALSE;

-- Comment for documentation
COMMENT ON TABLE blocked_jobs IS
    'Tracks jobs deferred due to unmet ENFORCED dataset dependencies. '
    'Retried with exponential backoff (30s, 60s, 120s, 300s). '
    'Resolved = TRUE once dependencies are satisfied and job is dispatched.';

COMMENT ON COLUMN blocked_jobs.job_name IS
    'Stable identifier: JobType.name() + "_" + DataSource.name(). '
    'Used to find active blocked record for a given job.';

COMMENT ON COLUMN blocked_jobs.failed_checks_json IS
    'JSON array of failed DependencyCheckResult entries. '
    'Schema: [{status, reason, dataset, partition}]';

COMMENT ON COLUMN blocked_jobs.next_retry_at IS
    'Scheduler re-checks this job when NOW() >= next_retry_at. '
    'Exponential backoff: retry 0=30s, 1=60s, 2=120s, 3+=300s.';
