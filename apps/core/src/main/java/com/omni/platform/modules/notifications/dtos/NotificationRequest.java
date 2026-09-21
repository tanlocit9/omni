package com.omni.platform.modules.notifications.dtos;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

public record NotificationRequest(
        NotificationChannel channel,
        NotificationType type,
        NotificationKind kind,
        NotificationSeverity severity,
        String title,
        String message,
        Map<String, Object> metadata,
        String deduplicationKey,
        StructuredContent structuredContent) {

    public NotificationRequest {
        if (kind == null) {
            throw new IllegalArgumentException("Notification kind is required");
        }
        if (kind == NotificationKind.SIGNAL_CHANGED && !(structuredContent instanceof SignalChangedContent)) {
            throw new IllegalArgumentException("SIGNAL_CHANGED requires SignalChangedContent");
        }
        if (kind == NotificationKind.SIGNAL_DIGEST && !(structuredContent instanceof SignalDigestContent)) {
            throw new IllegalArgumentException("SIGNAL_DIGEST requires SignalDigestContent");
        }
        if (kind != NotificationKind.SIGNAL_CHANGED && structuredContent instanceof SignalChangedContent) {
            throw new IllegalArgumentException(kind + " does not accept SignalChangedContent");
        }
        if (kind != NotificationKind.SIGNAL_DIGEST && structuredContent instanceof SignalDigestContent) {
            throw new IllegalArgumentException(kind + " does not accept SignalDigestContent");
        }
    }

    public NotificationRequest(
            NotificationChannel channel,
            NotificationType type,
            NotificationKind kind,
            NotificationSeverity severity,
            String title,
            String message,
            Map<String, Object> metadata,
            String deduplicationKey) {
        this(channel, type, kind, severity, title, message, metadata, deduplicationKey, null);
    }

    public enum NotificationKind {
        OPERATIONAL_GENERIC,
        JOB_SUCCEEDED,
        JOB_FAILED,
        JOB_DIGEST_SUCCEEDED,
        JOB_DIGEST_FAILED,
        SIGNAL_CHANGED,
        SIGNAL_DIGEST,
        MANUAL_GENERIC
    }

    public enum NotificationType {
        OPERATIONAL,
        SIGNAL
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "contentType")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = SignalChangedContent.class, name = "signalChanged"),
            @JsonSubTypes.Type(value = SignalDigestContent.class, name = "signalDigest")
    })
    public sealed interface StructuredContent permits SignalChangedContent, SignalDigestContent {
    }

    public record SignalChangedContent(
            String symbolKey,
            String previousSignal,
            String newSignal,
            Object price,
            String signalDate,
            Object score,
            List<String> reasonCodes,
            String strategy,
            String timeframe,
            Instant createdAt) implements StructuredContent {
    }

    public record SignalDigestContent(
            String strategy,
            String timeframe,
            int changedCount,
            List<SignalDigestEntry> items,
            Instant createdAt,
            int pageNumber,
            int pageCount) implements StructuredContent {
        public SignalDigestContent(
                String strategy,
                String timeframe,
                int changedCount,
                List<SignalDigestEntry> items,
                Instant createdAt) {
            this(strategy, timeframe, changedCount, items, createdAt, 1, 1);
        }
    }

    public record SignalDigestEntry(
            String symbolKey,
            String previousSignal,
            String newSignal,
            Object price,
            String signalDate,
            Object score,
            List<String> reasonCodes,
            String strategy,
            String timeframe) {
    }

    public enum NotificationSeverity {
        INFO,
        WARNING,
        ERROR
    }
}
