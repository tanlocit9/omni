package com.omni.platform.modules.scheduler.entities;

import java.time.Instant;
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
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "sync_job_log")
@Getter
@Setter
public class SyncJobLog extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false, updatable = false)
    private SyncJob job;

    @Enumerated(EnumType.STRING)
    @Column(name = "used_source", nullable = false)
    private SyncJob.DataSource usedSource;

    @Column(nullable = false)
    private Integer attempt = 1;

    @Column(name = "parent_log_id")
    private UUID parentLogId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SyncStatus status;

    @Column(name = "triggered_at", nullable = false, updatable = false)
    private Instant triggeredAt = Instant.now();

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "records_synced")
    private Integer recordsSynced;

    @Column(name = "records_skipped")
    private Integer recordsSkipped;

    @Column(name = "new_offset")
    private String newOffset;

    @Column(columnDefinition = "text")
    private String error;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "meta_json", columnDefinition = "jsonb")
    private Map<String, Object> metaJson;

    public enum SyncStatus {
        PENDING, RUNNING, SUCCESS, FAILED
    }
}