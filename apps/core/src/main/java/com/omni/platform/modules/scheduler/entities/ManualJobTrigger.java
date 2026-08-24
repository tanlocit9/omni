package com.omni.platform.modules.scheduler.entities;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.omni.platform.shared.entities.AuditableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(
        name = "manual_job_triggers",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_manual_job_trigger_actor_key",
                columnNames = {"actor", "idempotency_key"}),
        indexes = {
                @Index(
                        name = "idx_manual_job_triggers_definition_requested",
                        columnList = "job_definition_id, requested_at"),
                @Index(name = "idx_manual_job_triggers_execution", columnList = "execution_id")
        })
@Getter
@Setter
public class ManualJobTrigger extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_definition_id", nullable = false, updatable = false)
    private JobDefinition jobDefinition;

    @Column(name = "execution_id")
    private UUID executionId;

    @Column(nullable = false, length = 200, updatable = false)
    private String actor;

    @Column(name = "idempotency_key", nullable = false, length = 128, updatable = false)
    private String idempotencyKey;

    @Column(nullable = false, length = 500, updatable = false)
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parameters_json", nullable = false, columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> parameters = new LinkedHashMap<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ManualTriggerState state;

    @Column(name = "block_reason", columnDefinition = "text")
    private String blockReason;

    @Column(columnDefinition = "text")
    private String error;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    public enum ManualTriggerState {
        REQUESTED,
        ACCEPTED,
        BLOCKED,
        CONFLICT,
        FAILED
    }
}
