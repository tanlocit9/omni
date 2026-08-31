package com.omni.platform.modules.notifications.listeners;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.omni.platform.modules.notifications.dtos.NotificationRequest;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationSeverity;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationType;
import com.omni.platform.modules.notifications.events.OperationalNotificationEvent;
import com.omni.platform.modules.notifications.services.NotificationService;
import com.omni.platform.modules.notifications.events.SignalDigestItem;
import com.omni.platform.modules.notifications.events.SignalDigestNotificationEvent;
import com.omni.platform.modules.notifications.templates.OperationalNotificationTemplate;
import com.omni.platform.modules.notifications.templates.SignalChangedNotificationTemplate;
import com.omni.platform.modules.notifications.templates.SignalNotificationTemplate;

@ExtendWith(MockitoExtension.class)
class NotificationEventListenerTest {

    @Mock
    private NotificationService notificationService;

    private final OperationalNotificationTemplate operationalNotificationTemplate = new OperationalNotificationTemplate();
    private final SignalNotificationTemplate signalNotificationTemplate = new SignalNotificationTemplate();
    private final SignalChangedNotificationTemplate signalChangedNotificationTemplate = new SignalChangedNotificationTemplate();

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
    void onSignalDigestNotificationRendersAndSendsSignalNotificationOnce() {
        SignalDigestNotificationEvent event = signalDigestEvent();
        var listener = listener();

        listener.onSignalDigestNotification(event);

        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationService).send(captor.capture());
        verifyNoMoreInteractions(notificationService);

        NotificationRequest request = captor.getValue();
        assertThat(request.type()).isEqualTo(NotificationType.SIGNAL);
        assertThat(request.severity()).isEqualTo(NotificationSeverity.INFO);
        assertThat(request.title()).isEqualTo("Market signal changes: Sync market signals - daily BANKS");
        assertThat(request.message()).contains("1 signal change(s) detected");
        assertThat(request.message()).contains("HOSE-HPG: NEUTRAL -> BULLISH @ 28000.0");
        assertThat(request.metadata()).containsEntry("parentExecutionId", event.parentExecutionId());
        assertThat(request.metadata()).containsEntry("changedCount", 1);
    }

    @Test
    void onSignalDigestNotificationSwallowsNotificationServiceFailure() {
        SignalDigestNotificationEvent event = signalDigestEvent();
        doThrow(new IllegalStateException("telegram down")).when(notificationService).send(any());
        var listener = listener();

        assertDoesNotThrow(() -> listener.onSignalDigestNotification(event));

        verify(notificationService).send(any(NotificationRequest.class));
    }

    @Test
    void onSignalDigestNotificationIsAsyncTransactionalAfterCommitListener() throws NoSuchMethodException {
        Method method = NotificationEventListener.class.getDeclaredMethod(
                "onSignalDigestNotification",
                SignalDigestNotificationEvent.class);

        assertThat(method.isAnnotationPresent(Async.class)).isTrue();
        TransactionalEventListener annotation = method.getAnnotation(TransactionalEventListener.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    private NotificationEventListener listener() {
        return new NotificationEventListener(
                notificationService,
                operationalNotificationTemplate,
                signalNotificationTemplate,
                signalChangedNotificationTemplate);
    }

    private SignalDigestNotificationEvent signalDigestEvent() {
        UUID parentExecutionId = UUID.randomUUID();
        return new SignalDigestNotificationEvent(
                parentExecutionId,
                "Sync market signals - daily BANKS",
                "TREND_MOMENTUM_V1",
                "1d",
                2,
                1,
                List.of(new SignalDigestItem(
                        "HOSE-HPG",
                        "NEUTRAL",
                        "BULLISH",
                        "28000.0",
                        "2026-07-28",
                        "TREND_MOMENTUM_V1",
                        "1d",
                        "4",
                        List.of("PRICE_ABOVE_MA50", "SCORE_4"))),
                Map.of("jobType", "SYNC_SIGNALS"));
    }
}
