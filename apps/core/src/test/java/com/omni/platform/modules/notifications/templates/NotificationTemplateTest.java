package com.omni.platform.modules.notifications.templates;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationSeverity;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationType;
import com.omni.platform.modules.notifications.events.OperationalNotificationEvent;
import com.omni.platform.modules.scheduler.notifications.SignalDigestItem;
import com.omni.platform.modules.scheduler.notifications.SignalDigestNotificationEvent;
import com.omni.platform.modules.scheduler.notifications.SignalNotificationTemplate;

class NotificationTemplateTest {

    @Test
    void operationalTemplateRendersOperationalRequestAndCopiesMetadata() {
        OperationalNotificationTemplate template = new OperationalNotificationTemplate();
        Map<String, Object> metadata = Map.of("executionId", "exec-1");

        var request = template.render(new OperationalNotificationEvent(
                NotificationSeverity.ERROR,
                "Job failed",
                "Failure details",
                metadata));

        assertThat(request.type()).isEqualTo(NotificationType.OPERATIONAL);
        assertThat(request.severity()).isEqualTo(NotificationSeverity.ERROR);
        assertThat(request.title()).isEqualTo("Job failed");
        assertThat(request.message()).isEqualTo("Failure details");
        assertThat(request.metadata()).containsEntry("executionId", "exec-1");
        assertThat(request.metadata()).isNotSameAs(metadata);
    }

    @Test
    void signalTemplateRendersSignalDigestRequestWithFallbacksAndMetadata() {
        SignalNotificationTemplate template = new SignalNotificationTemplate();
        UUID parentExecutionId = UUID.randomUUID();

        var request = template.render(new SignalDigestNotificationEvent(
                parentExecutionId,
                "BANKS Market Signal",
                "TREND_MOMENTUM_V1",
                "1d",
                3,
                1,
                List.of(new SignalDigestItem(
                        "HOSE-VCB",
                        "",
                        "BULLISH",
                        null,
                        "2026-08-04",
                        "TREND_MOMENTUM_V1",
                        "1d",
                        "4",
                        List.of("PRICE_ABOVE_MA50"))),
                Map.of("jobType", "SYNC_SIGNALS")));

        assertThat(request.type()).isEqualTo(NotificationType.SIGNAL);
        assertThat(request.severity()).isEqualTo(NotificationSeverity.INFO);
        assertThat(request.title()).isEqualTo("Market signal changes: BANKS Market Signal");
        assertThat(request.message()).contains("1 signal change(s) detected");
        assertThat(request.message()).contains("HOSE-VCB: BASELINE -> BULLISH @ n/a");
        assertThat(request.message()).contains("score=4");
        assertThat(request.metadata()).containsEntry("jobType", "SYNC_SIGNALS");
        assertThat(request.metadata()).containsEntry("parentExecutionId", parentExecutionId);
        assertThat(request.metadata()).containsEntry("strategy", "TREND_MOMENTUM_V1");
        assertThat(request.metadata()).containsEntry("timeframe", "1d");
        assertThat(request.metadata()).containsEntry("totalChildren", 3);
        assertThat(request.metadata()).containsEntry("changedCount", 1);
    }
}
