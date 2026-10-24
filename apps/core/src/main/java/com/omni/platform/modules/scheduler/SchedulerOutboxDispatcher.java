package com.omni.platform.modules.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.omni.platform.modules.scheduler.config.SchedulerProperties;
import com.omni.platform.modules.scheduler.planning.JobRunDispatchCoordinator;
import com.omni.platform.modules.scheduler.dependencies.DependencyDecision;
import com.omni.platform.modules.scheduler.dependencies.DependencyRegistry;
import com.omni.platform.modules.scheduler.dependencies.DependencyRequest;
import com.omni.platform.modules.scheduler.repositories.SchedulerOutboxClaim;
import com.omni.platform.modules.scheduler.services.JobOperationsCatalogService;
import com.omni.platform.modules.scheduler.services.SchedulerOutboxService;
import com.omni.platform.shared.infrastructure.kafka.KafkaPublisher;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class SchedulerOutboxDispatcher {

    private static final String TRACE_TAG = "[SCHEDULER_OUTBOX_TRACE]";
    private static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(30);

    private static final int CANDIDATE_MULTIPLIER = 4;

    private final SchedulerOutboxService outboxService;
    private final DependencyRegistry dependencyRegistry;
    private final KafkaPublisher kafkaPublisher;
    private final SchedulerProperties schedulerProperties;
    private final JobRunDispatchCoordinator coordinator;

    public SchedulerOutboxDispatcher(
            SchedulerOutboxService outboxService,
            DependencyRegistry dependencyRegistry,
            KafkaPublisher kafkaPublisher,
            SchedulerProperties schedulerProperties,
            JobRunDispatchCoordinator coordinator) {
        if (schedulerProperties.claim().leaseDuration().compareTo(PUBLISH_TIMEOUT) <= 0)
            throw new IllegalArgumentException(
                    "Outbox claim lease must exceed the 15-second publish timeout");
        this.outboxService = outboxService;
        this.dependencyRegistry = dependencyRegistry;
        this.kafkaPublisher = kafkaPublisher;
        this.schedulerProperties = schedulerProperties;
        this.coordinator = coordinator;
    }

    @Scheduled(fixedDelayString = "${app.scheduler.outbox.fixed-delay:5000}")
    public void dispatch() {
        Instant now = Instant.now();
        log.info(
                "{} Dispatch tick started at={} instanceId={}",
                TRACE_TAG,
                now,
                schedulerProperties.instanceId());
        dispatchBatch(now);
    }

    void dispatchBatch(Instant now) {
        long batchStartedAt = System.nanoTime();
        int batchSize = schedulerProperties.claim().batchSize();
        int candidateLimit = batchSize * CANDIDATE_MULTIPLIER;
        log.info(
                "{} Loading candidates at={} batchSize={} candidateLimit={}",
                TRACE_TAG,
                now,
                batchSize,
                candidateLimit);
        var batch = coordinator.load(now, batchSize);
        var candidates = batch.candidates();
        log.info("{} Selection mode={} candidates={}", TRACE_TAG, batch.mode(), candidates.size());
        log.info(
                "{} Candidates loaded count={} elapsedMs={}",
                TRACE_TAG,
                candidates.size(),
                elapsedMillis(batchStartedAt));

        int waitingCount = 0;
        int blockedCount = 0;
        int readyCount = 0;
        List<SchedulerOutboxClaim> claims = new java.util.ArrayList<>(batchSize);
        for (var selected : candidates) {
            var candidate = selected.candidate();
            if (claims.size() >= batchSize) {
                break;
            }
            long evaluationStartedAt = System.nanoTime();
            log.info(
                    "{} Evaluating candidate messageId={} executionId={} parentExecutionId={} workType={} workKey={} availableAt={}",
                    TRACE_TAG,
                    candidate.messageId(),
                    candidate.executionId(),
                    candidate.parentExecutionId(),
                    candidate.workType(),
                    candidate.workKey(),
                    candidate.availableAt());
            DependencyDecision decision;
            try {
                decision =
                        dependencyRegistry.evaluate(
                                new DependencyRequest(
                                        candidate.messageId(),
                                        candidate.executionId(),
                                        candidate.parentExecutionId(),
                                        candidate.jobDefinition(),
                                        candidate.workType(),
                                        candidate.workKey(),
                                        candidate.runKey(),
                                        candidate.executionMetadata(),
                                        now));
                java.util.Objects.requireNonNull(decision, "Dependency decision");
                java.util.Objects.requireNonNull(decision.state(), "Dependency state");
                if (decision.state() == DependencyDecision.State.WAITING
                        && decision.retryAt() == null)
                    throw new IllegalStateException("Dependency retry time missing");
            } catch (RuntimeException error) {
                outboxService.markWaiting(
                        candidate.messageId(),
                        now.plus(RETRY_DELAY),
                        "{\"code\":\"DEPENDENCY_EVALUATION_FAILED\"}");
                coordinator.skipped(selected);
                log.warn(
                        "{} Dependency evaluation failed messageId={} type={}",
                        TRACE_TAG,
                        candidate.messageId(),
                        error.getClass().getSimpleName());
                continue;
            }
            log.info(
                    "{} Dependency evaluated messageId={} executionId={} workType={} workKey={} state={} retryAt={} elapsedMs={}",
                    TRACE_TAG,
                    candidate.messageId(),
                    candidate.executionId(),
                    candidate.workType(),
                    candidate.workKey(),
                    decision.state(),
                    decision.retryAt(),
                    elapsedMillis(evaluationStartedAt));
            if (decision.state() == DependencyDecision.State.WAITING) {
                boolean updated =
                        outboxService.markWaiting(
                                candidate.messageId(),
                                decision.retryAt(),
                                structuredReason(decision));
                waitingCount++;
                coordinator.skipped(selected);
                log.info(
                        "{} Candidate waiting messageId={} retryAt={} persisted={}",
                        TRACE_TAG,
                        candidate.messageId(),
                        decision.retryAt(),
                        updated);
                continue;
            }
            if (decision.state() == DependencyDecision.State.BLOCKED) {
                boolean updated =
                        outboxService.markBlocked(
                                candidate.messageId(), now, structuredReason(decision));
                blockedCount++;
                coordinator.skipped(selected);
                log.info(
                        "{} Candidate blocked messageId={} persisted={}",
                        TRACE_TAG,
                        candidate.messageId(),
                        updated);
                continue;
            }
            readyCount++;
            SchedulerOutboxClaim claim =
                    coordinator.claim(
                            selected,
                            now,
                            schedulerProperties.instanceId(),
                            schedulerProperties.claim().leaseDuration(),
                            decision.approvedInputVersions());
            log.info(
                    "{} Candidate ready messageId={} claimed={}",
                    TRACE_TAG,
                    candidate.messageId(),
                    claim != null);
            if (claim != null) {
                claims.add(claim);
            }
        }
        log.info(
                "{} Candidate phase completed candidates={} ready={} waiting={} blocked={} claims={} elapsedMs={}",
                TRACE_TAG,
                candidates.size(),
                readyCount,
                waitingCount,
                blockedCount,
                claims.size(),
                elapsedMillis(batchStartedAt));
        int publishedCount = 0;
        int retryCount = 0;
        for (SchedulerOutboxClaim claim : claims) {
            long publishStartedAt = System.nanoTime();
            try {
                if (!outboxService.renewForPublish(
                        claim,
                        now.plusNanos(System.nanoTime() - batchStartedAt),
                        schedulerProperties.claim().leaseDuration())) {
                    log.warn(
                            "{} Skipping superseded or expired publish claim messageId={}",
                            TRACE_TAG,
                            claim.messageId());
                    continue;
                }
                log.info(
                        "{} Publishing claim messageId={} executionId={} attempt={} topic={} key={}",
                        TRACE_TAG,
                        claim.messageId(),
                        claim.executionId(),
                        claim.attempts(),
                        claim.topic(),
                        claim.key());
                kafkaPublisher.publishSerializedAndWait(
                        claim.topic(), claim.key(), claim.payload(), PUBLISH_TIMEOUT);
                boolean delivered =
                        outboxService.markDelivered(
                                claim, now.plusNanos(System.nanoTime() - batchStartedAt));
                log.info(
                        "{} Publish acknowledged messageId={} executionId={} persisted={} elapsedMs={}",
                        TRACE_TAG,
                        claim.messageId(),
                        claim.executionId(),
                        delivered,
                        elapsedMillis(publishStartedAt));
                if (!delivered) {
                    log.warn(
                            "Outbox claim was superseded before publish acknowledgement messageId={}",
                            claim.messageId());
                } else {
                    publishedCount++;
                }
            } catch (Exception exception) {
                outboxService.scheduleRetry(
                        claim,
                        now.plusNanos(System.nanoTime() - batchStartedAt).plus(RETRY_DELAY),
                        sanitizedError(exception));
                retryCount++;
                log.error(
                        "{} Outbox publish failed messageId={} executionId={} attempt={} elapsedMs={}",
                        TRACE_TAG,
                        claim.messageId(),
                        claim.executionId(),
                        claim.attempts(),
                        elapsedMillis(publishStartedAt),
                        exception);
            }
        }
        log.info(
                "{} Dispatch batch completed candidates={} claims={} published={} retries={} elapsedMs={}",
                TRACE_TAG,
                candidates.size(),
                claims.size(),
                publishedCount,
                retryCount,
                elapsedMillis(batchStartedAt));
    }

    private long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    private String structuredReason(DependencyDecision decision) {
        return "{\"code\":\""
                + sanitized(decision.reasonCode(), 80)
                + "\",\"detail\":\""
                + sanitized(decision.reason(), 350).replace("\"", "'")
                + "\"}";
    }

    private String sanitized(String value, int limit) {
        String normalized = JobOperationsCatalogService.sanitize(value == null ? "unknown" : value);
        return normalized.substring(0, Math.min(normalized.length(), limit));
    }

    private String sanitizedError(Exception exception) {
        String error = exception.getMessage();
        String normalized =
                error == null ? "unknown" : error.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.substring(0, Math.min(normalized.length(), 4000));
    }
}
