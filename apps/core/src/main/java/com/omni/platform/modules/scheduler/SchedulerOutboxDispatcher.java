package com.omni.platform.modules.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.omni.platform.modules.scheduler.config.SchedulerProperties;
import com.omni.platform.modules.scheduler.repositories.SchedulerOutboxClaim;
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

    private final SchedulerOutboxService outboxService;
    private final KafkaPublisher kafkaPublisher;
    private final SchedulerProperties schedulerProperties;

    @Scheduled(fixedDelayString = "${app.scheduler.outbox.fixed-delay:5000}")
    public void dispatch() {
        dispatchBatch(Instant.now());
    }

    void dispatchBatch(Instant now) {
        List<SchedulerOutboxClaim> claims = outboxService.claimPending(
                now,
                schedulerProperties.instanceId(),
                schedulerProperties.claim().leaseDuration(),
                schedulerProperties.claim().batchSize());
        for (SchedulerOutboxClaim claim : claims) {
            try {
                kafkaPublisher.publishSerializedAndWait(
                        claim.topic(), claim.key(), claim.payload(), PUBLISH_TIMEOUT);
                if (!outboxService.markPublished(claim, Instant.now())) {
                    log.warn("Outbox claim was superseded before publish acknowledgement messageId={}", claim.messageId());
                }
            } catch (Exception exception) {
                outboxService.markFailed(claim, Instant.now().plus(RETRY_DELAY), exception);
                log.error("Outbox publish failed messageId={} executionId={} attempt={}",
                        claim.messageId(), claim.executionId(), claim.attempts(), exception);
            }
        }
    }
}
