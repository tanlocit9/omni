package com.omni.platform.modules.notifications.templates;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.omni.platform.modules.notifications.dtos.NotificationChannel;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationKind;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.SignalChangedContent;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.SignalDigestContent;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationSeverity;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationType;
import com.omni.platform.modules.notifications.events.OperationalNotificationEvent;
import com.omni.platform.modules.notifications.events.SignalChangedNotificationEvent;
import com.omni.platform.modules.notifications.events.SignalDigestItem;
import com.omni.platform.modules.notifications.events.SignalDigestNotificationEvent;

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

        assertThat(request.channel()).isEqualTo(NotificationChannel.SIGNALS);
        assertThat(request.type()).isEqualTo(NotificationType.SIGNAL);
        assertThat(request.kind()).isEqualTo(NotificationKind.SIGNAL_DIGEST);
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
        assertThat(request.deduplicationKey()).isEqualTo(parentExecutionId.toString());
        assertThat(request.structuredContent()).isInstanceOfSatisfying(SignalDigestContent.class, content -> {
            assertThat(content.changedCount()).isEqualTo(1);
            assertThat(content.strategy()).isEqualTo("TREND_MOMENTUM_V1");
            assertThat(content.items()).singleElement().satisfies(item -> {
                assertThat(item.symbolKey()).isEqualTo("HOSE-VCB");
                assertThat(item.newSignal()).isEqualTo("BULLISH");
                assertThat(item.reasonCodes()).containsExactly("PRICE_ABOVE_MA50");
            });
        });
    }

    @Test
    void signalChangedTemplatePreservesEveryStructuredFieldAndDeduplicationIdentity() {
        UUID executionId = UUID.fromString("44e8cce7-7197-42d7-93ce-e64d3002e88a");
        UUID parentExecutionId = UUID.fromString("adf8625c-cb75-42c5-ae99-621566b5b89d");
        Instant createdAt = Instant.parse("2026-08-29T08:30:00Z");
        var request = new SignalChangedNotificationTemplate().render(new SignalChangedNotificationEvent(
                executionId, parentExecutionId, "SET:PTT", "HOLD", "BUY", 34.75, "2026-08-29",
                List.of("RSI_OVERSOLD", "MACD_CROSS"), 0.91, "momentum-v1", "1d", createdAt,
                Map.of("source", "analyzer"), "delivery-identity"));

        assertThat(request.channel()).isEqualTo(NotificationChannel.SIGNALS);
        assertThat(request.kind()).isEqualTo(NotificationKind.SIGNAL_CHANGED);
        assertThat(request.deduplicationKey()).isEqualTo("delivery-identity");
        assertThat(request.message()).isEqualTo("SET:PTT: HOLD -> BUY @ 34.75 (2026-08-29, score=0.91)");
        assertThat(request.metadata()).containsEntry("previousSignal", "HOLD")
                .containsEntry("newSignal", "BUY")
                .containsEntry("price", 34.75)
                .containsEntry("signalDate", "2026-08-29")
                .containsEntry("score", 0.91);
        assertThat(request.structuredContent()).isEqualTo(new SignalChangedContent(
                "SET:PTT", "HOLD", "BUY", 34.75, "2026-08-29", 0.91,
                List.of("RSI_OVERSOLD", "MACD_CROSS"), "momentum-v1", "1d", createdAt));
    }
}
