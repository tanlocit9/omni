package com.omni.platform.modules.notifications.configs;

import java.time.Duration;
import java.time.DateTimeException;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.omni.platform.modules.notifications.dtos.NotificationChannel;

@ConfigurationProperties(prefix = "app.notifications.telegram")
public record TelegramNotificationProperties(
        boolean enabled,
        String botToken,
        String operationsChatId,
        String signalsChatId,
        String parseMode,
        String apiBaseUrl,
        Duration deduplicationCooldown,
        int deduplicationMaxCacheSize,
        String displayTimeZone,
        Boolean audibleOperationalErrors) {

    private static final Duration DEFAULT_DEDUPLICATION_COOLDOWN = Duration.ofMinutes(5);
    private static final int DEFAULT_DEDUPLICATION_MAX_CACHE_SIZE = 10_000;

    public boolean isConfigured(NotificationChannel channel) {
        return enabled && hasText(botToken) && hasText(destination(channel));
    }

    public String destination(NotificationChannel channel) {
        return channel == NotificationChannel.SIGNALS ? signalsChatId : operationsChatId;
    }

    public String resolvedApiBaseUrl() {
        return hasText(apiBaseUrl) ? apiBaseUrl : "https://api.telegram.org";
    }

    public Duration resolvedDeduplicationCooldown() {
        if (deduplicationCooldown == null || deduplicationCooldown.isNegative() || deduplicationCooldown.isZero()) {
            return DEFAULT_DEDUPLICATION_COOLDOWN;
        }
        return deduplicationCooldown;
    }

    public int resolvedDeduplicationMaxCacheSize() {
        return deduplicationMaxCacheSize <= 0 ? DEFAULT_DEDUPLICATION_MAX_CACHE_SIZE : deduplicationMaxCacheSize;
    }

    public ZoneId resolvedDisplayTimeZone() {
        try {
            return ZoneId.of(hasText(displayTimeZone) ? displayTimeZone : "Asia/Bangkok");
        } catch (DateTimeException exc) {
            throw new IllegalArgumentException("Invalid Telegram display time zone: " + displayTimeZone, exc);
        }
    }

    public boolean resolvedAudibleOperationalErrors() {
        return audibleOperationalErrors == null || audibleOperationalErrors;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
