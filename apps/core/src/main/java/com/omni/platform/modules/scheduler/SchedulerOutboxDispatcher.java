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

    private static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(30);

    private static final int CANDIDATE_MULTIPLIER = 4;

    private final SchedulerOutboxService outboxService;
    private final DependencyRegistry dependencyRegistry;
    private final KafkaPublisher kafkaPublisher;
    private final SchedulerProperties schedulerProperties;

    @Scheduled(fixedDelayString = "${app.scheduler.outbox.fixed-delay:5000}")
    public void dispatch() {
        dispatchBatch(Instant.now());
    }

    void dispatchBatch(Instant now) {
        int batchSize = schedulerProperties.claim().batchSize();
        List<SchedulerOutboxClaim> claims = new java.util.ArrayList<>(batchSize);
        for (var candidate : outboxService.findCandidates(now, batchSize * CANDIDATE_MULTIPLIER)) {
            if (claims.size() >= batchSize) {
                break;
            }
            DependencyDecision decision = dependencyRegistry.evaluate(new DependencyRequest(
                    candidate.messageId(), candidate.executionId(), candidate.parentExecutionId(),
                    candidate.jobDefinition(), candidate.workType(), candidate.workKey(), candidate.runKey(),
                    candidate.executionMetadata(), now));
            if (decision.state() == DependencyDecision.State.WAITING) {
                outboxService.markWaiting(candidate.messageId(), decision.retryAt(), structuredReason(decision));
                continue;
            }
            if (decision.state() == DependencyDecision.State.BLOCKED) {
                outboxService.markBlocked(candidate.messageId(), now, structuredReason(decision));
                continue;
            }
            SchedulerOutboxClaim claim = outboxService.claimEligible(
                    candidate.messageId(), now, schedulerProperties.instanceId(),
                    schedulerProperties.claim().leaseDuration(), decision.approvedInputVersions());
            if (claim != null) {
                claims.add(claim);
            }
        }
        for (SchedulerOutboxClaim claim : claims) {
            try {
                kafkaPublisher.publishSerializedAndWait(
                        claim.topic(), claim.key(), claim.payload(), PUBLISH_TIMEOUT);
                if (!outboxService.markDelivered(claim, Instant.now())) {
                    log.warn("Outbox claim was superseded before publish acknowledgement messageId={}", claim.messageId());
                }
            } catch (Exception exception) {
                outboxService.scheduleRetry(
                        claim, Instant.now().plus(RETRY_DELAY), sanitizedError(exception));
                log.error("Outbox publish failed messageId={} executionId={} attempt={}",
                        claim.messageId(), claim.executionId(), claim.attempts(), exception);
            }
        }
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
