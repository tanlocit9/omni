package com.omni.platform.modules.notifications.listeners;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.omni.platform.modules.notifications.dtos.NotificationRequest;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationType;
import com.omni.platform.modules.notifications.events.OperationalNotificationEvent;
import com.omni.platform.modules.notifications.services.NotificationService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventListener {

    private final NotificationService notificationService;

    @Async
    @EventListener
    public void onOperationalNotification(OperationalNotificationEvent event) {
        try {
            notificationService.send(new NotificationRequest(
                    NotificationType.OPERATIONAL,
                    event.severity(),
                    event.title(),
                    event.message(),
                    event.metadata()));
        } catch (Exception exc) {
            log.warn("Notification event handling failed: {}", exc.getMessage(), exc);
        }
    }
}
