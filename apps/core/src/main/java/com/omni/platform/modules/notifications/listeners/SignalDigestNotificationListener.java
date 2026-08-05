package com.omni.platform.modules.notifications.listeners;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.omni.platform.modules.notifications.services.NotificationService;
import com.omni.platform.modules.scheduler.notifications.SignalDigestNotificationEvent;
import com.omni.platform.modules.scheduler.notifications.SignalNotificationTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class SignalDigestNotificationListener {

    private final NotificationService notificationService;
    private final SignalNotificationTemplate signalNotificationTemplate;

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
