package com.omni.platform.modules.notifications.services;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import com.omni.platform.modules.notifications.dtos.NotificationRequest;

final class NotificationDeduplicator {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\b");
    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile(
            "(?i)\\b\\d{4}-\\d{2}-\\d{2}(?:[t\\s]\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?(?:z|[+-]\\d{2}:?\\d{2})?)?\\b");
    private static final Pattern NUMERIC_RUN_PATTERN = Pattern.compile("\\d+");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    private final Duration cooldown;
    private final int maxCacheSize;
    private final Clock clock;
    private final ConcurrentHashMap<NotificationKey, Entry> entries = new ConcurrentHashMap<>();

    NotificationDeduplicator(Duration cooldown, int maxCacheSize, Clock clock) {
        this.cooldown = cooldown;
        this.maxCacheSize = maxCacheSize;
        this.clock = clock;
    }

    Admission admit(NotificationRequest request) {
        Instant now = clock.instant();
        NotificationKey key = new NotificationKey(request.type(), request.severity(), normalizeTitle(request.title()));
        AtomicReference<Admission> result = new AtomicReference<>();

        entries.compute(key, (ignored, current) -> {
            if (current == null) {
                result.set(Admission.retained(0));
                return new Entry(now, 0);
            }
            if (!now.isBefore(current.retainedAt().plus(cooldown))) {
                result.set(Admission.retained(current.suppressedCount()));
                return new Entry(now, 0);
            }
            result.set(Admission.suppressed(current.suppressedCount() + 1));
            return new Entry(current.retainedAt(), current.suppressedCount() + 1);
        });

        evictIfRequired(now, key);
        return result.get();
    }

    int size() {
        return entries.size();
    }

    static String normalizeTitle(String title) {
        String normalized = title == null ? "" : title.trim().toLowerCase(Locale.ROOT);
        normalized = UUID_PATTERN.matcher(normalized).replaceAll("<uuid>");
        normalized = TIMESTAMP_PATTERN.matcher(normalized).replaceAll("<timestamp>");
        normalized = NUMERIC_RUN_PATTERN.matcher(normalized).replaceAll("<number>");
        return WHITESPACE_PATTERN.matcher(normalized).replaceAll(" ");
    }

    private void evictIfRequired(Instant now, NotificationKey currentKey) {
        if (entries.size() <= maxCacheSize) {
            return;
        }

        entries.entrySet().removeIf(entry -> !entry.getKey().equals(currentKey)
                && !now.isBefore(entry.getValue().retainedAt().plus(cooldown)));
        while (entries.size() > maxCacheSize) {
            Map.Entry<NotificationKey, Entry> oldest = entries.entrySet().stream()
                    .filter(entry -> !entry.getKey().equals(currentKey))
                    .min(Comparator.comparing(entry -> entry.getValue().retainedAt()))
                    .orElse(null);
            if (oldest == null || !entries.remove(oldest.getKey(), oldest.getValue())) {
                break;
            }
        }
    }

    record Admission(boolean retained, long suppressedCount) {

        static Admission retained(long suppressedCount) {
            return new Admission(true, suppressedCount);
        }

        static Admission suppressed(long suppressedCount) {
            return new Admission(false, suppressedCount);
        }
    }

    private record NotificationKey(
            NotificationRequest.NotificationType type,
            NotificationRequest.NotificationSeverity severity,
            String normalizedTitle) {
    }

    private record Entry(Instant retainedAt, long suppressedCount) {
    }
}
