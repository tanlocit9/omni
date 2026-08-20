package com.omni.platform.modules.scheduler.repositories;

import com.omni.platform.modules.scheduler.entities.BlockedJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository for managing blocked jobs.
 * 
 * <p>Blocked jobs are created when ENFORCED dataset dependencies are not satisfied.
 * The scheduler queries for jobs ready to retry and checks their dependencies again.
 */
@Repository
public interface BlockedJobRepository extends JpaRepository<BlockedJob, Long> {
    
    /**
     * Find active (unresolved) blocked job for a specific job name.
     * 
     * <p>There should only be one active blocked record per job at a time.
     * If found, update it rather than creating a duplicate.
     */
    Optional<BlockedJob> findByJobNameAndResolvedFalse(String jobName);
    
    /**
     * Find all blocked jobs ready for retry.
     * 
     * <p>Returns jobs where:
     * - resolved = false (still blocked)
     * - nextRetryAt <= now (retry time has arrived)
     * - retryCount < maxRetries (haven't exceeded max retries)
     * 
     * <p>Ordered by nextRetryAt to retry oldest blocks first.
     */
    @Query("""
        SELECT bj FROM BlockedJob bj
        WHERE bj.resolved = false
        AND bj.nextRetryAt <= :now
        AND bj.retryCount < bj.maxRetries
        ORDER BY bj.nextRetryAt ASC
    """)
    List<BlockedJob> findJobsReadyForRetry(@Param("now") Instant now);
    
    /**
     * Find all active blocked jobs (not resolved).
     */
    List<BlockedJob> findByResolvedFalse();
    
    /**
     * Count active blocked jobs for a specific job name.
     */
    long countByJobNameAndResolvedFalse(String jobName);
    
    /**
     * Count total active blocked jobs.
     */
    long countByResolvedFalse();
    
    /**
     * Delete old resolved blocked jobs.
     * 
     * <p>Cleanup query to remove historical records after retention period.
     * Run periodically to prevent unbounded table growth.
     */
    @Modifying
    @Query("""
        DELETE FROM BlockedJob bj
        WHERE bj.resolved = true
        AND bj.resolvedAt < :cutoffDate
    """)
    int deleteResolvedJobsOlderThan(@Param("cutoffDate") Instant cutoffDate);
    
    /**
     * Find blocked jobs that have exceeded max retries.
     * 
     * <p>These jobs should be logged as permanently blocked and potentially
     * moved to a dead-letter queue or manual intervention queue.
     */
    @Query("""
        SELECT bj FROM BlockedJob bj
        WHERE bj.resolved = false
        AND bj.retryCount >= bj.maxRetries
        ORDER BY bj.firstBlockedAt ASC
    """)
    List<BlockedJob> findJobsExceededMaxRetries();
}
