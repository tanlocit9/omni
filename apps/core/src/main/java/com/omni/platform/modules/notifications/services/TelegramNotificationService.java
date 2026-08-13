package com.omni.platform.modules.notifications.services;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.omni.platform.modules.notifications.configs.TelegramNotificationProperties;
import com.omni.platform.modules.notifications.dtos.NotificationRequest;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class TelegramNotificationService implements NotificationService {

    private static final int MAX_MESSAGE_LENGTH = 4_096;
    private static final String HTML_PARSE_MODE = "HTML";

    private final TelegramNotificationProperties properties;
    private final RestClient telegramRestClient;
    private final NotificationDeduplicator deduplicator;

    public TelegramNotificationService(
            TelegramNotificationProperties properties,
            RestClient telegramRestClient,
            Clock notificationClock) {
        this.properties = properties;
        this.telegramRestClient = telegramRestClient;
        this.deduplicator = new NotificationDeduplicator(
                properties.resolvedDeduplicationCooldown(),
                properties.resolvedDeduplicationMaxCacheSize(),
                notificationClock);
    }

    @Override
    public void send(NotificationRequest request) {
        if (!properties.isConfigured()) {
            log.warn(
                    "Telegram notification skipped: enabled={} botTokenPresent={} chatIdPresent={} apiBaseUrl={} requestType={} severity={} title={}",
                    properties.enabled(), hasText(properties.botToken()), hasText(properties.chatId()),
                    properties.resolvedApiBaseUrl(), request.type(), request.severity(), request.title());
            return;
        }

        NotificationDeduplicator.Admission admission = deduplicator.admit(request);
        if (!admission.retained()) {
            log.info("Telegram notification suppressed by cooldown type={} severity={} title={} suppressedCount={}",
                    request.type(), request.severity(), request.title(), admission.suppressedCount());
            return;
        }

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("chat_id", properties.chatId());
            payload.put("text", truncate(formatMessage(request, admission.suppressedCount())));
            payload.put("parse_mode", resolveParseMode());
            payload.put("disable_notification", true);

            log.info("Sending Telegram notification type={} severity={} title={} chatIdPresent={} parseMode={} apiBaseUrl={}",
                    request.type(), request.severity(), request.title(), hasText(properties.chatId()), resolveParseMode(),
                    properties.resolvedApiBaseUrl());
            telegramRestClient.post()
                    .uri("/bot{token}/sendMessage", properties.botToken())
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Telegram notification sent type={} severity={} title={}", request.type(), request.severity(),
                    request.title());
        } catch (Exception exc) {
            log.warn("Telegram notification delivery failed type={} severity={} title={}: {}", request.type(),
                    request.severity(), request.title(), exc.getMessage(), exc);
        }
    }

    private String formatMessage(NotificationRequest request, long suppressedCount) {
        StringBuilder message = new StringBuilder();
        message.append("<b>")
                .append(escapeHtml(String.valueOf(request.severity())))
                .append("</b>")
                .append(" — ")
                .append("<b>")
                .append(escapeHtml(defaultText(request.title(), "Untitled notification")))
                .append("</b>");

        if (hasText(request.message())) {
            message.append(System.lineSeparator())
                    .append(System.lineSeparator())
                    .append(escapeHtml(request.message()));
        }

        if (request.metadata() != null && !request.metadata().isEmpty()) {
            appendMetadata(message, request.metadata());
        }

        if (suppressedCount > 0) {
            message.append(System.lineSeparator())
                    .append(System.lineSeparator())
                    .append("<i>Repeated notifications suppressed: ")
                    .append(suppressedCount)
                    .append("</i>");
        }

        return message.toString();
    }

    private void appendMetadata(StringBuilder message, Map<String, Object> metadata) {
        String renderedMetadata = metadata.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .map(entry -> "• <b>" + escapeHtml(entry.getKey()) + ":</b> "
                        + escapeHtml(String.valueOf(entry.getValue())))
                .reduce((left, right) -> left + System.lineSeparator() + right)
                .orElse("");

        if (hasText(renderedMetadata)) {
            message.append(System.lineSeparator())
                    .append(System.lineSeparator())
                    .append(renderedMetadata);
        }
    }

    private String truncate(String message) {
        if (message.length() <= MAX_MESSAGE_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_MESSAGE_LENGTH - 3) + "...";
    }

    private String resolveParseMode() {
        if (hasText(properties.parseMode())) {
            return properties.parseMode();
        }
        return HTML_PARSE_MODE;
    }

    private String defaultText(String value, String fallback) {
        if (hasText(value)) {
            return value;
        }
        return fallback;
    }

    private String escapeHtml(String value) {
        return value.replace("&", "&#38;")
                .replace("<", "&#60;")
                .replace(">", "&#62;");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
