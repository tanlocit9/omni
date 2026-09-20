package com.omni.platform.modules.notifications.templates;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.omni.platform.modules.notifications.dtos.NotificationChannel;
import com.omni.platform.modules.notifications.dtos.NotificationRequest;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationKind;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.SignalDigestContent;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.SignalDigestEntry;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationSeverity;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationType;
import com.omni.platform.modules.notifications.events.SignalDigestNotificationEvent;

@Component
public class SignalNotificationTemplate extends AbstractNotificationTemplate<SignalDigestNotificationEvent> {

    @Override
    public NotificationRequest render(SignalDigestNotificationEvent event) {
        return pages(event).getFirst();
    }

    public List<NotificationRequest> pages(SignalDigestNotificationEvent event) {
        List<com.omni.platform.modules.notifications.events.SignalDigestItem> sorted =
                (event.items() == null ? List.<com.omni.platform.modules.notifications.events.SignalDigestItem>of()
                        : event.items()).stream()
                        .filter(item -> item != null && item.symbolKey() != null)
                        .sorted(Comparator.comparing(
                                com.omni.platform.modules.notifications.events.SignalDigestItem::symbolKey)
                                .thenComparing(item -> defaultText(item.strategy(), ""))
                                .thenComparing(item -> defaultText(item.timeframe(), "")))
                        .toList();
        if (sorted.isEmpty()) {
            throw new IllegalArgumentException("Signal digest requires at least one eligible item");
        }
        int pageCount = sorted.size();
        return java.util.stream.IntStream.range(0, pageCount)
                .mapToObj(index -> page(event, sorted.get(index), index + 1, pageCount))
                .toList();
    }

    public NotificationRequest digest(SignalDigestNotificationEvent event) {
        return render(event);
    }

    private NotificationRequest page(
            SignalDigestNotificationEvent event,
            com.omni.platform.modules.notifications.events.SignalDigestItem item,
            int pageNumber,
            int pageCount) {
        return new NotificationRequest(
                NotificationChannel.SIGNALS,
                NotificationType.SIGNAL,
                NotificationKind.SIGNAL_DIGEST,
                NotificationSeverity.INFO,
                "Market signal changes: " + event.jobTitle(),
                buildMessage(event),
                buildMetadata(event),
                event.parentExecutionId() + ":" + pageNumber,
                structuredContent(event, List.of(item), pageNumber, pageCount));
    }

    private String buildMessage(SignalDigestNotificationEvent event) {
        StringBuilder message = new StringBuilder();
        message.append(event.changedCount())
                .append(" signal change(s) detected")
                .append(" for strategy ")
                .append(event.strategy())
                .append(" on timeframe ")
                .append(event.timeframe())
                .append(".");

        List<com.omni.platform.modules.notifications.events.SignalDigestItem> items =
                event.items() == null ? List.of() : event.items();
        items.stream()
                .limit(20)
                .forEach(item -> message.append(System.lineSeparator())
                        .append("- ")
                        .append(item.symbolKey())
                        .append(": ")
                        .append(defaultText(item.previousSignal(), "BASELINE"))
                        .append(" -> ")
                        .append(item.newSignal())
                        .append(" @ ")
                        .append(defaultText(item.price(), "n/a"))
                        .append(" (")
                        .append(defaultText(item.signalDate(), "no date"))
                        .append(", score=")
                        .append(defaultText(item.score(), "n/a"))
                        .append(")"));

        if (items.size() > 20) {
            message.append(System.lineSeparator())
                    .append("...")
                    .append(items.size() - 20)
                    .append(" more changes omitted");
        }

        return message.toString();
    }

    private SignalDigestContent structuredContent(
            SignalDigestNotificationEvent event,
            List<com.omni.platform.modules.notifications.events.SignalDigestItem> pageItems,
            int pageNumber,
            int pageCount) {
        List<SignalDigestEntry> items = pageItems.stream()
                .map(item -> new SignalDigestEntry(
                        item.symbolKey(),
                        item.previousSignal(),
                        item.newSignal(),
                        item.price(),
                        item.signalDate(),
                        item.score(),
                        item.reasonCodes(),
                        item.strategy(),
                        item.timeframe()))
                .toList();
        return new SignalDigestContent(
                event.strategy(),
                event.timeframe(),
                event.changedCount(),
                items,
                timestamp(event.metadata()),
                pageNumber,
                pageCount);
    }

    private Instant timestamp(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        Object value = metadata.get("createdAt");
        if (value == null) {
            value = metadata.get("generatedAt");
        }
        try {
            return value instanceof Instant instant ? instant : value == null ? null : Instant.parse(String.valueOf(value));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Map<String, Object> buildMetadata(SignalDigestNotificationEvent event) {
        Map<String, Object> metadata = metadata(event.metadata());
        metadata.put("parentExecutionId", event.parentExecutionId());
        metadata.put("strategy", event.strategy());
        metadata.put("timeframe", event.timeframe());
        metadata.put("totalChildren", event.totalChildren());
        metadata.put("changedCount", event.changedCount());
        return metadata;
    }

}
