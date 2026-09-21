package com.omni.platform.modules.notifications.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import com.omni.platform.modules.notifications.controllers.ManualNotificationController.ManualLatestSignalNotificationResponse;
import com.omni.platform.modules.notifications.controllers.ManualNotificationController.ManualSignalNotificationRequest;
import com.omni.platform.modules.notifications.controllers.ManualNotificationController.ManualSignalNotificationResponse;
import com.omni.platform.modules.notifications.dtos.NotificationRequest;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationKind;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationSeverity;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationType;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.SignalChangedContent;
import com.omni.platform.modules.notifications.services.ManualLatestSignalNotificationService;
import com.omni.platform.modules.notifications.services.ManualLatestSignalNotificationService.LatestSignalResult;
import com.omni.platform.modules.notifications.services.NotificationOutboxService;
import com.omni.platform.modules.notifications.templates.SignalChangedNotificationTemplate;

@ExtendWith(MockitoExtension.class)
class ManualNotificationControllerTest {

    @Mock
    private NotificationOutboxService notificationOutboxService;
    @Mock
    private ManualLatestSignalNotificationService latestSignalNotificationService;

    @Test
    void sendSignalNotificationDurablyEnqueuesSignalNotificationWithDefaults() {
        var controller = controller();

        ResponseEntity<ManualSignalNotificationResponse> response = controller.sendSignalNotification(null);

        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationOutboxService).enqueue(captor.capture(), org.mockito.ArgumentMatchers.any(Instant.class));

        NotificationRequest request = captor.getValue();
        assertThat(request.type()).isEqualTo(NotificationType.SIGNAL);
        assertThat(request.severity()).isEqualTo(NotificationSeverity.INFO);
        assertThat(request.title()).isEqualTo("Manual signal notification test");
        assertThat(request.message()).contains("HOSE-HPG NEUTRAL -> BULLISH");
        assertThat(request.metadata()).containsEntry("symbolKey", "HOSE-HPG");
        assertThat(request.metadata()).containsEntry("manual", true);
        assertThat(request.deduplicationKey()).startsWith("manual:");

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo("ACCEPTED");
        assertThat(response.getBody().deliveryIdentity()).isEqualTo(request.deduplicationKey());
    }

    @Test
    void sendSignalNotificationDurablyEnqueuesSignalNotificationWithRequestValues() {
        var controller = controller();
        var body = new ManualSignalNotificationRequest(
                "Signal changed",
                "HPG changed to BEARISH",
                "HOSE-HPG",
                "BULLISH",
                "BEARISH",
                27000.0,
                "2026-08-06",
                List.of("MANUAL_CHECK"),
                "TREND_MOMENTUM_V1",
                "1d");

        ResponseEntity<ManualSignalNotificationResponse> response = controller.sendSignalNotification(body);

        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationOutboxService).enqueue(captor.capture(), org.mockito.ArgumentMatchers.any(Instant.class));

        NotificationRequest request = captor.getValue();
        assertThat(request.title()).isEqualTo("Signal changed");
        assertThat(request.message()).isEqualTo("HPG changed to BEARISH");
        assertThat(request.metadata()).containsEntry("previousSignal", "BULLISH");
        assertThat(request.metadata()).containsEntry("newSignal", "BEARISH");
        assertThat(request.metadata()).containsEntry("price", 27000.0);
        assertThat(request.metadata()).containsEntry("strategy", "TREND_MOMENTUM_V1");

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().metadata()).containsEntry("symbolKey", "HOSE-HPG");
    }

    @Test
    void sendLatestSignalNotificationDurablyEnqueuesTypedSignalChangedRequest() {
        var result = new LatestSignalResult(
                true,
                "COMPLETED",
                "HOSE-HPG",
                null,
                "BULLISH",
                28000.0,
                "2026-08-29",
                List.of("MOMENTUM"),
                4,
                "TREND_MOMENTUM_V1",
                "1d",
                "2026-08-29T10:00:00Z");
        when(latestSignalNotificationService.findLatest("HOSE-HPG")).thenReturn(result);

        ResponseEntity<ManualLatestSignalNotificationResponse> response =
                controller().sendLatestSignalNotification(" HOSE-HPG ");

        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationOutboxService).enqueue(captor.capture(), org.mockito.ArgumentMatchers.any(Instant.class));
        NotificationRequest request = captor.getValue();
        assertThat(request.kind()).isEqualTo(NotificationKind.SIGNAL_CHANGED);
        assertThat(request.deduplicationKey()).startsWith("manual-latest:");
        assertThat(request.metadata())
                .containsEntry("manual", true)
                .containsEntry("generatedAt", "2026-08-29T10:00:00Z");
        assertThat(request.structuredContent()).isInstanceOfSatisfying(
                SignalChangedContent.class,
                signal -> {
                    assertThat(signal.symbolKey()).isEqualTo("HOSE-HPG");
                    assertThat(signal.newSignal()).isEqualTo("BULLISH");
                    assertThat(signal.price()).isEqualTo(28000.0);
                    assertThat(signal.reasonCodes()).containsExactly("MOMENTUM");
                });

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo("ACCEPTED");
        assertThat(response.getBody().deliveryIdentity()).isEqualTo(request.deduplicationKey());
        verify(latestSignalNotificationService).findLatest("HOSE-HPG");
    }

    private ManualNotificationController controller() {
        return new ManualNotificationController(
                notificationOutboxService,
                latestSignalNotificationService,
                new SignalChangedNotificationTemplate());
    }
}
