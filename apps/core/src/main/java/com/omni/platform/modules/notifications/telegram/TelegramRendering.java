package com.omni.platform.modules.notifications.telegram;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.omni.platform.modules.notifications.configs.TelegramNotificationProperties;
import com.omni.platform.modules.notifications.dtos.NotificationChannel;
import com.omni.platform.modules.notifications.dtos.NotificationRequest;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationKind;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.SignalChangedContent;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.SignalDigestContent;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.SignalDigestEntry;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationSeverity;

public final class TelegramRendering {

    public static final int MAX_MESSAGE_LENGTH = 4_096;
    private static final String NEW_BLOCK = "\n\n";

    private TelegramRendering() {
    }

    public record RenderedMessage(String html, boolean disableNotification) {
    }

    public interface Renderer {
        boolean supports(NotificationRequest request);

        String render(NotificationRequest request, long suppressedCount);
    }

    @Component
    public static final class Registry {
        private final List<Renderer> renderers;
        private final SoundPolicy soundPolicy;

        public Registry(TelegramNotificationProperties properties) {
            ZoneId displayZone = properties.resolvedDisplayTimeZone();
            this.renderers = List.of(
                    new OperationalRenderer(displayZone),
                    new SignalChangedRenderer(displayZone),
                    new SignalDigestRenderer(displayZone),
                    new GenericRenderer());
            this.soundPolicy = new SoundPolicy(properties.resolvedAudibleOperationalErrors());
        }

        public RenderedMessage render(NotificationRequest request, long suppressedCount) {
            Renderer renderer = renderers.stream()
                    .filter(candidate -> candidate.supports(request))
                    .findFirst()
                    .orElseThrow();
            return new RenderedMessage(
                    renderer.render(request, suppressedCount),
                    soundPolicy.disableNotification(request));
        }
    }

    static final class SoundPolicy {
        private final boolean audibleOperationalErrors;

        SoundPolicy(boolean audibleOperationalErrors) {
            this.audibleOperationalErrors = audibleOperationalErrors;
        }

        boolean disableNotification(NotificationRequest request) {
            return request.channel() != NotificationChannel.OPERATIONS
                    || request.severity() != NotificationSeverity.ERROR
                    || !audibleOperationalErrors;
        }
    }

    static final class OperationalRenderer implements Renderer {
        private static final List<Detail> DETAILS = List.of(
                new Detail("jobType", "Job"),
                new Detail("source", "Source"),
                new Detail("executionId", "Execution"),
                new Detail("parentExecutionId", "Parent"));
        private final ZoneId displayZone;

        OperationalRenderer(ZoneId displayZone) {
            this.displayZone = displayZone;
        }

        @Override
        public boolean supports(NotificationRequest request) {
            return request.kind() == NotificationKind.OPERATIONAL_GENERIC
                    || request.kind() == NotificationKind.JOB_SUCCEEDED
                    || request.kind() == NotificationKind.JOB_FAILED
                    || request.kind() == NotificationKind.JOB_DIGEST_SUCCEEDED
                    || request.kind() == NotificationKind.JOB_DIGEST_FAILED;
        }

        @Override
        public String render(NotificationRequest request, long suppressedCount) {
            Builder builder = new Builder();
            builder.required(marker(request.severity()) + " <b>" + Html.escape(bound(request.title(), 320, "Untitled notification")) + "</b>");
            builder.optional(escapedBound(request.message(), 1_500));

            Map<String, Object> metadata = safeMetadata(request.metadata());
            List<String> details = new ArrayList<>();
            appendDetail(details, metadata, DETAILS.get(0));
            appendDetail(details, metadata, DETAILS.get(1));
            appendRecords(details, metadata);
            appendTasks(details, metadata);
            appendDetail(details, metadata, DETAILS.get(2));
            appendDetail(details, metadata, DETAILS.get(3));
            appendTime(details, metadata, displayZone);
            builder.optional(String.join("\n", details));
            builder.optional(suppression(suppressedCount));
            return builder.build();
        }

        private void appendDetail(List<String> details, Map<String, Object> metadata, Detail detail) {
            Object value = metadata.get(detail.key());
            if (value != null) {
                details.add("<b>" + detail.label() + ":</b> " + Html.escape(display(value)));
            }
        }

        private void appendRecords(List<String> details, Map<String, Object> metadata) {
            String synced = number(metadata.get("recordsSynced"));
            String skipped = number(metadata.get("recordsSkipped"));
            if (synced != null || skipped != null) {
                details.add("<b>Records:</b> " + (synced == null ? "0" : synced) + " synced"
                        + (skipped == null ? "" : " - " + skipped + " skipped"));
            }
        }

        private void appendTasks(List<String> details, Map<String, Object> metadata) {
            String failed = number(metadata.get("failed"));
            String total = number(metadata.get("total"));
            if (failed != null || total != null) {
                details.add("<b>Tasks:</b> " + (failed == null ? "0" : failed) + "/" + (total == null ? "0" : total) + " failed");
            }
        }

        private void appendTime(List<String> details, Map<String, Object> metadata, ZoneId zone) {
            Object value = metadata.get("createdAt");
            if (value == null) {
                value = metadata.get("generatedAt");
            }
            try {
                if (value != null) {
                    Instant instant = value instanceof Instant item ? item : Instant.parse(String.valueOf(value));
                    details.add("<i>Updated " + DateTimeFormatter.ofPattern("HH:mm z", Locale.ENGLISH).withZone(zone).format(instant) + "</i>");
                }
            } catch (RuntimeException ignored) {
                // Invalid optional timestamps are omitted rather than guessed.
            }
        }

        private record Detail(String key, String label) {
        }
    }

    static final class SignalChangedRenderer implements Renderer {
        private final ZoneId displayZone;

        SignalChangedRenderer(ZoneId displayZone) {
            this.displayZone = displayZone;
        }

        @Override
        public boolean supports(NotificationRequest request) {
            return request.kind() == NotificationKind.SIGNAL_CHANGED;
        }

        @Override
        public String render(NotificationRequest request, long suppressedCount) {
            if (!(request.structuredContent() instanceof SignalChangedContent signal)) {
                throw new IllegalArgumentException("SIGNAL_CHANGED requires SignalChangedContent");
            }
            SignalStyle style = signalStyle(signal.newSignal());
            Builder builder = new Builder();
            builder.required(style.marker() + " <b>" + Html.escape(style.label()) + " · "
                    + Html.escape(bound(signal.symbolKey(), 120, "Unknown symbol")) + "</b>");
            builder.optional(subtitle(signal.strategy(), signal.timeframe()));
            List<String> details = new ArrayList<>();
            details.add("<b>Price:</b> " + formatNumber(signal.price()));
            details.add("<b>Signal:</b> " + Html.escape(signalName(signal.previousSignal(), "BASELINE"))
                    + " → " + Html.escape(signalName(signal.newSignal(), "UNKNOWN")));
            details.add("<b>Score:</b> " + formatScore(signal.score()));
            details.add("<b>Date:</b> " + formatDate(signal.signalDate()));
            details.add("<b>Reasons:</b> " + formatReasons(signal.reasonCodes()));
            builder.optional(String.join("\n", details));
            builder.optional(updated(signal.createdAt(), displayZone));
            builder.optional(suppression(suppressedCount));
            return builder.build();
        }
    }

    static final class SignalDigestRenderer implements Renderer {
        private static final int ABSOLUTE_ITEM_CAP = 100;
        private final ZoneId displayZone;

        SignalDigestRenderer(ZoneId displayZone) {
            this.displayZone = displayZone;
        }

        @Override
        public boolean supports(NotificationRequest request) {
            return request.kind() == NotificationKind.SIGNAL_DIGEST;
        }

        @Override
        public String render(NotificationRequest request, long suppressedCount) {
            if (!(request.structuredContent() instanceof SignalDigestContent digest)) {
                throw new IllegalArgumentException("SIGNAL_DIGEST requires SignalDigestContent");
            }
            List<SignalDigestEntry> items = digest.items() == null ? List.of() : digest.items();
            int total = Math.max(0, digest.changedCount());
            String header = "📊 <b>" + total + " signal " + (total == 1 ? "change" : "changes")
                    + subtitleInline(digest.strategy(), digest.timeframe()) + "</b>";
            String suppression = suppression(suppressedCount);
            int shown = 0;
            List<String> blocks = new ArrayList<>();
            int candidates = Math.min(Math.min(items.size(), total), ABSOLUTE_ITEM_CAP);
            for (int index = 0; index < candidates; index++) {
                SignalDigestEntry item = items.get(index);
                if (item == null) {
                    continue;
                }
                String block = digestItem(item);
                int proposedShown = shown + 1;
                String footer = digestFooter(proposedShown, total, digest.createdAt(), displayZone);
                int length = joinedLength(header, blocks, block, footer, suppression);
                if (length > MAX_MESSAGE_LENGTH) {
                    break;
                }
                blocks.add(block);
                shown = proposedShown;
            }
            String footer = digestFooter(shown, total, digest.createdAt(), displayZone);
            Builder builder = new Builder();
            builder.required(header);
            blocks.forEach(builder::optional);
            builder.required(footer);
            builder.optional(suppression);
            return builder.build();
        }
    }

    static final class GenericRenderer implements Renderer {
        private static final Pattern SENSITIVE = Pattern.compile(
                ".*(credential|token|secret|password|authorization|cookie|stack|payload).*",
                Pattern.CASE_INSENSITIVE);
        private static final Set<String> INTERNAL = Set.of("notificationKind", "deliveryIdentity", "deduplicationKey", "manual");

        @Override
        public boolean supports(NotificationRequest request) {
            return request.kind() != NotificationKind.SIGNAL_CHANGED
                    && request.kind() != NotificationKind.SIGNAL_DIGEST;
        }

        @Override
        public String render(NotificationRequest request, long suppressedCount) {
            Builder builder = new Builder();
            builder.required(marker(request.severity()) + " <b>" + Html.escape(bound(request.title(), 320, "Untitled notification")) + "</b>");
            builder.optional(escapedBound(request.message(), 2_800));
            List<String> values = safeMetadata(request.metadata()).entrySet().stream()
                    .filter(entry -> !INTERNAL.contains(entry.getKey()))
                    .filter(entry -> !SENSITIVE.matcher(entry.getKey()).matches())
                    .filter(entry -> scalar(entry.getValue()))
                    .sorted(Comparator.comparing(Map.Entry::getKey))
                    .limit(8)
                    .map(entry -> "- " + Html.escape(bound(entry.getKey(), 80, "detail")) + ": "
                            + Html.escape(bound(String.valueOf(entry.getValue()), 240, "")))
                    .toList();
            builder.optional(values.isEmpty() ? null : "<b>Details</b>\n" + String.join("\n", values));
            builder.optional(suppression(suppressedCount));
            return builder.build();
        }

        private boolean scalar(Object value) {
            return value instanceof CharSequence || value instanceof Number || value instanceof Boolean
                    || value instanceof Enum<?> || value instanceof UUID;
        }
    }

    static final class Builder {
        private final StringBuilder html = new StringBuilder();

        void required(String block) {
            append(block);
        }

        boolean optional(String block) {
            if (block == null || block.isBlank()) {
                return false;
            }
            int separator = html.isEmpty() ? 0 : NEW_BLOCK.length();
            if (html.length() + separator + block.length() > MAX_MESSAGE_LENGTH) {
                return false;
            }
            append(block);
            return true;
        }

        String build() {
            if (html.length() > MAX_MESSAGE_LENGTH) {
                throw new IllegalStateException("Required Telegram block exceeds message limit");
            }
            return html.toString();
        }

        private void append(String block) {
            if (!html.isEmpty()) {
                html.append(NEW_BLOCK);
            }
            html.append(block);
        }
    }

    static final class Html {
        private Html() {
        }

        static String escape(String value) {
            String ampersand = "&" + "amp;";
            String lessThan = "&" + "lt;";
            String greaterThan = "&" + "gt;";
            return value.replace("&", ampersand).replace("<", lessThan).replace(">", greaterThan);
        }
    }

    private static String subtitle(String strategy, String timeframe) {
        List<String> values = new ArrayList<>();
        if (strategy != null && !strategy.isBlank()) {
            values.add(Html.escape(bound(strategy, 100, "")));
        }
        if (timeframe != null && !timeframe.isBlank()) {
            values.add(Html.escape(bound(timeframe.toUpperCase(Locale.ROOT), 30, "")));
        }
        return values.isEmpty() ? null : String.join(" · ", values);
    }

    private static String subtitleInline(String strategy, String timeframe) {
        String value = subtitle(strategy, timeframe);
        return value == null ? "" : " · " + value;
    }

    private static SignalStyle signalStyle(String source) {
        String normalized = signalName(source, "UNKNOWN");
        return switch (normalized) {
            case "BUY", "BULLISH" -> new SignalStyle("🟢", normalized);
            case "SELL", "BEARISH" -> new SignalStyle("🔴", normalized);
            case "HOLD", "NEUTRAL" -> new SignalStyle("⚪", normalized);
            default -> new SignalStyle("⚪", "UNKNOWN");
        };
    }

    private static String signalName(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : bound(value.trim().toUpperCase(Locale.ROOT), 40, fallback);
    }

    private static String formatNumber(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return "n/a";
        }
        try {
            BigDecimal decimal = new BigDecimal(String.valueOf(value));
            NumberFormat format = NumberFormat.getNumberInstance(Locale.ENGLISH);
            format.setGroupingUsed(true);
            format.setMinimumFractionDigits(0);
            format.setMaximumFractionDigits(4);
            format.setRoundingMode(RoundingMode.HALF_UP);
            return format.format(decimal);
        } catch (RuntimeException ignored) {
            return Html.escape(bound(String.valueOf(value), 80, "n/a"));
        }
    }

    private static String formatScore(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return "n/a";
        }
        try {
            BigDecimal decimal = new BigDecimal(String.valueOf(value)).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
            return decimal.toPlainString();
        } catch (RuntimeException ignored) {
            return "n/a";
        }
    }

    private static String formatDate(String value) {
        if (value == null || value.isBlank()) {
            return "n/a";
        }
        try {
            return DateTimeFormatter.ofPattern("dd MMM uuuu", Locale.ENGLISH).format(LocalDate.parse(value));
        } catch (DateTimeParseException ignored) {
            return Html.escape(bound(value, 80, "n/a"));
        }
    }

    private static String formatReasons(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return "n/a";
        }
        List<String> values = reasons.stream()
                .filter(value -> value != null && !value.isBlank())
                .limit(5)
                .map(value -> Html.escape(bound(value, 60, "")))
                .toList();
        String result = values.isEmpty() ? "n/a" : String.join(", ", values);
        if (reasons.stream().filter(value -> value != null && !value.isBlank()).count() > values.size()) {
            result += ", ...";
        }
        return result;
    }

    private static String updated(Instant instant, ZoneId zone) {
        return instant == null ? null : "<i>Updated "
                + DateTimeFormatter.ofPattern("HH:mm z", Locale.ENGLISH).withZone(zone).format(instant) + "</i>";
    }

    private static String digestItem(SignalDigestEntry item) {
        SignalStyle style = signalStyle(item.newSignal());
        String strategy = subtitle(item.strategy(), item.timeframe());
        StringBuilder block = new StringBuilder(style.marker()).append(" <b>")
                .append(Html.escape(bound(item.symbolKey(), 120, "Unknown symbol"))).append("</b>  ")
                .append(Html.escape(signalName(item.previousSignal(), "BASELINE"))).append(" → ")
                .append(Html.escape(signalName(item.newSignal(), "UNKNOWN"))).append("  @ ")
                .append(formatNumber(item.price()));
        if (strategy != null) {
            block.append("\n").append(strategy);
        }
        String date = formatDate(item.signalDate());
        String score = formatScore(item.score());
        String reasons = formatReasons(item.reasonCodes());
        block.append("\n<b>Date:</b> ").append(date).append(" · <b>Score:</b> ").append(score)
                .append("\n<b>Reasons:</b> ").append(reasons);
        return block.toString();
    }

    private static String digestFooter(int shown, int total, Instant instant, ZoneId zone) {
        int omitted = Math.max(0, total - shown);
        String count = "Showing " + shown + " of " + total + " · " + omitted + " omitted";
        String time = instant == null ? null
                : DateTimeFormatter.ofPattern("HH:mm z", Locale.ENGLISH).withZone(zone).format(instant);
        return "<i>" + count + (time == null ? "" : " · Updated " + time) + "</i>";
    }

    private static int joinedLength(String header, List<String> blocks, String candidate, String footer, String suppression) {
        int length = header.length() + NEW_BLOCK.length() + footer.length();
        for (String block : blocks) {
            length += NEW_BLOCK.length() + block.length();
        }
        if (candidate != null) {
            length += NEW_BLOCK.length() + candidate.length();
        }
        if (suppression != null) {
            length += NEW_BLOCK.length() + suppression.length();
        }
        return length;
    }

    private record SignalStyle(String marker, String label) {
    }

    private static String escapedBound(String value, int maxCodePoints) {
        String bounded = bound(value, maxCodePoints, null);
        return bounded == null ? null : Html.escape(bounded);
    }

    private static String marker(NotificationSeverity severity) {
        return switch (severity) {
            case ERROR -> "\uD83D\uDEA8";
            case WARNING -> "\u26A0\uFE0F";
            case INFO -> "\u2139\uFE0F";
        };
    }

    private static String suppression(long count) {
        return count > 0 ? "<i>Repeated notifications suppressed: " + count + "</i>" : null;
    }

    private static Map<String, Object> safeMetadata(Map<String, Object> metadata) {
        return metadata == null ? Map.of() : metadata;
    }

    private static String display(Object value) {
        String text = String.valueOf(value);
        if (value instanceof UUID || text.matches("[0-9a-fA-F-]{32,36}")) {
            return text.substring(0, Math.min(8, text.length()));
        }
        return bound(text, 240, "");
    }

    private static String number(Object value) {
        if (value == null) {
            return null;
        }
        try {
            BigDecimal decimal = new BigDecimal(String.valueOf(value));
            NumberFormat format = NumberFormat.getNumberInstance(Locale.ENGLISH);
            format.setMaximumFractionDigits(4);
            return format.format(decimal);
        } catch (NumberFormatException ignored) {
            return Html.escape(bound(String.valueOf(value), 80, ""));
        }
    }

    private static String bound(String value, int maxCodePoints, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        if (value.codePointCount(0, value.length()) <= maxCodePoints) {
            return value;
        }
        int end = value.offsetByCodePoints(0, Math.max(0, maxCodePoints - 3));
        return value.substring(0, end) + "...";
    }
}
