package com.omni.platform.modules.notifications.listeners;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.omni.platform.modules.notifications.events.OperationalNotificationEvent;
import com.omni.platform.modules.notifications.events.SignalChangedNotificationEvent;
import com.omni.platform.modules.notifications.events.SignalDigestNotificationEvent;
import com.omni.platform.modules.notifications.services.NotificationService;
import com.omni.platform.modules.notifications.templates.OperationalNotificationTemplate;
import com.omni.platform.modules.notifications.templates.SignalChangedNotificationTemplate;
import com.omni.platform.modules.notifications.templates.SignalNotificationTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventListener {

    private final NotificationService notificationService;
    private final OperationalNotificationTemplate operationalNotificationTemplate;
    private final SignalNotificationTemplate signalNotificationTemplate;
    private final SignalChangedNotificationTemplate signalChangedNotificationTemplate;

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

    @Async
    @EventListener
    public void onSignalChangedNotification(SignalChangedNotificationEvent event) {
        try {
            log.info("Received immediate signal notification event executionId={} symbolKey={}",
                    event.executionId(), event.symbolKey());
            notificationService.send(signalChangedNotificationTemplate.render(event));
        } catch (Exception exc) {
            log.warn("Immediate signal notification handling failed: {}", exc.getMessage(), exc);
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSignalDigestNotification(SignalDigestNotificationEvent event) {
        try {
            log.info("Received signal digest notification event parentExecutionId={} changedCount={} totalChildren={}",
                    event.parentExecutionId(), event.changedCount(), event.totalChildren());
            notificationService.send(signalNotificationTemplate.render(event));
        } catch (Exception exc) {
            log.warn("Signal digest notification handling failed: {}", exc.getMessage(), exc);
        }
    }
}
