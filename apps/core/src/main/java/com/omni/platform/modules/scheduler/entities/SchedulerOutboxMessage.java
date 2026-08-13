package com.omni.platform.modules.scheduler.entities;

import java.time.Instant;
import java.util.UUID;

import com.omni.platform.shared.entities.AuditableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "scheduler_outbox_messages", uniqueConstraints = @UniqueConstraint(
        name = "uq_scheduler_outbox_execution_message",
        columnNames = { "execution_id", "message_index" }))
@Getter
@Setter
public class SchedulerOutboxMessage extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "execution_id", nullable = false, updatable = false)
    private JobExecutionHistory execution;

    @Column(name = "message_index", nullable = false, updatable = false)
    private Integer messageIndex;

    @Column(nullable = false, updatable = false)
    private String topic;

    @Column(name = "message_key", updatable = false)
    private String messageKey;

    @Column(nullable = false, columnDefinition = "text", updatable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @Column(nullable = false)
    private Integer attempts = 0;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "claim_token")
    private UUID claimToken;

    @Column(name = "claimed_by")
    private String claimedBy;

    @Column(name = "claim_until")
    private Instant claimUntil;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    public enum Status {
        PENDING, PUBLISHED
    }
}

