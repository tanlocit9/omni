package com.omni.platform.modules.notifications.dtos;

import java.util.Map;

public record NotificationRequest(
        NotificationChannel channel,
        NotificationType type,
        NotificationSeverity severity,
        String title,
        String message,
        Map<String, Object> metadata,
        String deduplicationKey) {

    public NotificationRequest(
            NotificationType type,
            NotificationSeverity severity,
            String title,
            String message,
            Map<String, Object> metadata) {
        this(type == NotificationType.OPERATIONAL ? NotificationChannel.OPERATIONS : NotificationChannel.SIGNALS,
                type, severity, title, message, metadata, null);
    }

    public enum NotificationType {
        OPERATIONAL,
        SIGNAL
    }

    public enum NotificationSeverity {
        INFO,
        WARNING,
        ERROR
    }
}
