package com.omni.platform.modules.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.omni.platform.modules.scheduler.config.SchedulerProperties;
import com.omni.platform.modules.scheduler.dependencies.DependencyDecision;
import com.omni.platform.modules.scheduler.dependencies.DependencyRegistry;
import com.omni.platform.modules.scheduler.dependencies.DependencyRequest;
import com.omni.platform.modules.scheduler.repositories.SchedulerOutboxClaim;
import com.omni.platform.modules.scheduler.services.JobOperationsCatalogService;
import com.omni.platform.modules.scheduler.services.SchedulerOutboxService;
import com.omni.platform.shared.infrastructure.kafka.KafkaPublisher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class SchedulerOutboxDispatcher {

    private static final String TRACE_TAG = "[SCHEDULER_OUTBOX_TRACE]";
    private static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(30);

    private static final int CANDIDATE_MULTIPLIER = 4;

    private final SchedulerOutboxService outboxService;
    private final DependencyRegistry dependencyRegistry;
    private final KafkaPublisher kafkaPublisher;
    private final SchedulerProperties schedulerProperties;

    @Scheduled(fixedDelayString = "${app.scheduler.outbox.fixed-delay:5000}")
    public void dispatch() {
        Instant now = Instant.now();
        log.info("{} Dispatch tick started at={} instanceId={}",
                TRACE_TAG, now, schedulerProperties.instanceId());
        dispatchBatch(now);
    }

    void dispatchBatch(Instant now) {
        long batchStartedAt = System.nanoTime();
        int batchSize = schedulerProperties.claim().batchSize();
        int candidateLimit = batchSize * CANDIDATE_MULTIPLIER;
        log.info("{} Loading candidates at={} batchSize={} candidateLimit={}",
                TRACE_TAG, now, batchSize, candidateLimit);
        var candidates = outboxService.findCandidates(now, candidateLimit);
        log.info("{} Candidates loaded count={} elapsedMs={}",
                TRACE_TAG, candidates.size(), elapsedMillis(batchStartedAt));

        int waitingCount = 0;
        int blockedCount = 0;
        int readyCount = 0;
        List<SchedulerOutboxClaim> claims = new java.util.ArrayList<>(batchSize);
        for (var candidate : candidates) {
            if (claims.size() >= batchSize) {
                break;
            }
            long evaluationStartedAt = System.nanoTime();
            log.info(
                    "{} Evaluating candidate messageId={} executionId={} parentExecutionId={} workType={} workKey={} availableAt={}",
                    TRACE_TAG, candidate.messageId(), candidate.executionId(), candidate.parentExecutionId(),
                    candidate.workType(), candidate.workKey(), candidate.availableAt());
            DependencyDecision decision = dependencyRegistry.evaluate(new DependencyRequest(
                    candidate.messageId(), candidate.executionId(), candidate.parentExecutionId(),
                    candidate.jobDefinition(), candidate.workType(), candidate.workKey(), candidate.runKey(),
                    candidate.executionMetadata(), now));
            log.info(
                    "{} Dependency evaluated messageId={} executionId={} workType={} workKey={} state={} retryAt={} elapsedMs={}",
                    TRACE_TAG, candidate.messageId(), candidate.executionId(), candidate.workType(),
                    candidate.workKey(), decision.state(), decision.retryAt(), elapsedMillis(evaluationStartedAt));
            if (decision.state() == DependencyDecision.State.WAITING) {
                boolean updated = outboxService.markWaiting(
                        candidate.messageId(), decision.retryAt(), structuredReason(decision));
                waitingCount++;
                log.info("{} Candidate waiting messageId={} retryAt={} persisted={}",
                        TRACE_TAG, candidate.messageId(), decision.retryAt(), updated);
                continue;
            }
            if (decision.state() == DependencyDecision.State.BLOCKED) {
                boolean updated = outboxService.markBlocked(
                        candidate.messageId(), now, structuredReason(decision));
                blockedCount++;
                log.info("{} Candidate blocked messageId={} persisted={}",
                        TRACE_TAG, candidate.messageId(), updated);
                continue;
            }
            readyCount++;
            SchedulerOutboxClaim claim = outboxService.claimEligible(
                    candidate.messageId(), now, schedulerProperties.instanceId(),
                    schedulerProperties.claim().leaseDuration(), decision.approvedInputVersions());
            log.info("{} Candidate ready messageId={} claimed={}",
                    TRACE_TAG, candidate.messageId(), claim != null);
            if (claim != null) {
                claims.add(claim);
            }
        }
        log.info("{} Candidate phase completed candidates={} ready={} waiting={} blocked={} claims={} elapsedMs={}",
                TRACE_TAG, candidates.size(), readyCount, waitingCount, blockedCount,
                claims.size(), elapsedMillis(batchStartedAt));
        int publishedCount = 0;
        int retryCount = 0;
        for (SchedulerOutboxClaim claim : claims) {
            long publishStartedAt = System.nanoTime();
            try {
                log.info("{} Publishing claim messageId={} executionId={} attempt={} topic={} key={}",
                        TRACE_TAG, claim.messageId(), claim.executionId(), claim.attempts(), claim.topic(), claim.key());
                kafkaPublisher.publishSerializedAndWait(
                        claim.topic(), claim.key(), claim.payload(), PUBLISH_TIMEOUT);
                boolean delivered = outboxService.markDelivered(claim, Instant.now());
                log.info("{} Publish acknowledged messageId={} executionId={} persisted={} elapsedMs={}",
                        TRACE_TAG, claim.messageId(), claim.executionId(), delivered, elapsedMillis(publishStartedAt));
                if (!delivered) {
                    log.warn("Outbox claim was superseded before publish acknowledgement messageId={}", claim.messageId());
                } else {
                    publishedCount++;
                }
            } catch (Exception exception) {
                outboxService.scheduleRetry(
                        claim, Instant.now().plus(RETRY_DELAY), sanitizedError(exception));
                retryCount++;
                log.error("{} Outbox publish failed messageId={} executionId={} attempt={} elapsedMs={}",
                        TRACE_TAG, claim.messageId(), claim.executionId(), claim.attempts(),
                        elapsedMillis(publishStartedAt), exception);
            }
        }
        log.info("{} Dispatch batch completed candidates={} claims={} published={} retries={} elapsedMs={}",
                TRACE_TAG, candidates.size(), claims.size(), publishedCount, retryCount,
                elapsedMillis(batchStartedAt));
    }

    private long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    private String structuredReason(DependencyDecision decision) {
        return "{\"code\":\"" + sanitized(decision.reasonCode(), 80)
                + "\",\"detail\":\"" + sanitized(decision.reason(), 350).replace("\"", "'") + "\"}";
    }

    private String sanitized(String value, int limit) {
        String normalized = JobOperationsCatalogService.sanitize(value == null ? "unknown" : value);
        return normalized.substring(0, Math.min(normalized.length(), limit));
    }

    private String sanitizedError(Exception exception) {
        String error = exception.getMessage();
        String normalized = error == null ? "unknown" : error.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.substring(0, Math.min(normalized.length(), 4000));
    }
}
