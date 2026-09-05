package com.omni.platform.modules.notifications.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.omni.platform.modules.notifications.dtos.NotificationChannel;
import com.omni.platform.modules.notifications.dtos.NotificationRequest;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationKind;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.SignalChangedContent;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationSeverity;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationType;

class NotificationDeduplicatorTest {

    @Test
    void normalizesDynamicTitlesAndAggregatesSuppressedCountAfterCooldown() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-13T12:00:00Z"));
        NotificationDeduplicator deduplicator = new NotificationDeduplicator(Duration.ofMinutes(5), 100, clock);

        assertTrue(deduplicator.admit(request(NotificationType.OPERATIONAL, NotificationSeverity.ERROR,
                " Job 123 failed at 2026-08-13T12:00:00Z for 550e8400-e29b-41d4-a716-446655440000 ")).retained());
        assertFalse(deduplicator.admit(request(NotificationType.OPERATIONAL, NotificationSeverity.ERROR,
                "job 999   failed at 2026-08-14T13:30:00Z for 123e4567-e89b-42d3-a456-426614174000")).retained());
        assertFalse(deduplicator.admit(request(NotificationType.OPERATIONAL, NotificationSeverity.ERROR,
                "JOB 456 failed at 2026-08-15T14:45:00Z for 123e4567-e89b-42d3-a456-426614174001")).retained());

        clock.advance(Duration.ofMinutes(5));
        NotificationDeduplicator.Admission retained = deduplicator.admit(request(
                NotificationType.OPERATIONAL,
                NotificationSeverity.ERROR,
                "job 777 failed at 2026-08-16T15:00:00Z for 123e4567-e89b-42d3-a456-426614174002"));

        assertTrue(retained.retained());
        assertEquals(2, retained.suppressedCount());
        assertFalse(deduplicator.admit(request(
                NotificationType.OPERATIONAL,
                NotificationSeverity.ERROR,
                "job 888 failed at 2026-08-17T16:00:00Z for 123e4567-e89b-42d3-a456-426614174003")).retained());
    }

    @Test
    void keepsTypeSeverityAndNormalizedTitleAsDistinctKeys() {
        NotificationDeduplicator deduplicator = new NotificationDeduplicator(
                Duration.ofMinutes(5), 100, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

        assertTrue(deduplicator.admit(request(NotificationType.OPERATIONAL, NotificationSeverity.ERROR, "failure")).retained());
        assertTrue(deduplicator.admit(request(NotificationType.SIGNAL, NotificationSeverity.ERROR, "failure")).retained());
        assertTrue(deduplicator.admit(request(NotificationType.OPERATIONAL, NotificationSeverity.WARNING, "failure")).retained());
        assertTrue(deduplicator.admit(request(NotificationType.OPERATIONAL, NotificationSeverity.ERROR, "different")).retained());
    }

    @Test
    void admitsOnlyOneConcurrentFirstNotificationAndCountsAllRepeats() throws Exception {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        NotificationDeduplicator deduplicator = new NotificationDeduplicator(Duration.ofMinutes(5), 100, clock);
        int callers = 40;
        AtomicInteger retained = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        try {
            for (int index = 0; index < callers; index++) {
                executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    if (deduplicator.admit(request(NotificationType.SIGNAL, NotificationSeverity.INFO, "signal 42")).retained()) {
                        retained.incrementAndGet();
                    }
                    return null;
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }

        assertEquals(1, retained.get());
        clock.advance(Duration.ofMinutes(5));
        NotificationDeduplicator.Admission rollover = deduplicator.admit(
                request(NotificationType.SIGNAL, NotificationSeverity.INFO, "signal 42"));
        assertTrue(rollover.retained());
        assertEquals(callers - 1, rollover.suppressedCount());
    }

    @Test
    void explicitSignalIdentityIsUnaffectedByStructuredRenderingContent() {
        NotificationDeduplicator deduplicator = new NotificationDeduplicator(
                Duration.ofMinutes(5), 100, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        NotificationRequest first = new NotificationRequest(
                NotificationChannel.SIGNALS, NotificationType.SIGNAL, NotificationKind.SIGNAL_CHANGED,
                NotificationSeverity.INFO, "old title", "old message", Map.of(), "stable-signal-id",
                new SignalChangedContent("SET:PTT", "HOLD", "BUY", 34.75, "2026-08-29", 0.91,
                        java.util.List.of("RSI_OVERSOLD"), "momentum", "1d", Instant.EPOCH));
        NotificationRequest reformatted = new NotificationRequest(
                NotificationChannel.SIGNALS, NotificationType.SIGNAL, NotificationKind.SIGNAL_CHANGED,
                NotificationSeverity.INFO, "new title", "new message", Map.of(), "stable-signal-id",
                new SignalChangedContent("SET:PTT", "HOLD", "BUY", 34.7500, "2026-08-29", 0.910,
                        java.util.List.of("RSI_OVERSOLD"), "momentum", "1D", Instant.EPOCH));

        assertTrue(deduplicator.admit(first).retained());
        assertFalse(deduplicator.admit(reformatted).retained());
    }

    @Test
    void boundsCacheWithoutClearingCurrentEntry() {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        NotificationDeduplicator deduplicator = new NotificationDeduplicator(Duration.ofMinutes(5), 2, clock);

        deduplicator.admit(request(NotificationType.OPERATIONAL, NotificationSeverity.INFO, "one"));
        deduplicator.admit(request(NotificationType.OPERATIONAL, NotificationSeverity.INFO, "two"));
        deduplicator.admit(request(NotificationType.OPERATIONAL, NotificationSeverity.INFO, "three"));

        assertEquals(2, deduplicator.size());
        assertFalse(deduplicator.admit(request(NotificationType.OPERATIONAL, NotificationSeverity.INFO, "three")).retained());
    }

    private NotificationRequest request(NotificationType type, NotificationSeverity severity, String title) {
        NotificationChannel channel = type == NotificationType.OPERATIONAL
                ? NotificationChannel.OPERATIONS
                : NotificationChannel.SIGNALS;
        NotificationKind kind = type == NotificationType.OPERATIONAL
                ? NotificationKind.OPERATIONAL_GENERIC
                : NotificationKind.MANUAL_GENERIC;
        return new NotificationRequest(channel, type, kind, severity, title, "message", Map.of(), null);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
