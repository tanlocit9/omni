package com.omni.platform.modules.notifications.controllers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.omni.platform.modules.notifications.dtos.NotificationRequest;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationKind;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationSeverity;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationType;
import com.omni.platform.modules.notifications.events.SignalChangedNotificationEvent;
import com.omni.platform.modules.notifications.services.ManualLatestSignalNotificationService;
import com.omni.platform.modules.notifications.services.ManualLatestSignalNotificationService.LatestSignalResult;
import com.omni.platform.modules.notifications.services.NotificationOutboxService;
import com.omni.platform.modules.notifications.templates.SignalChangedNotificationTemplate;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/notifications/manual")
@RequiredArgsConstructor
public class ManualNotificationController {

    private final NotificationOutboxService notificationOutboxService;
    private final ManualLatestSignalNotificationService latestSignalNotificationService;
    private final SignalChangedNotificationTemplate signalChangedNotificationTemplate;

    @PostMapping("/signal/latest")
    public ResponseEntity<ManualLatestSignalNotificationResponse> sendLatestSignalNotification(
            @RequestParam(required = false) String symbolKey) {
        String normalizedSymbol = symbolKey == null || symbolKey.isBlank() ? null : symbolKey.trim();
        LatestSignalResult latest = latestSignalNotificationService.findLatest(normalizedSymbol);
        UUID requestIdentity = UUID.randomUUID();
        String deliveryIdentity = "manual-latest:" + requestIdentity;
        Instant acceptedAt = Instant.now();
        SignalChangedNotificationEvent event = new SignalChangedNotificationEvent(
                requestIdentity,
                requestIdentity,
                latest.symbolKey(),
                latest.previousSignal(),
                latest.newSignal(),
                latest.price(),
                latest.signalDate(),
                latest.reasonCodes(),
                latest.score(),
                latest.strategy(),
                latest.timeframe(),
                acceptedAt,
                Map.of("manual", true, "generatedAt", latest.generatedAt()),
                deliveryIdentity);
        notificationOutboxService.enqueue(signalChangedNotificationTemplate.render(event), acceptedAt);
        return ResponseEntity.accepted().body(new ManualLatestSignalNotificationResponse(
                "ACCEPTED",
                latest.symbolKey(),
                latest.newSignal(),
                latest.signalDate(),
                latest.generatedAt(),
                deliveryIdentity));
    }

    @PostMapping("/signal")
    public ResponseEntity<ManualSignalNotificationResponse> sendSignalNotification(
            @RequestBody(required = false) ManualSignalNotificationRequest request) {
        ManualSignalNotificationRequest resolved = request == null
                ? ManualSignalNotificationRequest.defaults().withDefaults()
                : request.withDefaults();
        String deliveryIdentity = "manual:" + UUID.randomUUID();
        NotificationRequest notification = new NotificationRequest(
                com.omni.platform.modules.notifications.dtos.NotificationChannel.SIGNALS,
                NotificationType.SIGNAL,
                NotificationKind.MANUAL_GENERIC,
                NotificationSeverity.INFO,
                resolved.title(),
                resolved.message(),
                resolved.metadata(),
                deliveryIdentity);

        notificationOutboxService.enqueue(notification, Instant.now());
        return ResponseEntity.accepted().body(new ManualSignalNotificationResponse(
                "ACCEPTED",
                resolved.title(),
                resolved.metadata(),
                deliveryIdentity));
    }

    public record ManualSignalNotificationRequest(
            String title,
            String message,
            String symbolKey,
            String previousSignal,
            String newSignal,
            Double price,
            String signalDate,
            List<String> reasonCodes,
            String strategy,
            String timeframe) {

        static ManualSignalNotificationRequest defaults() {
            return new ManualSignalNotificationRequest(
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }

        ManualSignalNotificationRequest withDefaults() {
            return new ManualSignalNotificationRequest(
                    defaultText(title, "Manual signal notification test"),
                    defaultText(message, defaultMessage()),
                    defaultText(symbolKey, "HOSE-HPG"),
                    defaultText(previousSignal, "NEUTRAL"),
                    defaultText(newSignal, "BULLISH"),
                    price == null ? 28000.0 : price,
                    defaultText(signalDate, Instant.now().toString()),
                    reasonCodes == null || reasonCodes.isEmpty()
                            ? List.of("MANUAL_TEST")
                            : List.copyOf(reasonCodes),
                    defaultText(strategy, "MANUAL_TEST"),
                    defaultText(timeframe, "1d"));
        }

        Map<String, Object> metadata() {
            return Map.of(
                    "symbolKey", symbolKey,
                    "previousSignal", previousSignal,
                    "newSignal", newSignal,
                    "price", price,
                    "signalDate", signalDate,
                    "reasonCodes", reasonCodes,
                    "strategy", strategy,
                    "timeframe", timeframe,
                    "manual", true);
        }

        private String defaultMessage() {
            return "Manual signal notification: "
                    + defaultText(symbolKey, "HOSE-HPG")
                    + " "
                    + defaultText(previousSignal, "NEUTRAL")
                    + " -> "
                    + defaultText(newSignal, "BULLISH")
                    + " @ "
                    + (price == null ? 28000.0 : price);
        }

        private static String defaultText(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value;
        }
    }

    public record ManualLatestSignalNotificationResponse(
            String status,
            String symbolKey,
            String newSignal,
            String signalDate,
            String generatedAt,
            String deliveryIdentity) {
    }

    public record ManualSignalNotificationResponse(
            String status,
            String title,
            Map<String, Object> metadata,
            String deliveryIdentity) {
    }
}
