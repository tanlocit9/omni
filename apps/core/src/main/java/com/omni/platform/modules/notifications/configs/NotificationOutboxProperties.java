package com.omni.platform.modules.notifications.configs;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.notifications.outbox")
public record NotificationOutboxProperties(
        Duration fixedDelay,
        Claim claim,
        int maxAttempts,
        Retry retry) {

    public Duration resolvedFixedDelay() {
        return positive(fixedDelay, Duration.ofSeconds(5));
    }

    public Claim resolvedClaim() {
        return claim == null ? new Claim(Duration.ofMinutes(2), 10) : claim;
    }

    public int resolvedMaxAttempts() {
        return maxAttempts <= 0 ? 8 : maxAttempts;
    }

    public Retry resolvedRetry() {
        return retry == null ? new Retry(Duration.ofSeconds(5), Duration.ofMinutes(15)) : retry;
    }

    private static Duration positive(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    public record Claim(Duration leaseDuration, int batchSize) {
        public Duration resolvedLeaseDuration() {
            return positive(leaseDuration, Duration.ofMinutes(2));
        }

        public int resolvedBatchSize() {
            return batchSize <= 0 ? 10 : batchSize;
        }
    }

    public record Retry(Duration initialDelay, Duration maxDelay) {
        public Duration resolvedInitialDelay() {
            return positive(initialDelay, Duration.ofSeconds(5));
        }

        public Duration resolvedMaxDelay() {
            return positive(maxDelay, Duration.ofMinutes(15));
        }
    }
}
