package com.omni.platform.modules.notifications.services;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.omni.platform.modules.notifications.configs.TelegramNotificationProperties;
import com.omni.platform.modules.notifications.dtos.NotificationRequest;
import com.omni.platform.modules.notifications.telegram.TelegramRendering.Registry;
import com.omni.platform.modules.notifications.telegram.TelegramRendering.RenderedMessage;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class TelegramNotificationService implements NotificationService {

    private static final String HTML_PARSE_MODE = "HTML";

    private final TelegramNotificationProperties properties;
    private final RestClient telegramRestClient;
    private final NotificationDeduplicator deduplicator;
    private final Registry rendererRegistry;

    public TelegramNotificationService(
            TelegramNotificationProperties properties,
            RestClient telegramRestClient,
            Clock notificationClock,
            Registry rendererRegistry) {
        this.properties = properties;
        this.telegramRestClient = telegramRestClient;
        this.rendererRegistry = rendererRegistry;
        this.deduplicator = new NotificationDeduplicator(
                properties.resolvedDeduplicationCooldown(),
                properties.resolvedDeduplicationMaxCacheSize(),
                notificationClock);
    }

    @Override
    public void send(NotificationRequest request) {
        String chatId = properties.destination(request.channel());
        if (!properties.isConfigured(request.channel())) {
            log.warn(
                    "Telegram notification skipped: channel={} enabled={} botTokenPresent={} chatIdPresent={} apiBaseUrl={} requestType={} severity={} title={}",
                    request.channel(), properties.enabled(), hasText(properties.botToken()), hasText(chatId),
                    properties.resolvedApiBaseUrl(), request.type(), request.severity(), request.title());
            return;
        }

        NotificationDeduplicator.Admission admission = deduplicator.admit(request);
        if (!admission.retained()) {
            log.info("Telegram notification suppressed by cooldown channel={} type={} severity={} title={} suppressedCount={}",
                    request.channel(), request.type(), request.severity(), request.title(), admission.suppressedCount());
            return;
        }

        try {
            RenderedMessage rendered = rendererRegistry.render(request, admission.suppressedCount());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("chat_id", chatId);
            payload.put("text", rendered.html());
            payload.put("parse_mode", HTML_PARSE_MODE);
            payload.put("disable_notification", rendered.disableNotification());

            log.info("Sending Telegram notification channel={} kind={} type={} severity={} title={} renderedLength={} chatIdPresent={} parseMode={} apiBaseUrl={}",
                    request.channel(), request.kind(), request.type(), request.severity(), request.title(), rendered.html().length(),
                    hasText(chatId), HTML_PARSE_MODE, properties.resolvedApiBaseUrl());
            telegramRestClient.post()
                    .uri("/bot{token}/sendMessage", properties.botToken())
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Telegram notification sent channel={} type={} severity={} title={}", request.channel(),
                    request.type(), request.severity(), request.title());
        } catch (Exception exc) {
            log.warn("Telegram notification delivery failed channel={} type={} severity={} title={}: {}", request.channel(),
                    request.type(), request.severity(), request.title(), exc.getMessage(), exc);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
