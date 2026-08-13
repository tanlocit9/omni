package com.omni.platform.modules.notifications.configs;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.notifications.telegram")
public record TelegramNotificationProperties(
        boolean enabled,
        String botToken,
        String chatId,
        String parseMode,
        String apiBaseUrl,
        Duration deduplicationCooldown,
        int deduplicationMaxCacheSize) {

    private static final Duration DEFAULT_DEDUPLICATION_COOLDOWN = Duration.ofMinutes(5);
    private static final int DEFAULT_DEDUPLICATION_MAX_CACHE_SIZE = 10_000;

    public boolean isConfigured() {
        return enabled && hasText(botToken) && hasText(chatId);
    }

    public String resolvedApiBaseUrl() {
        if (hasText(apiBaseUrl)) {
            return apiBaseUrl;
        }
        return "https://api.telegram.org";
    }

    public Duration resolvedDeduplicationCooldown() {
        if (deduplicationCooldown == null || deduplicationCooldown.isNegative()
                || deduplicationCooldown.isZero()) {
            return DEFAULT_DEDUPLICATION_COOLDOWN;
        }
        return deduplicationCooldown;
    }

    public int resolvedDeduplicationMaxCacheSize() {
        if (deduplicationMaxCacheSize <= 0) {
            return DEFAULT_DEDUPLICATION_MAX_CACHE_SIZE;
        }
        return deduplicationMaxCacheSize;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
