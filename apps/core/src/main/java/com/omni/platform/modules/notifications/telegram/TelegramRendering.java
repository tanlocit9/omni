package com.omni.platform.modules.notifications.telegram;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationSeverity;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationType;

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
                    new LegacySignalRenderer(),
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

    /** Keeps pre-P8 signal presentation until P8-I2 supplies purpose-specific renderers. */
    static final class LegacySignalRenderer implements Renderer {
        @Override
        public boolean supports(NotificationRequest request) {
            return request.type() == NotificationType.SIGNAL && request.kind() != NotificationKind.MANUAL_GENERIC;
        }

        @Override
        public String render(NotificationRequest request, long suppressedCount) {
            Builder builder = new Builder();
            builder.required("<b>" + Html.escape(String.valueOf(request.severity())) + "</b> - <b>"
                    + Html.escape(bound(request.title(), 320, "Untitled notification")) + "</b>");
            builder.optional(escapedBound(request.message(), 3_200));
            builder.optional(suppression(suppressedCount));
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
            return true;
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
