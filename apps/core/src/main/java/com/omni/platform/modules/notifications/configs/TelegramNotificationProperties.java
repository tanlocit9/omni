package com.omni.platform.modules.notifications.configs;

import java.time.Duration;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

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
        Boolean audibleOperationalErrors,
        Duration rateLimit,
        SignalFilterConfig signalFilter) {

    @Deprecated(forRemoval = false)
    public TelegramNotificationProperties(
            boolean enabled,
            String botToken,
            String operationsChatId,
            String signalsChatId,
            String parseMode,
            String apiBaseUrl,
            Duration deduplicationCooldown,
            int deduplicationMaxCacheSize,
            String displayTimeZone,
            Boolean audibleOperationalErrors,
            SignalFilterConfig signalFilter) {
        this(enabled, botToken, operationsChatId, signalsChatId, parseMode, apiBaseUrl,
                deduplicationCooldown, deduplicationMaxCacheSize, displayTimeZone,
                audibleOperationalErrors, null, signalFilter);
    }

    @ConstructorBinding
    public TelegramNotificationProperties {
    }

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

    public Duration resolvedRateLimit() {
        return rateLimit == null || rateLimit.isZero() || rateLimit.isNegative()
                ? Duration.ofMillis(50)
                : rateLimit;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record SignalFilterConfig(
            List<String> allowedStrategies,
            List<String> allowedSymbols,
            List<String> symbolPatterns,
            List<String> allowedDirections,
            List<String> allowedTimeframes,
            Double minScore,
            Boolean enableNewSignalDate) {

        public SignalFilterConfig {
            // Apply defaults
            if (allowedStrategies == null || allowedStrategies.isEmpty()) {
                allowedStrategies = List.of("CONFIRMED_TREND_EQUALS");
            }
            if (allowedSymbols == null) {
                allowedSymbols = List.of();
            }
            if (symbolPatterns == null) {
                symbolPatterns = List.of();
            }
            if (allowedDirections == null) {
                allowedDirections = List.of();
            }
            if (allowedTimeframes == null) {
                allowedTimeframes = List.of();
            }
            if (enableNewSignalDate == null) {
                enableNewSignalDate = true;
            }

            // Normalize to uppercase for case-insensitive matching
            allowedStrategies = allowedStrategies.stream()
                    .map(String::toUpperCase)
                    .collect(Collectors.toList());
            allowedDirections = allowedDirections.stream()
                    .map(String::toUpperCase)
                    .collect(Collectors.toList());
            allowedTimeframes = allowedTimeframes.stream()
                    .map(String::toLowerCase)
                    .collect(Collectors.toList());
        }

        public boolean matchesStrategy(String strategy) {
            if (strategy == null) {
                return false;
            }
            return allowedStrategies.contains(strategy.toUpperCase());
        }

        public boolean matchesSymbol(String symbolKey) {
            if (symbolKey == null) {
                return false;
            }
            // No filter = allow all
            if (allowedSymbols.isEmpty() && symbolPatterns.isEmpty()) {
                return true;
            }
            // Check exact match
            if (allowedSymbols.contains(symbolKey)) {
                return true;
            }
            // Check pattern match
            for (String pattern : symbolPatterns) {
                if (matchesPattern(symbolKey, pattern)) {
                    return true;
                }
            }
            return false;
        }

        public boolean matchesDirection(String direction) {
            if (direction == null) {
                return false;
            }
            // No filter = allow all
            if (allowedDirections.isEmpty()) {
                return true;
            }
            return allowedDirections.contains(direction.toUpperCase());
        }

        public boolean matchesTimeframe(String timeframe) {
            if (timeframe == null) {
                return false;
            }
            // No filter = allow all
            if (allowedTimeframes.isEmpty()) {
                return true;
            }
            return allowedTimeframes.contains(timeframe.toLowerCase());
        }

        public boolean matchesScore(Object score) {
            if (minScore == null) {
                return true; // No threshold = allow all
            }
            if (score == null) {
                return false; // Null score fails threshold check
            }
            try {
                double scoreValue = parseScore(score);
                return scoreValue >= minScore;
            } catch (NumberFormatException e) {
                return false; // Unparseable score fails threshold check
            }
        }

        private double parseScore(Object score) {
            if (score instanceof Number n) {
                return n.doubleValue();
            }
            if (score instanceof String s) {
                return Double.parseDouble(s);
            }
            throw new NumberFormatException("Cannot parse score: " + score);
        }

        private boolean matchesPattern(String value, String pattern) {
            // Simple glob: HOSE-* matches HOSE-FPT, HOSE-VNM, etc.
            // Convert glob pattern to regex
            String regex = pattern.replace("*", ".*")
                    .replace("?", ".");
            return value.matches(regex);
        }
    }
}
