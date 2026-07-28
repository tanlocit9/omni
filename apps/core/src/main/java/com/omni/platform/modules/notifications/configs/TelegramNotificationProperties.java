package com.omni.platform.modules.notifications.configs;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.notifications.telegram")
public record TelegramNotificationProperties(
        boolean enabled,
        String botToken,
        String chatId,
        String parseMode,
        String apiBaseUrl) {

    public boolean isConfigured() {
        return enabled && hasText(botToken) && hasText(chatId);
    }

    public String resolvedApiBaseUrl() {
        if (hasText(apiBaseUrl)) {
            return apiBaseUrl;
        }
        return "https://api.telegram.org";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
