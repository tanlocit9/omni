package com.omni.platform.modules.scheduler;

import com.omni.platform.modules.scheduler.dependencies.JobDependencyContextFactory;
import com.omni.platform.modules.scheduler.dependencies.JobDependencyGuard;
import com.omni.platform.modules.scheduler.dependencies.JobDependencyGuard.GuardResult;
import com.omni.platform.modules.scheduler.dependencies.JobExecutionContext;
import com.omni.platform.modules.scheduler.entities.BlockedJob;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.producers.JobProducerRegistry;
import com.omni.platform.modules.scheduler.repositories.JobDefinitionRepository;
import com.omni.platform.modules.scheduler.repositories.SchedulerClaim;
import com.omni.platform.modules.scheduler.services.BlockedJobTracker;
import com.omni.platform.modules.scheduler.services.SchedulerClaimService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Core scheduler that claims due jobs and dispatches them via Kafka producers.
 *
 * <p>Each scheduler cycle performs two passes:
 * <ol>
 *   <li><b>Blocked job retry pass</b>: Checks blocked jobs whose {@code nextRetryAt} has
 *       arrived; re-evaluates their dependencies and dispatches if resolved.</li>
 *   <li><b>New job claim pass</b>: Claims newly due jobs from the database, runs the
 *       dependency guard, and dispatches or records as blocked.</li>
 * </ol>
 *
 * <p>The dependency guard enforces ENFORCED dataset dependencies. Jobs with unmet
 * ENFORCED dependencies are deferred to {@link BlockedJobTracker} with exponential
 * backoff (30s → 60s → 120s → 300s). DOCUMENTATION_ONLY dependency failures emit
 * warnings but do not block execution.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobScheduler {

    private final JobDefinitionRepository jobDefinitionRepository;
    private final JobProducerRegistry jobProducerRegistry;
    private final SchedulerClaimService schedulerClaimService;
    private final JobDependencyGuard jobDependencyGuard;
    private final JobDependencyContextFactory dependencyContextFactory;
    private final BlockedJobTracker blockedJobTracker;

    @Scheduled(fixedDelayString = "${app.scheduler.global.fixedDelayString:30000}")
    public void scan() {
        Instant now = Instant.now();

        // Pass 1: Retry blocked jobs whose nextRetryAt has arrived
        retryBlockedJobs(now);

        // Pass 2: Claim and dispatch newly due jobs
        claimAndDispatchDueJobs(now);
    }

    // -------------------------------------------------------------------------
    // Pass 1: Retry blocked jobs
    // -------------------------------------------------------------------------

    private void retryBlockedJobs(Instant now) {
        List<BlockedJob> readyForRetry = blockedJobTracker.findJobsReadyForRetry(now);

        if (readyForRetry.isEmpty()) {
            log.debug("No blocked jobs ready for retry at {}", now);
            return;
        }

        log.info("Retrying {} blocked job(s)", readyForRetry.size());

        for (BlockedJob blockedJob : readyForRetry) {
            try {
                retryBlockedJob(blockedJob, now);
            } catch (Exception e) {
                log.error("Failed to retry blocked job [{}]: {}", blockedJob.getJobName(), e.getMessage(), e);
            }
        }
    }

    private void retryBlockedJob(BlockedJob blockedJob, Instant now) {
        // Find the corresponding job definition by type+source encoded in jobName
        List<JobDefinition> candidates = jobDefinitionRepository.findAll().stream()
            .filter(j -> Boolean.TRUE.equals(j.getIsActive())
                && (j.getJobType().name() + "_" + j.getSource().name()).equals(blockedJob.getJobName()))
            .toList();

        if (candidates.isEmpty()) {
            log.warn("No active job definition found for blocked job: {}", blockedJob.getJobName());
            return;
        }

        for (JobDefinition job : candidates) {
            JobExecutionContext context = dependencyContextFactory.create(job);
            String executionId = context.executionId();
            GuardResult guardResult = jobDependencyGuard.checkDependencies(context);

            if (guardResult.canExecute()) {
                // Dependencies resolved - re-claim and dispatch
                List<SchedulerClaim> claims = schedulerClaimService.claimDueJobs(now);
                SchedulerClaim matchingClaim = claims.stream()
                    .filter(c -> c.jobDefinitionId().equals(job.getId()))
                    .findFirst()
                    .orElse(null);

                if (matchingClaim != null) {
                    JobDefinition claimedJob = jobDefinitionRepository
                            .findById(matchingClaim.jobDefinitionId())
                            .orElseThrow(() -> new IllegalStateException(
                                    "Claimed job definition no longer exists: "
                                            + matchingClaim.jobDefinitionId()));
                    blockedJobTracker.markResolved(claimedJob);
                    log.info(
                        "Blocked job resolved, dispatching: jobName={} executionId={} approvedInputVersions={}",
                        blockedJob.getJobName(), executionId, guardResult.approvedInputVersions());
                    jobProducerRegistry.getProducer(claimedJob.getJobType()).prepareDispatch(
                            claimedJob,
                            matchingClaim,
                            now,
                            guardResult.approvedInputVersions());
                } else {
                    // Job wasn't due yet per scheduler; mark resolved, will be picked up next natural cycle
                    log.info("Blocked job dependencies satisfied (not yet due): jobName={}", blockedJob.getJobName());
                    blockedJobTracker.markResolved(job);
                }
            } else {
                // Still blocked - update with new retry time
                log.info("Blocked job still blocked: jobName={} reason={} retryCount={}",
                    blockedJob.getJobName(),
                    guardResult.blockReason(),
                    blockedJob.getRetryCount() + 1);
                blockedJobTracker.recordBlocked(job, guardResult, executionId);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Pass 2: Claim and dispatch due jobs
    // -------------------------------------------------------------------------

    private void claimAndDispatchDueJobs(Instant now) {
        List<SchedulerClaim> claims = schedulerClaimService.claimDueJobs(now);

        if (claims.isEmpty()) {
            log.debug("No due jobs at {}", now);
            return;
        }

        log.info("Claimed {} due job(s)", claims.size());

        for (SchedulerClaim claim : claims) {
            JobDefinition job = jobDefinitionRepository.findById(claim.jobDefinitionId()).orElse(null);
            if (job == null) {
                log.warn("Claimed job definition disappeared before preparation: {}", claim.jobDefinitionId());
                continue;
            }

            try {
                processClaimedJob(job, claim, now);
            } catch (Exception e) {
                log.error("Failed to process claimed job [{}]: {}", job.getId(), e.getMessage(), e);
            }
        }
    }

    private void processClaimedJob(JobDefinition job, SchedulerClaim claim, Instant now) {
        // Skip if already blocked (waiting for retry via blocked job tracker)
        if (blockedJobTracker.isBlocked(job)) {
            log.info("Skipping due job - already in blocked state: jobType={} source={}",
                job.getJobType(), job.getSource());
            schedulerClaimService.releaseClaim(
                    claim.jobDefinitionId(),
                    claim.claimToken(),
                    claim.claimedBy());
            return;
        }

        JobExecutionContext context = dependencyContextFactory.create(job);

        log.info("Checking dependencies: jobType={} source={} executionId={}",
            job.getJobType(), job.getSource(), context.executionId());

        GuardResult guardResult = jobDependencyGuard.checkDependencies(context);

        if (guardResult.isBlocked()) {
            log.info("Job BLOCKED by dependency guard: jobType={} source={} reason={}",
                job.getJobType(), job.getSource(), guardResult.blockReason());
            blockedJobTracker.recordBlocked(job, guardResult, context.executionId());
            schedulerClaimService.releaseClaim(
                    claim.jobDefinitionId(),
                    claim.claimToken(),
                    claim.claimedBy());
            return;
        }

        if (guardResult.hasWarnings()) {
            log.warn("Job proceeding with {} DOCUMENTATION_ONLY dependency warning(s): jobType={} source={}",
                guardResult.checks().size(), job.getJobType(), job.getSource());
        }

        log.info(
            "Dispatching due job [{}] type [{}] source [{}] nextRun [{}] active [{}] approvedInputVersions={}",
            job.getId(), job.getJobType(), job.getSource(), job.getNextRun(), job.getIsActive(),
            guardResult.approvedInputVersions());

        jobProducerRegistry.getProducer(job.getJobType()).prepareDispatch(
                job,
                claim,
                now,
                guardResult.approvedInputVersions());
    }

}
