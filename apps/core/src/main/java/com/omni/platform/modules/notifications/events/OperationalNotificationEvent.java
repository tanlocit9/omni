package com.omni.platform.modules.notifications.events;

import java.util.Map;

import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationKind;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationSeverity;

public record OperationalNotificationEvent(
        NotificationKind kind,
        NotificationSeverity severity,
        String title,
        String message,
        Map<String, Object> metadata) {

    public OperationalNotificationEvent(
            NotificationSeverity severity,
            String title,
            String message,
            Map<String, Object> metadata) {
        this(NotificationKind.OPERATIONAL_GENERIC, severity, title, message, metadata);
    }
}
