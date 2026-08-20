package com.omni.platform.modules.scheduler.entities;

import com.omni.platform.shared.entities.AuditableEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Tracks jobs blocked due to unmet dataset dependencies.
 * 
 * <p>When a job's ENFORCED dependencies are not satisfied, it enters BLOCKED state
 * and is tracked here for retry with exponential backoff. This allows the scheduler
 * to defer jobs without creating false FAILED execution history.
 * 
 * <p>Retry backoff sequence: 30s → 1m → 2m → 5m (capped)
 * 
 * <p>Each scheduler cycle checks blocked jobs and retries those whose nextRetryAt
 * has passed. If dependencies are still unmet, nextRetryAt is pushed forward with
 * exponential backoff.
 */
@Entity
@Table(
    name = "blocked_jobs",
    indexes = {
        @Index(name = "idx_blocked_jobs_next_retry", columnList = "next_retry_at"),
        @Index(name = "idx_blocked_jobs_job_name", columnList = "job_name")
    }
)
@Getter
@Setter
@NoArgsConstructor
public class BlockedJob extends AuditableEntity {
    
    /**
     * Job name from JobDefinition.
     */
    @Column(name = "job_name", nullable = false, length = 255)
    private String jobName;
    
    /**
     * Job type from JobDefinition.
     */
    @Column(name = "job_type", nullable = false, length = 100)
    private String jobType;
    
    /**
     * Execution ID that was blocked.
     * Used for correlation with job execution history if the job eventually runs.
     */
    @Column(name = "execution_id", nullable = false, length = 100)
    private String executionId;
    
    /**
     * Human-readable reason for blocking.
     * Example: "2 missing, 1 not ready"
     */
    @Column(name = "block_reason", nullable = false, length = 1000)
    private String blockReason;
    
    /**
     * JSON array of failed dependency checks.
     * Stored as JSON for detailed analysis without additional tables.
     */
    @Column(name = "failed_checks_json", columnDefinition = "TEXT")
    private String failedChecksJson;
    
    /**
     * When this job was first blocked.
     */
    @Column(name = "first_blocked_at", nullable = false)
    private Instant firstBlockedAt;
    
    /**
     * When to retry checking this job's dependencies.
     * Scheduler queries blocked_jobs WHERE next_retry_at <= NOW().
     */
    @Column(name = "next_retry_at", nullable = false)
    private Instant nextRetryAt;
    
    /**
     * Number of times this job has been retried.
     * Used to calculate exponential backoff.
     */
    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;
    
    /**
     * Maximum retry count before giving up.
     * Default: 20 retries (covers ~1 hour with exponential backoff)
     */
    @Column(name = "max_retries", nullable = false)
    private int maxRetries = 20;
    
    /**
     * Whether this blocked job has been resolved (dependencies satisfied).
     * When true, the job has been dispatched and this record is historical.
     */
    @Column(name = "resolved", nullable = false)
    private boolean resolved = false;
    
    /**
     * When this blocked job was resolved.
     */
    @Column(name = "resolved_at")
    private Instant resolvedAt;
    
    /**
     * Calculate next retry delay using exponential backoff.
     * 
     * <p>Backoff sequence:
     * - Retry 0: 30 seconds
     * - Retry 1: 1 minute
     * - Retry 2: 2 minutes
     * - Retry 3+: 5 minutes (capped)
     * 
     * @return delay in seconds
     */
    public long calculateNextRetryDelay() {
        return switch (retryCount) {
            case 0 -> 30;
            case 1 -> 60;
            case 2 -> 120;
            default -> 300; // Cap at 5 minutes
        };
    }
    
    /**
     * Check if this job has exceeded maximum retries.
     */
    public boolean hasExceededMaxRetries() {
        return retryCount >= maxRetries;
    }
    
    /**
     * Mark this blocked job as resolved.
     */
    public void markResolved() {
        this.resolved = true;
        this.resolvedAt = Instant.now();
    }
}
