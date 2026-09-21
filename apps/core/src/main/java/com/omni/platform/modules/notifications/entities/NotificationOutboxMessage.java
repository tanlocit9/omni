package com.omni.platform.modules.notifications.entities;

import java.time.Instant;

import com.omni.platform.modules.notifications.dtos.NotificationChannel;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationKind;
import com.omni.platform.shared.entities.AbstractClaimableOutboxMessage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "notification_outbox_messages", uniqueConstraints = @UniqueConstraint(
        name = "uq_notification_outbox_delivery",
        columnNames = { "provider", "channel", "deduplication_key" }))
@Getter
@Setter
public class NotificationOutboxMessage extends AbstractClaimableOutboxMessage {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Provider provider;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private NotificationChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_kind", nullable = false, updatable = false)
    private NotificationKind notificationKind;

    @Column(name = "schema_version", nullable = false, updatable = false)
    private Integer schemaVersion;

    @Column(nullable = false, columnDefinition = "text", updatable = false)
    private String payload;

    @Column(name = "deduplication_key", nullable = false, updatable = false, length = 512)
    private String deduplicationKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @Column(name = "sent_at")
    private Instant sentAt;

    public enum Provider {
        TELEGRAM
    }

    public enum Status {
        PENDING, SENT, DEAD
    }
}
