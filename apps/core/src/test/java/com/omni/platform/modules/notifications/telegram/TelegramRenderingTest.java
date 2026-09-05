package com.omni.platform.modules.notifications.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.omni.platform.modules.notifications.configs.TelegramNotificationProperties;
import com.omni.platform.modules.notifications.dtos.NotificationChannel;
import com.omni.platform.modules.notifications.dtos.NotificationRequest;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationKind;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationSeverity;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationType;
import com.omni.platform.modules.notifications.telegram.TelegramRendering.Registry;

class TelegramRenderingTest {

    @Test
    void rendersExactOrderedJobFailureAndMakesErrorAudible() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("executionId", UUID.fromString("7dd9f8c2-1111-2222-3333-444444444444"));
        metadata.put("recordsSkipped", 20);
        metadata.put("source", "SCHEDULER");
        metadata.put("recordsSynced", 12_450);
        metadata.put("jobType", "SYNC_SIGNALS");
        NotificationRequest request = request(
                NotificationChannel.OPERATIONS,
                NotificationType.OPERATIONAL,
                NotificationKind.JOB_FAILED,
                NotificationSeverity.ERROR,
                "Job failed",
                "Analyzer request timed out",
                metadata);

        var rendered = registry().render(request, 0);

        assertThat(rendered.html()).isEqualTo("""
                🚨 <b>Job failed</b>

                Analyzer request timed out

                <b>Job:</b> SYNC_SIGNALS
                <b>Source:</b> SCHEDULER
                <b>Records:</b> 12,450 synced - 20 skipped
                <b>Execution:</b> 7dd9f8c2""");
        assertThat(rendered.disableNotification()).isFalse();
    }

    @Test
    void genericFallbackEscapesOnceFiltersSensitiveValuesAndSortsScalars() {
        NotificationRequest request = request(
                NotificationChannel.SIGNALS,
                NotificationType.SIGNAL,
                NotificationKind.MANUAL_GENERIC,
                NotificationSeverity.WARNING,
                "A & <B>",
                "x & y > z",
                Map.of("zeta", "last", "apiToken", "secret", "<alpha>", "<first> & last", "payload", "raw"));

        var rendered = registry().render(request, 0);

        assertThat(rendered.html()).isEqualTo("""
                ⚠️ <b>A \u0026amp; \u0026lt;B\u0026gt;</b>

                x \u0026amp; y \u0026gt; z

                <b>Details</b>
                - \u0026lt;alpha\u0026gt;: \u0026lt;first\u0026gt; \u0026amp; last
                - zeta: last""");
        assertThat(rendered.disableNotification()).isTrue();
    }

    @Test
    void boundsUnicodeContentWithoutSplittingSurrogatePairsOrHtmlBlocks() {
        String message = "<unsafe> & " + "😀".repeat(5_000);
        var rendered = registry().render(request(
                NotificationChannel.OPERATIONS,
                NotificationType.OPERATIONAL,
                NotificationKind.OPERATIONAL_GENERIC,
                NotificationSeverity.INFO,
                "Unicode",
                message,
                Map.of()), 3);

        assertThat(rendered.html()).hasSizeLessThanOrEqualTo(TelegramRendering.MAX_MESSAGE_LENGTH);
        assertThat(rendered.html()).doesNotContain("<unsafe>");
        assertThat(rendered.html()).contains("\u0026lt;unsafe\u0026gt;");
        assertThat(rendered.html()).doesNotEndWith("\uD83D");
        assertThat(rendered.html()).contains("Repeated notifications suppressed: 3");
    }

    @Test
    void appliesBangkokTimezoneAndRejectsInvalidConfiguration() {
        NotificationRequest request = request(
                NotificationChannel.OPERATIONS,
                NotificationType.OPERATIONAL,
                NotificationKind.OPERATIONAL_GENERIC,
                NotificationSeverity.INFO,
                "Updated",
                null,
                Map.of("createdAt", "2026-09-04T10:22:00Z"));

        assertThat(registry().render(request, 0).html()).contains("<i>Updated 17:22 ICT</i>");
        TelegramNotificationProperties invalid = properties("Mars/Olympus", null);
        assertThatThrownBy(() -> new Registry(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid Telegram display time zone");
    }

    @Test
    void preservesLegacySignalRenderingUntilP8I2() {
        NotificationRequest request = request(
                NotificationChannel.SIGNALS,
                NotificationType.SIGNAL,
                NotificationKind.SIGNAL_CHANGED,
                NotificationSeverity.INFO,
                "Signal changed: HOSE-FPT",
                "BASELINE -> BUY",
                Map.of("price", 126500));

        assertThat(registry().render(request, 0).html()).isEqualTo("""
                <b>INFO</b> - <b>Signal changed: HOSE-FPT</b>

                BASELINE -\u0026gt; BUY""");
    }

    private Registry registry() {
        return new Registry(properties(null, null));
    }

    private TelegramNotificationProperties properties(String zone, Boolean audibleErrors) {
        return new TelegramNotificationProperties(
                true, "token", "operations", "signals", "Markdown", "https://api.telegram.org",
                Duration.ofMinutes(5), 100, zone, audibleErrors);
    }

    private NotificationRequest request(
            NotificationChannel channel,
            NotificationType type,
            NotificationKind kind,
            NotificationSeverity severity,
            String title,
            String message,
            Map<String, Object> metadata) {
        return new NotificationRequest(channel, type, kind, severity, title, message, metadata, null);
    }
}
