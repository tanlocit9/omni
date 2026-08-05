package com.omni.platform.modules.scheduler.notifications;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.omni.platform.modules.notifications.dtos.NotificationRequest;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationSeverity;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationType;
import com.omni.platform.modules.notifications.templates.AbstractNotificationTemplate;

@Component
public class SignalNotificationTemplate extends AbstractNotificationTemplate<SignalDigestNotificationEvent> {

    @Override
    public NotificationRequest render(SignalDigestNotificationEvent event) {
        return new NotificationRequest(
                NotificationType.SIGNAL,
                NotificationSeverity.INFO,
                "Market signal changes: " + event.jobTitle(),
                buildMessage(event),
                buildMetadata(event));
    }

    public NotificationRequest digest(SignalDigestNotificationEvent event) {
        return render(event);
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

        event.items().stream()
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

        if (event.items().size() > 20) {
            message.append(System.lineSeparator())
                    .append("...")
                    .append(event.items().size() - 20)
                    .append(" more changes omitted");
        }

        return message.toString();
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
