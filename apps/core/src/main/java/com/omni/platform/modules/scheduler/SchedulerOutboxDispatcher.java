package com.omni.platform.modules.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.omni.platform.modules.scheduler.config.SchedulerProperties;
import com.omni.platform.modules.scheduler.repositories.SchedulerOutboxClaim;
import com.omni.platform.shared.infrastructure.kafka.KafkaPublisher;
import com.omni.platform.shared.outbox.ClaimableOutboxStore;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class SchedulerOutboxDispatcher {

    private static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(30);

    private final ClaimableOutboxStore<SchedulerOutboxClaim> outboxStore;
    private final KafkaPublisher kafkaPublisher;
    private final SchedulerProperties schedulerProperties;

    @Scheduled(fixedDelayString = "${app.scheduler.outbox.fixed-delay:5000}")
    public void dispatch() {
        dispatchBatch(Instant.now());
    }

    void dispatchBatch(Instant now) {
        List<SchedulerOutboxClaim> claims = outboxStore.claimPending(
                now,
                schedulerProperties.instanceId(),
                schedulerProperties.claim().leaseDuration(),
                schedulerProperties.claim().batchSize());
        for (SchedulerOutboxClaim claim : claims) {
            try {
                kafkaPublisher.publishSerializedAndWait(
                        claim.topic(), claim.key(), claim.payload(), PUBLISH_TIMEOUT);
                if (!outboxStore.markDelivered(claim, Instant.now())) {
                    log.warn("Outbox claim was superseded before publish acknowledgement messageId={}", claim.messageId());
                }
            } catch (Exception exception) {
                outboxStore.scheduleRetry(
                        claim, Instant.now().plus(RETRY_DELAY), sanitizedError(exception));
                log.error("Outbox publish failed messageId={} executionId={} attempt={}",
                        claim.messageId(), claim.executionId(), claim.attempts(), exception);
            }
        }
    }

    private String sanitizedError(Exception exception) {
        String error = exception.getMessage();
        String normalized = error == null ? "unknown" : error.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.substring(0, Math.min(normalized.length(), 4000));
    }
}
