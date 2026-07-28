package com.omni.platform.modules.notifications.dtos;

import java.util.Map;

public record NotificationRequest(
        NotificationType type,
        NotificationSeverity severity,
        String title,
        String message,
        Map<String, Object> metadata) {

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
