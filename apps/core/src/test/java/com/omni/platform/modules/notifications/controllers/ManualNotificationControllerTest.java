package com.omni.platform.modules.notifications.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import com.omni.platform.modules.notifications.controllers.ManualNotificationController.ManualSignalNotificationRequest;
import com.omni.platform.modules.notifications.controllers.ManualNotificationController.ManualSignalNotificationResponse;
import com.omni.platform.modules.notifications.dtos.NotificationRequest;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationSeverity;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationType;
import com.omni.platform.modules.notifications.services.ManualLatestSignalNotificationService;
import com.omni.platform.modules.notifications.services.ManualLatestSignalNotificationService.LatestSignalNotificationResult;
import com.omni.platform.modules.notifications.services.NotificationService;

@ExtendWith(MockitoExtension.class)
class ManualNotificationControllerTest {

    @Mock
    private NotificationService notificationService;
    @Mock
    private ManualLatestSignalNotificationService latestSignalNotificationService;

    @Test
    void sendSignalNotificationSendsSignalNotificationWithDefaults() {
        var controller = controller();

        ResponseEntity<ManualSignalNotificationResponse> response = controller.sendSignalNotification(null);

        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationService).send(captor.capture());

        NotificationRequest request = captor.getValue();
        assertThat(request.type()).isEqualTo(NotificationType.SIGNAL);
        assertThat(request.severity()).isEqualTo(NotificationSeverity.INFO);
        assertThat(request.title()).isEqualTo("Manual signal notification test");
        assertThat(request.message()).contains("HOSE-HPG NEUTRAL -> BULLISH");
        assertThat(request.metadata()).containsEntry("symbolKey", "HOSE-HPG");
        assertThat(request.metadata()).containsEntry("manual", true);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo("SENT");
    }

    @Test
    void sendSignalNotificationSendsSignalNotificationWithRequestValues() {
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
        verify(notificationService).send(captor.capture());

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
    void sendLatestSignalNotificationReturnsAccepted() {
        var result = new LatestSignalNotificationResult(
                true, "ACCEPTED", "HOSE-HPG", "BULLISH", "2026-08-29", "2026-08-29T10:00:00Z");
        when(latestSignalNotificationService.sendLatest("HOSE-HPG")).thenReturn(result);

        ResponseEntity<LatestSignalNotificationResult> response =
                controller().sendLatestSignalNotification(" HOSE-HPG ");

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).isEqualTo(result);
        verify(latestSignalNotificationService).sendLatest("HOSE-HPG");
    }

    private ManualNotificationController controller() {
        return new ManualNotificationController(notificationService, latestSignalNotificationService);
    }
}
