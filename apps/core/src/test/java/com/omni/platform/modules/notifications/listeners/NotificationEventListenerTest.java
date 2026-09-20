package com.omni.platform.modules.notifications.listeners;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.omni.platform.modules.notifications.dtos.NotificationRequest;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationSeverity;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationType;
import com.omni.platform.modules.notifications.events.OperationalNotificationEvent;
import com.omni.platform.modules.notifications.services.NotificationService;
import com.omni.platform.modules.notifications.templates.OperationalNotificationTemplate;

@ExtendWith(MockitoExtension.class)
class NotificationEventListenerTest {

    @Mock
    private NotificationService notificationService;

    private final OperationalNotificationTemplate operationalNotificationTemplate = new OperationalNotificationTemplate();

    @Test
    void onOperationalNotificationBuildsOperationalNotificationRequest() {
        OperationalNotificationEvent event = new OperationalNotificationEvent(
                NotificationSeverity.ERROR,
                "Consumer failed",
                "Failed to process message",
                Map.of("topic", "topic-sync-job-status"));

        var listener = listener();

        listener.onOperationalNotification(event);

        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationService).send(captor.capture());
        verifyNoMoreInteractions(notificationService);

        NotificationRequest request = captor.getValue();
        assertThat(request.type()).isEqualTo(NotificationType.OPERATIONAL);
        assertThat(request.severity()).isEqualTo(NotificationSeverity.ERROR);
        assertThat(request.title()).isEqualTo("Consumer failed");
        assertThat(request.message()).isEqualTo("Failed to process message");
        assertThat(request.metadata()).containsEntry("topic", "topic-sync-job-status");
    }

    @Test
    void onOperationalNotificationSwallowsNotificationServiceFailure() {
        OperationalNotificationEvent event = new OperationalNotificationEvent(
                NotificationSeverity.ERROR,
                "Consumer failed",
                "Failed to process message",
                Map.of());
        doThrow(new IllegalStateException("telegram down")).when(notificationService).send(any());

        var listener = listener();

        assertDoesNotThrow(() -> listener.onOperationalNotification(event));

        verify(notificationService).send(any(NotificationRequest.class));
    }

    @Test
    void signalChangedDirectListenerIsRemoved() {
        assertThat(NotificationEventListener.class.getDeclaredMethods())
                .noneMatch(method -> method.getName().equals("onSignalChangedNotification"));
    }

    private NotificationEventListener listener() {
        return new NotificationEventListener(notificationService, operationalNotificationTemplate);
    }

}
