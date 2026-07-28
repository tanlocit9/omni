package com.omni.platform.modules.notifications.listeners;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.omni.platform.modules.notifications.dtos.NotificationRequest;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationSeverity;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationType;
import com.omni.platform.modules.notifications.events.OperationalNotificationEvent;
import com.omni.platform.modules.notifications.services.NotificationService;

@ExtendWith(MockitoExtension.class)
class NotificationEventListenerTest {

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private NotificationEventListener listener;

    @Test
    void onOperationalNotificationBuildsOperationalNotificationRequest() {
        OperationalNotificationEvent event = new OperationalNotificationEvent(
                NotificationSeverity.ERROR,
                "Consumer failed",
                "Failed to process message",
                Map.of("topic", "topic-sync-job-status"));

        listener.onOperationalNotification(event);

        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationService).send(captor.capture());
        NotificationRequest request = captor.getValue();
        assert request.type() == NotificationType.OPERATIONAL;
        assert request.severity() == NotificationSeverity.ERROR;
        assert request.title().equals("Consumer failed");
        assert request.message().equals("Failed to process message");
        assert request.metadata().get("topic").equals("topic-sync-job-status");
    }

    @Test
    void onOperationalNotificationSwallowsNotificationServiceFailure() {
        OperationalNotificationEvent event = new OperationalNotificationEvent(
                NotificationSeverity.ERROR,
                "Consumer failed",
                "Failed to process message",
                Map.of());
        doThrow(new IllegalStateException("telegram down")).when(notificationService).send(org.mockito.ArgumentMatchers.any());

        assertDoesNotThrow(() -> listener.onOperationalNotification(event));
    }
}
