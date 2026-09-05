package com.omni.platform.modules.notifications.dtos;

import java.util.Map;

public record NotificationRequest(
        NotificationChannel channel,
        NotificationType type,
        NotificationKind kind,
        NotificationSeverity severity,
        String title,
        String message,
        Map<String, Object> metadata,
        String deduplicationKey) {

    public NotificationRequest(
            NotificationChannel channel,
            NotificationType type,
            NotificationSeverity severity,
            String title,
            String message,
            Map<String, Object> metadata,
            String deduplicationKey) {
        this(channel, type, defaultKind(type), severity, title, message, metadata, deduplicationKey);
    }

    public NotificationRequest(
            NotificationType type,
            NotificationSeverity severity,
            String title,
            String message,
            Map<String, Object> metadata) {
        this(type == NotificationType.OPERATIONAL ? NotificationChannel.OPERATIONS : NotificationChannel.SIGNALS,
                type, defaultKind(type), severity, title, message, metadata, null);
    }

    private static NotificationKind defaultKind(NotificationType type) {
        return type == NotificationType.OPERATIONAL
                ? NotificationKind.OPERATIONAL_GENERIC
                : NotificationKind.MANUAL_GENERIC;
    }

    public enum NotificationKind {
        OPERATIONAL_GENERIC,
        JOB_SUCCEEDED,
        JOB_FAILED,
        JOB_DIGEST_SUCCEEDED,
        JOB_DIGEST_FAILED,
        SIGNAL_CHANGED,
        SIGNAL_DIGEST,
        MANUAL_GENERIC
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
