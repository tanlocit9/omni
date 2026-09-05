package com.omni.platform.modules.notifications.templates;

import org.springframework.stereotype.Component;

import com.omni.platform.modules.notifications.dtos.NotificationRequest;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationType;
import com.omni.platform.modules.notifications.events.OperationalNotificationEvent;

@Component
public class OperationalNotificationTemplate extends AbstractNotificationTemplate<OperationalNotificationEvent> {

    @Override
    public NotificationRequest render(OperationalNotificationEvent event) {
        return new NotificationRequest(
                com.omni.platform.modules.notifications.dtos.NotificationChannel.OPERATIONS,
                NotificationType.OPERATIONAL,
                event.kind(),
                event.severity(),
                event.title(),
                event.message(),
                metadata(event.metadata()),
                null);
    }
}
