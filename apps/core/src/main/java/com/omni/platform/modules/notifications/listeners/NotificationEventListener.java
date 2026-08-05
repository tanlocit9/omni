package com.omni.platform.modules.notifications.listeners;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.omni.platform.modules.notifications.events.OperationalNotificationEvent;
import com.omni.platform.modules.notifications.services.NotificationService;
import com.omni.platform.modules.notifications.templates.OperationalNotificationTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventListener {

    private final NotificationService notificationService;
    private final OperationalNotificationTemplate operationalNotificationTemplate;

    @Async
    @EventListener
    public void onOperationalNotification(OperationalNotificationEvent event) {
        try {
            log.info("Received operational notification event severity={} title={}", event.severity(), event.title());
            notificationService.send(operationalNotificationTemplate.render(event));
        } catch (Exception exc) {
            log.warn("Notification event handling failed: {}", exc.getMessage(), exc);
        }
    }
}
