package com.omni.platform.modules.notifications.events;

import java.util.Map;

import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationSeverity;

public record OperationalNotificationEvent(
        NotificationSeverity severity,
        String title,
        String message,
        Map<String, Object> metadata) {
}
