package com.omni.platform.modules.scheduler.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omni.platform.modules.scheduler.dependencies.DependencyCheckResult;
import com.omni.platform.modules.scheduler.dependencies.JobDependencyGuard.GuardResult;
import com.omni.platform.modules.scheduler.entities.BlockedJob;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.repositories.BlockedJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages the lifecycle of blocked jobs.
 *
 * <p>When the dependency guard blocks a job, this service:
 * <ol>
 *   <li>Creates or updates a {@link BlockedJob} record</li>
 *   <li>Calculates the next retry time using exponential backoff</li>
 *   <li>Marks the record as resolved when dependencies are satisfied</li>
 * </ol>
 *
 * <p>Backoff sequence: 30s → 60s → 120s → 300s (capped at 5 minutes)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlockedJobTracker {

    private final BlockedJobRepository blockedJobRepository;
    private final ObjectMapper objectMapper;

    /**
     * Record or update a blocked job.
     *
     * <p>If a BlockedJob record already exists for this job name (unresolved), it is updated
     * (retry count incremented, nextRetryAt pushed forward). Otherwise a new record is created.
     *
     * @param job         the job definition that was blocked
     * @param guardResult the result from the dependency guard (must be blocked)
     * @param executionId a new execution ID for correlation
     * @return the persisted BlockedJob
     */
    @Transactional
    public BlockedJob recordBlocked(JobDefinition job, GuardResult guardResult, String executionId) {
        String jobIdentifier = buildJobIdentifier(job);
        Optional<BlockedJob> existing = blockedJobRepository.findByJobNameAndResolvedFalse(jobIdentifier);

        if (existing.isPresent()) {
            return updateExistingBlock(existing.get(), guardResult);
        } else {
            return createNewBlock(job, jobIdentifier, guardResult, executionId);
        }
    }

    /**
     * Mark a blocked job as resolved (dependencies satisfied).
     *
     * <p>Called when the scheduler retries the job and the guard returns READY.
     *
     * @param job the job definition that was unblocked
     * @return true if a blocked record was found and resolved, false if none existed
     */
    @Transactional
    public boolean markResolved(JobDefinition job) {
        String jobIdentifier = buildJobIdentifier(job);
        Optional<BlockedJob> existing = blockedJobRepository.findByJobNameAndResolvedFalse(jobIdentifier);

        if (existing.isEmpty()) {
            log.debug("No active blocked record for jobIdentifier={}, nothing to resolve", jobIdentifier);
            return false;
        }

        BlockedJob blockedJob = existing.get();
        blockedJob.markResolved();
        blockedJobRepository.save(blockedJob);

        log.info("Resolved blocked job: jobIdentifier={} totalRetries={} blockedForSeconds={}",
            jobIdentifier,
            blockedJob.getRetryCount(),
            java.time.Duration.between(blockedJob.getFirstBlockedAt(), Instant.now()).getSeconds());

        return true;
    }

    /**
     * Find all blocked jobs that are ready for retry.
     *
     * <p>Returns jobs where {@code resolved = false}, {@code nextRetryAt <= now},
     * and {@code retryCount < maxRetries}.
     *
     * @param now current time
     * @return list of blocked jobs ready to re-check their dependencies
     */
    @Transactional(readOnly = true)
    public List<BlockedJob> findJobsReadyForRetry(Instant now) {
        return blockedJobRepository.findJobsReadyForRetry(now);
    }

    /**
     * Find all currently active blocked jobs.
     *
     * @return list of unresolved blocked jobs
     */
    @Transactional(readOnly = true)
    public List<BlockedJob> findAllActive() {
        return blockedJobRepository.findByResolvedFalse();
    }

    /**
     * Whether a job is currently blocked.
     *
     * @param job job definition to check
     * @return true if the job has an active (unresolved) blocked record
     */
    @Transactional(readOnly = true)
    public boolean isBlocked(JobDefinition job) {
        return blockedJobRepository.countByJobNameAndResolvedFalse(buildJobIdentifier(job)) > 0;
    }

    /**
     * Clean up old resolved records.
     *
     * @param cutoff records resolved before this instant will be deleted
     * @return number of records deleted
     */
    @Transactional
    public int cleanupResolved(Instant cutoff) {
        int deleted = blockedJobRepository.deleteResolvedJobsOlderThan(cutoff);
        if (deleted > 0) {
            log.info("Cleaned up {} resolved blocked job records older than {}", deleted, cutoff);
        }
        return deleted;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Build a stable, unique job identifier from the definition.
     *
     * <p>Uses jobType + source so the identifier is stable across restarts.
     * Title is human-readable but may not always be set.
     */
    private String buildJobIdentifier(JobDefinition job) {
        return job.getJobType().name() + "_" + job.getSource().name();
    }

    private BlockedJob createNewBlock(
        JobDefinition job,
        String jobIdentifier,
        GuardResult guardResult,
        String executionId
    ) {
        BlockedJob blockedJob = new BlockedJob();
        blockedJob.setJobName(jobIdentifier);
        blockedJob.setJobType(job.getJobType().name());
        blockedJob.setExecutionId(executionId != null ? executionId : UUID.randomUUID().toString());
        blockedJob.setBlockReason(guardResult.blockReason());
        blockedJob.setFailedChecksJson(serializeFailedChecks(guardResult.checks()));
        blockedJob.setFirstBlockedAt(Instant.now());

        long delaySecs = blockedJob.calculateNextRetryDelay();
        blockedJob.setNextRetryAt(Instant.now().plusSeconds(delaySecs));

        BlockedJob saved = blockedJobRepository.save(blockedJob);

        log.info("Blocked job created: jobIdentifier={} executionId={} reason={} nextRetryInSeconds={}",
            jobIdentifier,
            saved.getExecutionId(),
            guardResult.blockReason(),
            delaySecs);

        return saved;
    }

    private BlockedJob updateExistingBlock(BlockedJob blockedJob, GuardResult guardResult) {
        blockedJob.setRetryCount(blockedJob.getRetryCount() + 1);
        blockedJob.setBlockReason(guardResult.blockReason());
        blockedJob.setFailedChecksJson(serializeFailedChecks(guardResult.checks()));

        long delaySecs = blockedJob.calculateNextRetryDelay();
        blockedJob.setNextRetryAt(Instant.now().plusSeconds(delaySecs));

        BlockedJob saved = blockedJobRepository.save(blockedJob);

        if (saved.hasExceededMaxRetries()) {
            log.warn("Blocked job exceeded max retries: jobName={} retryCount={} maxRetries={} reason={}",
                saved.getJobName(),
                saved.getRetryCount(),
                saved.getMaxRetries(),
                guardResult.blockReason());
        } else {
            log.info("Blocked job updated: jobName={} retryCount={} nextRetryInSeconds={} reason={}",
                saved.getJobName(),
                saved.getRetryCount(),
                delaySecs,
                guardResult.blockReason());
        }

        return saved;
    }

    private String serializeFailedChecks(List<DependencyCheckResult> failedChecks) {
        if (failedChecks == null || failedChecks.isEmpty()) {
            return "[]";
        }
        try {
            List<Map<String, Object>> simplified = failedChecks.stream()
                .map(r -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("status", r.getStatus().name());
                    entry.put("reason", r.getReason().orElse(null));
                    entry.put("dataset", r.getDatasetRef() != null ? r.getDatasetRef().getDataset() : "unknown");
                    entry.put("partition", r.getDatasetRef() != null ? r.getDatasetRef().getPartition() : Map.of());
                    return entry;
                })
                .toList();
            return objectMapper.writeValueAsString(simplified);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize failed checks: {}", e.getMessage());
            return "[]";
        }
    }
}
