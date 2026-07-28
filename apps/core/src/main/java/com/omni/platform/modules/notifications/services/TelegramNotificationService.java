package com.omni.platform.modules.notifications.services;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.omni.platform.modules.notifications.configs.TelegramNotificationProperties;
import com.omni.platform.modules.notifications.dtos.NotificationRequest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramNotificationService implements NotificationService {

    private static final int MAX_MESSAGE_LENGTH = 4_096;

    private final TelegramNotificationProperties properties;
    private final RestClient telegramRestClient;

    @Override
    public void send(NotificationRequest request) {
        if (!properties.isConfigured()) {
            log.debug("Telegram notification skipped because Telegram notifications are disabled or not configured");
            return;
        }

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("chat_id", properties.chatId());
            payload.put("text", truncate(formatMessage(request)));
            if (hasText(properties.parseMode())) {
                payload.put("parse_mode", properties.parseMode());
            }

            telegramRestClient.post()
                    .uri("/bot{token}/sendMessage", properties.botToken())
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception exc) {
            log.warn("Telegram notification delivery failed: {}", exc.getMessage(), exc);
        }
    }

    private String formatMessage(NotificationRequest request) {
        StringBuilder message = new StringBuilder();
        message.append("[").append(request.severity()).append("] ").append(request.title());
        if (hasText(request.message())) {
            message.append(System.lineSeparator()).append(request.message());
        }
        if (request.metadata() != null && !request.metadata().isEmpty()) {
            message.append(System.lineSeparator())
                    .append(System.lineSeparator())
                    .append(request.metadata().entrySet().stream()
                            .filter(entry -> entry.getValue() != null)
                            .map(entry -> entry.getKey() + "=" + entry.getValue())
                            .collect(Collectors.joining(System.lineSeparator())));
        }
        return message.toString();
    }

    private String truncate(String message) {
        if (message.length() <= MAX_MESSAGE_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_MESSAGE_LENGTH - 3) + "...";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
