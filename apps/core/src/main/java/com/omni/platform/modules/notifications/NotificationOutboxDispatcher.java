package com.omni.platform.modules.notifications;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.omni.platform.modules.notifications.configs.NotificationOutboxProperties;
import com.omni.platform.modules.notifications.configs.TelegramNotificationProperties;
import com.omni.platform.modules.notifications.repositories.NotificationOutboxClaim;
import com.omni.platform.modules.notifications.services.NotificationOutboxStore;
import com.omni.platform.modules.notifications.services.TelegramTransport;
import com.omni.platform.modules.notifications.services.TelegramTransport.DeliveryResult;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class NotificationOutboxDispatcher {

    private final NotificationOutboxStore outboxStore;
    private final TelegramTransport telegramTransport;
    private final NotificationOutboxProperties outboxProperties;
    private final TelegramNotificationProperties telegramProperties;
    private final NotificationOutboxMetrics metrics;
    private final String instanceId;

    public NotificationOutboxDispatcher(
            NotificationOutboxStore outboxStore,
            TelegramTransport telegramTransport,
            NotificationOutboxProperties outboxProperties,
            TelegramNotificationProperties telegramProperties,
            NotificationOutboxMetrics metrics,
            @Value("${app.scheduler.instance-id:}") String instanceId) {
        this.outboxStore = outboxStore;
        this.telegramTransport = telegramTransport;
        this.outboxProperties = outboxProperties;
        this.telegramProperties = telegramProperties;
        this.metrics = metrics;
        this.instanceId = instanceId == null || instanceId.isBlank()
                ? "platform-" + Integer.toHexString(System.identityHashCode(this))
                : instanceId;
    }

    @Scheduled(fixedDelayString = "${app.notifications.outbox.fixed-delay:5000}")
    public void dispatch() {
        dispatchBatch(Instant.now());
    }

    void dispatchBatch(Instant now) {
        var claim = outboxProperties.resolvedClaim();
        List<NotificationOutboxClaim> claims = outboxStore.claimPending(
                now, instanceId, claim.resolvedLeaseDuration(), claim.resolvedBatchSize());
        for (NotificationOutboxClaim message : claims) {
            dispatchOne(message, Instant.now());
        }
    }

    private void dispatchOne(NotificationOutboxClaim claim, Instant now) {
        var sample = metrics.start();
        if (!outboxStore.acquireProviderPermit(claim.provider(), now, telegramProperties.resolvedRateLimit())) {
            boolean updated = outboxStore.scheduleRetry(
                    claim, now.plus(telegramProperties.resolvedRateLimit()), "provider_rate_limited");
            if (updated) {
                metrics.rateLimited(sample);
                log.info("Notification delivery rate limited messageId={} provider={} channel={} kind={} attempt={}",
                        claim.messageId(), claim.provider(), claim.channel(), claim.kind(), claim.attempts());
            } else {
                logStaleClaim(claim, "rate_limit_retry");
            }
            return;
        }
        DeliveryResult result;
        try {
            result = telegramTransport.deliver(outboxStore.decode(claim));
        } catch (RuntimeException exception) {
            if (outboxStore.markDead(claim, now, "payload_decode_failure")) {
                metrics.dead(sample);
                log.warn("Notification marked dead messageId={} provider={} channel={} kind={} attempt={} category={}",
                        claim.messageId(), claim.provider(), claim.channel(), claim.kind(), claim.attempts(),
                        "payload_decode_failure");
            } else {
                logStaleClaim(claim, "payload_decode_failure");
            }
            return;
        }
        boolean updated = switch (result.outcome()) {
            case DELIVERED -> {
                boolean sent = outboxStore.markDelivered(claim, Instant.now());
                if (sent) {
                    metrics.delivered(sample);
                }
                yield sent;
            }
            case PERMANENT_FAILURE -> {
                boolean dead = outboxStore.markDead(claim, now, result.errorCategory());
                if (dead) {
                    metrics.dead(sample);
                }
                yield dead;
            }
            case RETRYABLE -> retryOrDead(claim, now, result, sample);
        };
        if (!updated) {
            logStaleClaim(claim, result.outcome().name().toLowerCase());
            return;
        }
        log.info("Notification delivery resolved messageId={} provider={} channel={} kind={} attempt={} outcome={} category={}",
                claim.messageId(), claim.provider(), claim.channel(), claim.kind(), claim.attempts(), result.outcome(),
                result.errorCategory());
    }

    private boolean retryOrDead(
            NotificationOutboxClaim claim,
            Instant now,
            DeliveryResult result,
            io.micrometer.core.instrument.Timer.Sample sample) {
        if (claim.attempts() >= outboxProperties.resolvedMaxAttempts()) {
            boolean updated = outboxStore.markDead(claim, now, "max_attempts_" + result.errorCategory());
            if (updated) {
                metrics.dead(sample);
            }
            return updated;
        }
        Duration delay = result.retryAfter().orElseGet(() -> exponentialBackoff(claim.attempts()));
        boolean updated = outboxStore.scheduleRetry(claim, now.plus(delay), result.errorCategory());
        if (updated) {
            metrics.retried(sample);
        }
        return updated;
    }

    private Duration exponentialBackoff(int attempts) {
        var retry = outboxProperties.resolvedRetry();
        long initialMillis = retry.resolvedInitialDelay().toMillis();
        long maxMillis = retry.resolvedMaxDelay().toMillis();
        int exponent = Math.min(Math.max(0, attempts - 1), 20);
        long largestSafeInitial = maxMillis >> exponent;
        long bounded = initialMillis > largestSafeInitial ? maxMillis : Math.min(maxMillis, initialMillis << exponent);
        long jitterBound = bounded / 4;
        long jitter = jitterBound <= 0 ? 0 : ThreadLocalRandom.current().nextLong(jitterBound + 1);
        return Duration.ofMillis(bounded >= maxMillis - jitter ? maxMillis : bounded + jitter);
    }

    private void logStaleClaim(NotificationOutboxClaim claim, String outcome) {
        log.warn("Notification claim lost before state transition messageId={} provider={} channel={} kind={} attempt={} outcome={}",
                claim.messageId(), claim.provider(), claim.channel(), claim.kind(), claim.attempts(), outcome);
    }
}
