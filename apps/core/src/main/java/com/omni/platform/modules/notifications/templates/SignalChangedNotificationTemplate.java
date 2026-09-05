package com.omni.platform.modules.notifications.templates;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.omni.platform.modules.notifications.dtos.NotificationChannel;
import com.omni.platform.modules.notifications.dtos.NotificationRequest;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationKind;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.SignalChangedContent;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationSeverity;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationType;
import com.omni.platform.modules.notifications.events.SignalChangedNotificationEvent;

@Component
public class SignalChangedNotificationTemplate extends AbstractNotificationTemplate<SignalChangedNotificationEvent> {

    @Override
    public NotificationRequest render(SignalChangedNotificationEvent event) {
        Map<String, Object> metadata = metadata(event.metadata());
        metadata.put("executionId", event.executionId());
        metadata.put("parentExecutionId", event.parentExecutionId());
        metadata.put("symbolKey", event.symbolKey());
        metadata.put("previousSignal", event.previousSignal());
        metadata.put("newSignal", event.newSignal());
        metadata.put("price", event.price());
        metadata.put("signalDate", event.signalDate());
        metadata.put("score", event.score());
        metadata.put("reasonCodes", event.reasonCodes());
        metadata.put("strategy", event.strategy());
        metadata.put("timeframe", event.timeframe());
        metadata.put("createdAt", event.createdAt());
        String deterministicIdentity = event.executionId() + ":" + event.symbolKey() + ":" + event.newSignal()
                + ":" + event.createdAt();
        return new NotificationRequest(
                NotificationChannel.SIGNALS,
                NotificationType.SIGNAL,
                NotificationKind.SIGNAL_CHANGED,
                NotificationSeverity.INFO,
                "Signal changed: " + event.symbolKey(),
                event.symbolKey() + ": " + defaultText(event.previousSignal(), "BASELINE") + " -> "
                        + event.newSignal() + " @ " + valueOrDefault(event.price(), "n/a")
                        + " (" + defaultText(event.signalDate(), "no date") + ", score="
                        + valueOrDefault(event.score(), "n/a") + ")",
                metadata,
                defaultText(event.deliveryIdentity(), deterministicIdentity),
                new SignalChangedContent(
                        event.symbolKey(),
                        event.previousSignal(),
                        event.newSignal(),
                        event.price(),
                        event.signalDate(),
                        event.score(),
                        event.reasonCodes(),
                        event.strategy(),
                        event.timeframe(),
                        event.createdAt()));
    }

    private String valueOrDefault(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }
}
