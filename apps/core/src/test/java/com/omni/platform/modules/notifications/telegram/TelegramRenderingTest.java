package com.omni.platform.modules.notifications.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.omni.platform.modules.notifications.configs.TelegramNotificationProperties;
import com.omni.platform.modules.notifications.dtos.NotificationChannel;
import com.omni.platform.modules.notifications.dtos.NotificationRequest;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationKind;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.SignalChangedContent;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.SignalDigestContent;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.SignalDigestEntry;
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
    void rendersExactBuySignalFromTypedContentAndEscapesOnce() {
        NotificationRequest request = signalRequest(NotificationKind.SIGNAL_CHANGED, new SignalChangedContent(
                "HOSE-<FPT>", "baseline", "buy", 126500.12567, "2026-08-31", 0.846,
                List.of("TREND_UP", "MOMENTUM_&STRONG"), "Trend & Momentum", "1d",
                Instant.parse("2026-08-31T10:22:00Z")));

        var rendered = registry().render(request, 0);

        assertThat(rendered.html()).isEqualTo("""
                🟢 <b>BUY · HOSE-\u0026lt;FPT\u0026gt;</b>

                Trend \u0026amp; Momentum · 1D

                <b>Price:</b> 126,500.1257
                <b>Signal:</b> BASELINE → BUY
                <b>Score:</b> 0.85
                <b>Date:</b> 31 Aug 2026
                <b>Reasons:</b> TREND_UP, MOMENTUM_\u0026amp;STRONG

                <i>Updated 17:22 ICT</i>""");
        assertThat(rendered.disableNotification()).isTrue();
    }

    @Test
    void mapsSellHoldAliasesAndUnknownWithoutChangingSourceContent() {
        assertThat(registry().render(signalRequest(NotificationKind.SIGNAL_CHANGED,
                signal("BEARISH")), 0).html()).startsWith("🔴 <b>BEARISH");
        assertThat(registry().render(signalRequest(NotificationKind.SIGNAL_CHANGED,
                signal("NEUTRAL")), 0).html()).startsWith("⚪ <b>NEUTRAL");
        assertThat(registry().render(signalRequest(NotificationKind.SIGNAL_CHANGED,
                signal("watch")), 0).html()).startsWith("⚪ <b>UNKNOWN")
                .contains("BASELINE → WATCH");
    }

    @Test
    void digestAdmitsOnlyCompleteBlocksReservesFooterAndReportsCounts() {
        String longReason = "😀<&>".repeat(100);
        List<SignalDigestEntry> items = java.util.stream.IntStream.range(0, 150)
                .mapToObj(index -> new SignalDigestEntry("SYM-" + index, "HOLD", "BUY", 12345.6789,
                        "2026-08-31", 0.846, List.of(longReason), "Momentum", "1d"))
                .toList();
        NotificationRequest request = signalRequest(NotificationKind.SIGNAL_DIGEST,
                new SignalDigestContent("Momentum", "1d", 150, items, Instant.parse("2026-08-31T10:22:00Z")));

        String html = registry().render(request, 4).html();

        assertThat(html).hasSizeLessThanOrEqualTo(TelegramRendering.MAX_MESSAGE_LENGTH)
                .contains("📊 <b>150 signal changes · Momentum · 1D</b>")
                .containsPattern("Showing \\d+ of 150 · \\d+ omitted · Updated 17:22 ICT")
                .contains("Repeated notifications suppressed: 4")
                .doesNotContain("<&>")
                .doesNotEndWith("\uD83D");
        int shown = Integer.parseInt(html.replaceFirst("(?s).*Showing (\\d+) of 150.*", "$1"));
        int omitted = Integer.parseInt(html.replaceFirst("(?s).* of 150 · (\\d+) omitted.*", "$1"));
        assertThat(shown).isLessThanOrEqualTo(100);
        assertThat(shown + omitted).isEqualTo(150);
        assertThat(html.split("<b>Date:</b>", -1).length - 1).isEqualTo(shown);
    }

    @Test
    void rejectsCanonicalSignalKindsWithoutRequiredStructuredContent() {
        assertThatThrownBy(() -> request(NotificationChannel.SIGNALS, NotificationType.SIGNAL,
                NotificationKind.SIGNAL_CHANGED, NotificationSeverity.INFO, "ignored", "ignored", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SIGNAL_CHANGED requires SignalChangedContent");
        assertThatThrownBy(() -> request(NotificationChannel.SIGNALS, NotificationType.SIGNAL,
                NotificationKind.SIGNAL_DIGEST, NotificationSeverity.INFO, "ignored", "ignored", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SIGNAL_DIGEST requires SignalDigestContent");
    }

    @Test
    void rejectsMismatchedStructuredContentForCanonicalSignalKinds() {
        SignalChangedContent changed = signal("BUY");
        SignalDigestContent digest = new SignalDigestContent("Momentum", "1d", 0, List.of(), Instant.EPOCH);

        assertThatThrownBy(() -> signalRequest(NotificationKind.SIGNAL_CHANGED, digest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SIGNAL_CHANGED requires SignalChangedContent");
        assertThatThrownBy(() -> signalRequest(NotificationKind.SIGNAL_DIGEST, changed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SIGNAL_DIGEST requires SignalDigestContent");
    }

    @Test
    void rejectsStructuredSignalContentForManualGenericRequests() {
        assertThatThrownBy(() -> signalRequest(NotificationKind.MANUAL_GENERIC, signal("BUY")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("MANUAL_GENERIC does not accept SignalChangedContent");
    }

    @Test
    void doesNotExposeImplicitKindNotificationRequestConstructors() {
        assertThatThrownBy(() -> NotificationRequest.class.getConstructor(
                NotificationChannel.class,
                NotificationType.class,
                NotificationSeverity.class,
                String.class,
                String.class,
                Map.class,
                String.class))
                .isInstanceOf(NoSuchMethodException.class);
        assertThatThrownBy(() -> NotificationRequest.class.getConstructor(
                NotificationType.class,
                NotificationSeverity.class,
                String.class,
                String.class,
                Map.class))
                .isInstanceOf(NoSuchMethodException.class);
    }

    private SignalChangedContent signal(String newSignal) {
        return new SignalChangedContent("SET:PTT", null, newSignal, null, null, null, null, null, null, null);
    }

    private NotificationRequest signalRequest(NotificationKind kind, NotificationRequest.StructuredContent content) {
        return new NotificationRequest(NotificationChannel.SIGNALS, NotificationType.SIGNAL, kind,
                NotificationSeverity.INFO, "plain title", "plain fallback", Map.of(), "stable-key", content);
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
